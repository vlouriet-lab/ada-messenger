package com.ada.messenger.core

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object WifiLinkCrypto {
    private const val VERSION = 2
    private const val ALGORITHM = "P256-HKDF-SHA256-AESGCM"
    private const val CURVE = "secp256r1"
    private const val GCM_TAG_BITS = 128
    private const val AES_KEY_BYTES = 32
    private const val IV_BYTES = 12
    private val secureRandom = SecureRandom()

    fun encryptSnapshotForServer(serverUrl: String, snapshotJson: String): String {
        val parsed = URL(serverUrl)
        val token = queryParam(parsed, "token") ?: error("missing pairing token")
        val serverPublicKeyB64 = queryParam(parsed, "pk") ?: error("missing pairing public key")
        val serverPublicKey = decodePublicKey(serverPublicKeyB64)
        val ephemeralKeyPair = generateEphemeralKeyPair()
        val ephemeralPublicKeyB64 = base64UrlEncode(ephemeralKeyPair.public.encoded)

        val sharedSecret = deriveSharedSecret(ephemeralKeyPair, serverPublicKey)
        val aesKey = hkdfSha256(
            ikm = sharedSecret,
            salt = pairingSalt(token, serverPublicKeyB64),
            info = "ADA-WIFI-LINK snapshot v2".toByteArray(Charsets.UTF_8),
            outputLength = AES_KEY_BYTES,
        )

        return try {
            val iv = ByteArray(IV_BYTES).also(secureRandom::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(pairingAad(token, serverPublicKeyB64, ephemeralPublicKeyB64))
            val ciphertext = cipher.doFinal(snapshotJson.toByteArray(Charsets.UTF_8))

            JSONObject()
                .put("v", VERSION)
                .put("alg", ALGORITHM)
                .put("epk", ephemeralPublicKeyB64)
                .put("iv", base64UrlEncode(iv))
                .put("ct", base64UrlEncode(ciphertext))
                .toString()
        } finally {
            sharedSecret.fill(0)
            aesKey.fill(0)
        }
    }

    fun hasRequiredQuery(url: String): Boolean = runCatching {
        val parsed = URL(url)
        queryParam(parsed, "v") == VERSION.toString() &&
            queryParam(parsed, "token")?.matches(Regex("[0-9a-fA-F]{64}")) == true &&
            queryParam(parsed, "pk")?.let { base64UrlDecode(it).size in 80..160 } == true
    }.getOrDefault(false)

    private fun generateEphemeralKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(CURVE), secureRandom)
        return generator.generateKeyPair()
    }

    private fun decodePublicKey(publicKeyB64: String): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(base64UrlDecode(publicKeyB64)))

    private fun deriveSharedSecret(keyPair: KeyPair, peerPublicKey: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(keyPair.private)
            doPhase(peerPublicKey, true)
            generateSecret()
        }

    private fun pairingSalt(token: String, serverPublicKeyB64: String): ByteArray = sha256(
        "ADA-WIFI-LINK salt v2\n$token\n$serverPublicKeyB64".toByteArray(Charsets.UTF_8),
    )

    private fun pairingAad(token: String, serverPublicKeyB64: String, ephemeralPublicKeyB64: String): ByteArray =
        "ADA-WIFI-LINK aad v2\n$token\n$serverPublicKeyB64\n$ephemeralPublicKeyB64".toByteArray(Charsets.UTF_8)

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, outputLength: Int): ByteArray {
        val prk = hmacSha256(salt, ikm)
        val output = ByteArrayOutputStream(outputLength)
        var previous = ByteArray(0)
        var counter = 1
        try {
            while (output.size() < outputLength) {
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(prk, "HmacSHA256"))
                mac.update(previous)
                mac.update(info)
                mac.update(counter.toByte())
                previous = mac.doFinal()
                output.write(previous)
                counter += 1
            }
            return output.toByteArray().copyOf(outputLength)
        } finally {
            prk.fill(0)
            previous.fill(0)
        }
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private fun queryParam(url: URL, name: String): String? =
        url.query
            ?.split('&')
            ?.firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.takeIf { it.isNotBlank() }

    private fun base64UrlEncode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun base64UrlDecode(value: String): ByteArray =
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}