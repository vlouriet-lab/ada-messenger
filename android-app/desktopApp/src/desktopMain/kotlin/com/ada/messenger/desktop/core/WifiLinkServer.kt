package com.ada.messenger.desktop.core

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom

/**
 * Minimal single-use HTTP server that listens for a POST from the Android app.
 *
 * Flow:
 * 1. Desktop calls [start]. Server binds a random port and returns its URL.
 * 2. Android scans the QR code containing [linkUrl], encrypts the device snapshot
 *    to the QR public key, and POSTs the encrypted payload.
 * 3. Server validates the one-time token, decrypts the payload, calls
 *    [onSnapshotReceived], then shuts itself down.
 *
 * Security model:
 * - 32-byte random token embedded in the URL prevents accidental connections.
 * - The snapshot body is encrypted with ephemeral P-256 ECDH + HKDF + AES-GCM,
 *   so a passive LAN observer cannot read identity or ratchet material.
 * - The QR code is only visible on the user's screen, so only a device that
 *   physically scanned the code can supply the correct token.
 * - Connections are only accepted on local (site-local / link-local) addresses.
 */
class WifiLinkServer(
    private val onSnapshotReceived: (snapshotJson: String) -> Unit,
    private val onError: (message: String) -> Unit = {},
    /**
     * Called just before the HTTP 200 response is sent.  Should return the TCP
     * port of the [SyncReceiveServer] that is ready to accept sync-push payloads
     * from the phone, or 0 if no sync server is running.
     */
    private val syncPortProvider: () -> Int = { 0 },
) {
    private var serverSocket: ServerSocket? = null

    /** Full URL to display as QR.  Only valid after [start] returns. */
    var linkUrl: String = ""
        private set

    private val token: String = buildToken()
    private val encryptionKeyPair: WifiLinkServerKeyPair = WifiLinkCrypto.generateServerKeyPair()

    /**
     * Start accepting.  Binds a random OS-assigned port and spawns a daemon
     * thread.  The thread exits after the first successful snapshot is received
     * or [stop] is called.
     */
    fun start() {
        val localAddress = getLocalBindAddress()
        val ss = ServerSocket(0, 1, localAddress)
        serverSocket = ss
        val localIp = localAddress.hostAddress ?: "127.0.0.1"
        linkUrl = "http://$localIp:${ss.localPort}/link?token=$token&v=2&pk=${encryptionKeyPair.publicKeyB64}"

        val thread = Thread({ acceptLoop(ss) }, "wifi-link-server")
        thread.isDaemon = true
        thread.start()
    }

    /** Stop the server (safe to call multiple times or before [start]). */
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
            ss.use { server ->
                while (!server.isClosed) {
                    val client: Socket = try {
                        server.accept()
                    } catch (_: SocketException) {
                        break // closed by stop()
                    }
                    if (handleClient(client)) {
                        // One-shot: stop accepting after the first valid payload.
                        break
                    }
                }
            }
        } catch (e: Exception) {
            onError("WifiLinkServer loop error: ${e.message}")
        } finally {
            stop()
        }
    }

    private fun handleClient(client: Socket): Boolean {
        client.use { sock ->
            sock.soTimeout = 20_000 // 20 s read timeout
            try {
                if (!isAllowedRemote(sock.inetAddress)) {
                    writeResponse(sock, 403, "Forbidden")
                    return false
                }

                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))

                // Read request line
                val requestLine = reader.readLine() ?: return false
                // Only accept POST /link
                if (!requestLine.startsWith("POST ")) {
                    writeResponse(sock, 405, "Method Not Allowed")
                    return false
                }
                val path = requestLine.split(" ").getOrNull(1) ?: ""
                val queryToken = extractQueryParam(path, "token")
                if (queryToken != token) {
                    writeResponse(sock, 403, "Forbidden")
                    return false
                }

                // Read headers to find Content-Length
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val colonIdx = line.indexOf(':')
                    if (colonIdx > 0) {
                        headers[line.substring(0, colonIdx).trim().lowercase()] =
                            line.substring(colonIdx + 1).trim()
                    }
                }
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0

                if (contentLength <= 0 || contentLength > MAX_BODY_BYTES) {
                    writeResponse(sock, 400, "Bad Request")
                    return false
                }

                // Read body
                val bodyChars = CharArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val n = reader.read(bodyChars, totalRead, contentLength - totalRead)
                    if (n == -1) break
                    totalRead += n
                }
                val body = String(bodyChars, 0, totalRead)

                if (body.isBlank()) {
                    writeResponse(sock, 400, "Bad Request")
                    return false
                }

                val snapshotJson = try {
                    WifiLinkCrypto.decryptSnapshotPayload(
                        payloadJson = body,
                        privateKey = encryptionKeyPair.keyPair.private,
                        expectedToken = token,
                        serverPublicKeyB64 = encryptionKeyPair.publicKeyB64,
                    )
                } catch (e: Exception) {
                    onError("WifiLinkServer rejected encrypted payload: ${e.message}")
                    writeResponse(sock, 400, "Bad Request")
                    return false
                }

                writeResponse(sock, 200, "OK", syncPortProvider())
                onSnapshotReceived(snapshotJson)
                return true
            } catch (e: Exception) {
                onError("WifiLinkServer client error: ${e.message}")
            }
        }
        return false
    }

    private fun writeResponse(sock: Socket, code: Int, status: String, syncPort: Int = 0) {
        try {
            val bodyStr = if (code == 200) {
                """{"ok":true,"sync_port":$syncPort}"""
            } else {
                status
            }
            val bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
            val contentType = if (code == 200) "application/json" else "text/plain"
            val response = buildString {
                append("HTTP/1.1 $code $status\r\n")
                append("Content-Type: $contentType; charset=utf-8\r\n")
                append("Content-Length: ${bodyBytes.size}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            val out = sock.getOutputStream()
            out.write(response.toByteArray(Charsets.US_ASCII))
            out.write(bodyBytes)
            out.flush()
        } catch (_: Exception) {
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun extractQueryParam(path: String, name: String): String? {
        val query = path.substringAfter('?', "")
        return query.split('&').firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
    }

    private companion object {
        private const val MAX_BODY_BYTES = 5 * 1024 * 1024

        fun buildToken(): String {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun isAllowedRemote(address: InetAddress): Boolean =
            address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress

        /**
         * Return the first site-local IPv4 address of any non-loopback interface,
         * falling back to the loopback address if none is found.
         */
        fun getLocalBindAddress(): InetAddress {
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
}
