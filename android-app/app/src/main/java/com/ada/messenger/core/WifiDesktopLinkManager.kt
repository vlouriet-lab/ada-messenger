package com.ada.messenger.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * Sends the encrypted device snapshot to the ADA Windows app via Wi-Fi HTTP POST.
 *
 * Protocol:
 *   - Windows PC starts an HTTP server on a random port, generates a one-time token
 *     plus an ephemeral P-256 public key, and displays both in the QR URL.
 *   - Android scans the QR, encrypts the snapshot with ECDH/HKDF/AES-GCM, and POSTs
 *     only the encrypted payload.
 *   - Server validates the token, decrypts the payload, then completes the login.
 *
 * Direct Socket transport is used so that local Wi-Fi pairing works safely even when
 * the application disables cleartext HTTP globally via Android Network Security Config.
 */
class WifiDesktopLinkManager {

    companion object {
        private const val TAG = "WifiDesktopLinkManager"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_URL_LEN = 512
    }

    sealed class WifiLinkResult {
        /** Pairing succeeded.  [syncPort] is the port of the desktop sync server
         *  (0 means the desktop did not report one). */
        data class Success(val syncPort: Int = 0) : WifiLinkResult()
        data class Failure(val reason: String) : WifiLinkResult()
    }

    /**
     * POST [identityJson] to [serverUrl] (the full URL including token from the QR code).
     *
     * Must be called from a coroutine. Runs on [Dispatchers.IO].
     *
     * @param serverUrl   Full URL from QR scan, e.g. `http://192.168.1.5:49200/link?token=...`
     * @param identityJson JSON string from [AdaCore.exportIdentityJson].
     */
    suspend fun sendIdentityToPC(
        serverUrl: String,
        identityJson: String,
    ): WifiLinkResult = withContext(Dispatchers.IO) {

        // Basic validation — URL must be an http:// address on a private network
        if (!isValidServerUrl(serverUrl)) {
            return@withContext WifiLinkResult.Failure("Неверный URL сервера. Убедитесь, что QR-код получен с экрана ПК.")
        }

        Log.i(TAG, "Connecting to PC at ${redactedServer(serverUrl)}")

        return@withContext try {
            val parsedUrl = URL(serverUrl)
            val host = parsedUrl.host
            val port = if (parsedUrl.port > 0) parsedUrl.port else 80
            val path = parsedUrl.file.ifEmpty { "/link" }

            val encryptedBody = WifiLinkCrypto.encryptSnapshotForServer(serverUrl, identityJson)
            val bodyBytes = encryptedBody.toByteArray(Charsets.UTF_8)

            val (httpCode, responseBody) = postHttpOverSocket(
                host = host,
                port = port,
                path = path,
                contentType = "application/json; charset=utf-8",
                bodyBytes = bodyBytes,
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS,
            )
            Log.i(TAG, "Server responded: HTTP $httpCode")

            if (httpCode in 200..299) {
                val syncPort = parseSyncPort(responseBody)
                WifiLinkResult.Success(syncPort)
            } else {
                WifiLinkResult.Failure("Сервер вернул код $httpCode. ${responseBody.take(256)}".trim())
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendIdentityToPC failed", e)
            val msg = when {
                e.message?.contains("ECONNREFUSED") == true ->
                    "ПК недоступен. Убедитесь, что оба устройства в одной Wi-Fi сети и ADA открыта на ПК."
                e.message?.contains("timeout", ignoreCase = true) == true ->
                    "Истекло время ожидания. Проверьте соединение и повторите."
                else -> e.localizedMessage ?: "Ошибка соединения"
            }
            WifiLinkResult.Failure(msg)
        }
    }

    private fun postHttpOverSocket(
        host: String,
        port: Int,
        path: String,
        contentType: String,
        bodyBytes: ByteArray,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): Pair<Int, String> {
        Socket().use { sock ->
            sock.connect(InetSocketAddress(host, port), connectTimeoutMs)
            sock.soTimeout = readTimeoutMs
            val out = sock.getOutputStream()
            val reqHeaders = buildString {
                append("POST $path HTTP/1.1\r\n")
                append("Host: $host:$port\r\n")
                append("Content-Type: $contentType\r\n")
                append("Content-Length: ${bodyBytes.size}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            out.write(reqHeaders.toByteArray(Charsets.US_ASCII))
            out.write(bodyBytes)
            out.flush()

            val input = BufferedInputStream(sock.getInputStream())
            val statusLine = readAsciiLine(input) ?: return 500 to ""
            val parts = statusLine.split(" ")
            val code = parts.getOrNull(1)?.toIntOrNull() ?: 500

            var contentLength = -1
            while (true) {
                val line = readAsciiLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0 && line.substring(0, colon).trim().equals("content-length", ignoreCase = true)) {
                    contentLength = line.substring(colon + 1).trim().toIntOrNull() ?: -1
                }
            }

            val body = if (contentLength >= 0) {
                val buf = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(buf, read, contentLength - read)
                    if (n == -1) break
                    read += n
                }
                String(buf, 0, read, Charsets.UTF_8)
            } else {
                input.bufferedReader(Charsets.UTF_8).readText()
            }
            return code to body
        }
    }

    private fun readAsciiLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (out.size() == 0) null else out.toString(Charsets.US_ASCII.name())
            }
            if (b == '\n'.code) {
                val bytes = out.toByteArray()
                val len = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, len, Charsets.US_ASCII)
            }
            out.write(b)
            if (out.size() > 8192) throw java.io.IOException("Line too long")
        }
    }

    /**
     * Validate that [url] is a safe encrypted-v2 HTTP URL pointing to a local address.
     * Rejects non-http schemes, public hosts, excessively long URLs, and fragments.
     */
    private fun isValidServerUrl(url: String): Boolean {
        if (url.length > MAX_URL_LEN) return false
        return try {
            val parsed = URL(url)
            parsed.protocol == "http" &&
                parsed.host.isNotEmpty() &&
                parsed.ref == null &&
                parsed.path == "/link" &&
                isPrivateHost(parsed.host) &&
                WifiLinkCrypto.hasRequiredQuery(url)
        } catch (_: Exception) {
            false
        }
    }

    private fun isPrivateHost(host: String): Boolean = runCatching {
        val address = InetAddress.getByName(host)
        address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress
    }.getOrDefault(false)

    private fun redactedServer(url: String): String = runCatching {
        val parsed = URL(url)
        "${parsed.host}:${parsed.port.takeIf { it > 0 } ?: parsed.defaultPort}${parsed.path}"
    }.getOrDefault("desktop")

    /** Extract `sync_port` from the server response JSON body (safe no-crash approach). */
    private fun parseSyncPort(json: String): Int {
        // Minimal JSON parsing without a dependency: look for "sync_port":<digits>
        val match = Regex(""""sync_port"\s*:\s*(\d+)""").find(json)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }
}
