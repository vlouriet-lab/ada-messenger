package com.ada.messenger.desktop.core

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Persistent HTTP server that receives encrypted sync-push payloads from the
 * paired Android phone.
 *
 * Protocol:
 *   POST /sync  HTTP/1.1
 *   Authorization: ADA-Sync-HMAC v1
 *   X-Ada-Sync-Nonce: <base64url 16 random bytes>
 *   X-Ada-Sync-Mac: <base64url HMAC-SHA256 over method/path/body hash>
 *   Content-Type: application/octet-stream
 *   Content-Length: <N>
 *   <body: SYNC_MAGIC | nonce | XChaCha20-Poly1305 ciphertext, base64-encoded>
 *
 * The body is base64-encoded for HTTP transport.  [onSyncPush] receives the
 * raw base64 string; the decryption happens in Rust via [DesktopAdaCore.nativeHandleSyncPush].
 *
 * Security:
 * - The link key is never sent as an HTTP header. The server verifies an HMAC
 *   using the locally stored link key, then passes that local key to Rust so
 *   AEAD validation still happens in the core.
 * - Maximum body size: 5 MB (prevents memory exhaustion from rogue senders
 *   on the LAN).
 * - Read timeout: 15 s.
 */
class SyncReceiveServer(
    private val linkKeyProvider: () -> String?,
    private val onSyncPush: (linkKeyHex: String, dataB64: String) -> Unit,
    private val onError: (message: String) -> Unit = {},
) {
    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null
    private val activeConnections = AtomicInteger(0)

    companion object {
        /** Only hex strings of exactly 64 chars (32 bytes) are accepted as Bearer tokens. */
        private val LINK_KEY_HEX_RE = Regex("[0-9a-fA-F]{64}")
        private const val AUTH_SCHEME = "ADA-Sync-HMAC v1"
        private const val MAX_BODY_BYTES = 5 * 1024 * 1024
        private const val MAX_AUTH_NONCES = 512
        /** Maximum concurrent in-flight handler threads. */
        private const val MAX_CONNECTIONS = 4
    }

    private val seenAuthNonces = ArrayDeque<String>()
    private val seenAuthNonceSet = LinkedHashSet<String>()

    /** The TCP port the server is listening on. Valid after [start] returns. */
    var port: Int = 0
        private set

    /**
     * Bind to a random port and start accepting connections in a background
     * daemon thread.  Safe to call only once.
     */
    fun start() {
        val ss = ServerSocket(0, 50, getLocalBindAddress())
        serverSocket = ss
        port = ss.localPort

        val t = Thread({ acceptLoop(ss) }, "sync-receive-server")
        t.isDaemon = true
        t.start()
        thread = t
    }

    /** Stop the server.  Safe to call multiple times. */
    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
    }

    // ── internals ──────────────────────────────────────────────────────────

    private fun acceptLoop(ss: ServerSocket) {
        try {
            while (!ss.isClosed) {
                val client: Socket = try {
                    ss.accept()
                } catch (_: SocketException) {
                    break // stopped via stop()
                }
                // Enforce a limit on concurrent in-flight handlers to prevent
                // thread exhaustion from a flood of LAN connections.
                if (activeConnections.incrementAndGet() > MAX_CONNECTIONS) {
                    activeConnections.decrementAndGet()
                    try { client.close() } catch (_: Exception) {}
                    continue
                }
                val t = Thread({
                    try { handleClient(client) } finally { activeConnections.decrementAndGet() }
                }, "sync-push-handler")
                t.isDaemon = true
                t.start()
            }
        } catch (e: Exception) {
            onError("SyncReceiveServer accept error: ${e.message}")
        }
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 15_000 // 15 s read timeout
            try {
                if (!isAllowedRemote(sock.inetAddress)) {
                    writeResponse(sock, 403, "Forbidden")
                    return
                }

                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))

                // Request line — expect "POST /sync HTTP/1.x"
                val requestLine = reader.readLine() ?: return
                val requestParts = requestLine.split(" ")
                val method = requestParts.getOrNull(0) ?: ""
                val path   = requestParts.getOrNull(1) ?: ""
                if (method != "POST") {
                    writeResponse(sock, 405, "Method Not Allowed")
                    return
                }
                if (path != "/sync") {
                    writeResponse(sock, 404, "Not Found")
                    return
                }

                // Parse headers
                var contentLength = 0
                var authHeader: String? = null
                var authNonce: String? = null
                var authMac: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val colonIdx = line.indexOf(':')
                    if (colonIdx > 0) {
                        val name = line.substring(0, colonIdx).trim().lowercase()
                        val value = line.substring(colonIdx + 1).trim()
                        when (name) {
                            "content-length" -> contentLength = value.toIntOrNull() ?: 0
                            "authorization"  -> authHeader = value
                            "x-ada-sync-nonce" -> authNonce = value
                            "x-ada-sync-mac" -> authMac = value
                        }
                    }
                }

                // Guard against oversized payloads (max 5 MB)
                if (contentLength <= 0 || contentLength > MAX_BODY_BYTES) {
                    writeResponse(sock, 400, "Bad Request")
                    return
                }

                // Read body (base64-encoded sealed payload)
                val bodyChars = CharArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val n = reader.read(bodyChars, totalRead, contentLength - totalRead)
                    if (n == -1) break
                    totalRead += n
                }
                val dataB64 = String(bodyChars, 0, totalRead).trim()

                if (dataB64.isEmpty()) {
                    writeResponse(sock, 400, "Bad Request")
                    return
                }

                val linkKeyHex = linkKeyProvider()
                    ?.takeIf { LINK_KEY_HEX_RE.matches(it) }
                if (linkKeyHex == null) {
                    writeResponse(sock, 503, "Service Unavailable")
                    return
                }
                if (!verifyAuth(authHeader, authNonce, authMac, linkKeyHex, dataB64)) {
                    writeResponse(sock, 401, "Unauthorized")
                    return
                }
                if (!rememberAuthNonce(authNonce.orEmpty())) {
                    writeResponse(sock, 409, "Conflict")
                    return
                }

                // Acknowledge receipt before decryption so the phone doesn't time out.
                writeResponse(sock, 200, "OK")

                // Delegate decryption + storage to Rust via the callback.
                onSyncPush(linkKeyHex, dataB64)
            } catch (e: Exception) {
                onError("SyncReceiveServer client error: ${e.message}")
            }
        }
    }

    private fun writeResponse(sock: Socket, code: Int, status: String) {
        try {
            val body = status.toByteArray(Charsets.UTF_8)
            val response = "HTTP/1.1 $code $status\r\n" +
                "Content-Type: text/plain\r\n" +
                "Cache-Control: no-store\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
            val out = sock.getOutputStream()
            out.write(response.toByteArray(Charsets.US_ASCII))
            out.write(body)
            out.flush()
        } catch (_: Exception) {
        }
    }

    private fun verifyAuth(
        authHeader: String?,
        nonceB64: String?,
        macB64: String?,
        linkKeyHex: String,
        body: String,
    ): Boolean {
        if (authHeader != AUTH_SCHEME) return false
        if (nonceB64.isNullOrBlank() || macB64.isNullOrBlank()) return false
        val nonceBytes = runCatching { Base64.getUrlDecoder().decode(nonceB64) }.getOrNull() ?: return false
        val providedMac = runCatching { Base64.getUrlDecoder().decode(macB64) }.getOrNull() ?: return false
        if (nonceBytes.size != 16 || providedMac.size != 32) return false
        val expectedMac = computeMac(linkKeyHex, "POST", "/sync", nonceB64, body)
        return MessageDigest.isEqual(expectedMac, providedMac)
    }

    @Synchronized
    private fun rememberAuthNonce(nonceB64: String): Boolean {
        if (seenAuthNonceSet.contains(nonceB64)) return false
        seenAuthNonceSet.add(nonceB64)
        seenAuthNonces.addLast(nonceB64)
        while (seenAuthNonces.size > MAX_AUTH_NONCES) {
            seenAuthNonceSet.remove(seenAuthNonces.removeFirst())
        }
        return true
    }

    private fun computeMac(
        linkKeyHex: String,
        method: String,
        path: String,
        nonceB64: String,
        body: String,
    ): ByteArray {
        val bodyHashB64 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8)))
        val message = "ADA-Sync-HMAC-v1\n${method.uppercase()}\n$path\n$nonceB64\n$bodyHashB64"
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(hexToBytes(linkKeyHex), "HmacSHA256"))
            doFinal(message.toByteArray(Charsets.UTF_8))
        }
    }

    private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { index ->
        hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private fun isAllowedRemote(address: InetAddress): Boolean =
        address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress

    private fun getLocalBindAddress(): InetAddress {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return InetAddress.getByName("127.0.0.1")
            for (iface in interfaces.asSequence()) {
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                for (addr in iface.inetAddresses.asSequence()) {
                    if (addr.isLoopbackAddress) continue
                    if (addr is java.net.Inet4Address && addr.isSiteLocalAddress) {
                        return addr
                    }
                }
            }
        } catch (_: Exception) {
        }
        return InetAddress.getByName("127.0.0.1")
    }
}
