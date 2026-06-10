package com.ada.messenger.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
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
 * No additional dependencies required — uses [java.net.HttpURLConnection] (always available).
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

        var conn: HttpURLConnection? = null
        return@withContext try {
            conn = (URL(serverUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
            }

            val encryptedBody = WifiLinkCrypto.encryptSnapshotForServer(serverUrl, identityJson)
            val bodyBytes = encryptedBody.toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(bodyBytes) }

            val httpCode = conn.responseCode
            Log.i(TAG, "Server responded: HTTP $httpCode")

            if (httpCode in 200..299) {
                // Read response body to extract sync_port from {"ok":true,"sync_port":N}
                val responseBody = runCatching {
                    conn.inputStream?.bufferedReader()?.readText()
                }.getOrNull() ?: ""
                val syncPort = parseSyncPort(responseBody)
                WifiLinkResult.Success(syncPort)
            } else {
                val body = runCatching {
                    conn.errorStream?.bufferedReader()?.readText()?.take(256)
                }.getOrNull() ?: ""
                WifiLinkResult.Failure("Сервер вернул код $httpCode. $body".trim())
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
        } finally {
            conn?.disconnect()
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
