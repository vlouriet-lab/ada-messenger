package com.ada.messenger.core

import android.util.Base64
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object DesktopSyncAuth {
    private const val AUTH_SCHEME = "ADA-Sync-HMAC v1"
    private const val NONCE_BYTES = 16
    private val secureRandom = SecureRandom()

    fun applyHeaders(
        connection: HttpURLConnection,
        linkKeyHex: String,
        method: String,
        path: String,
        body: String,
    ) {
        val nonce = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)
        val nonceB64 = base64UrlEncode(nonce)
        val macB64 = base64UrlEncode(computeMac(linkKeyHex, method, path, nonceB64, body))
        connection.setRequestProperty("Authorization", AUTH_SCHEME)
        connection.setRequestProperty("X-Ada-Sync-Nonce", nonceB64)
        connection.setRequestProperty("X-Ada-Sync-Mac", macB64)
    }

    private fun computeMac(
        linkKeyHex: String,
        method: String,
        path: String,
        nonceB64: String,
        body: String,
    ): ByteArray {
        val bodyHashB64 = base64UrlEncode(sha256(body.toByteArray(Charsets.UTF_8)))
        val message = "ADA-Sync-HMAC-v1\n${method.uppercase()}\n$path\n$nonceB64\n$bodyHashB64"
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(hexToBytes(linkKeyHex), "HmacSHA256"))
            doFinal(message.toByteArray(Charsets.UTF_8))
        }
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "invalid hex length" }
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun base64UrlEncode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}