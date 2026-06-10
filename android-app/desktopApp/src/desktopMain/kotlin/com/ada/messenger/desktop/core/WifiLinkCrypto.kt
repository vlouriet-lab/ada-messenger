package com.ada.messenger.desktop.core

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class WifiLinkServerKeyPair(
    val keyPair: KeyPair,
    val publicKeyB64: String,
)

internal object WifiLinkCrypto {
    private const val VERSION = 2
    private const val ALGORITHM = "P256-HKDF-SHA256-AESGCM"
    private const val CURVE = "secp256r1"
    private const val GCM_TAG_BITS = 128
    private const val AES_KEY_BYTES = 32
    private val secureRandom = SecureRandom()

    fun generateServerKeyPair(): WifiLinkServerKeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(CURVE), secureRandom)
        val keyPair = generator.generateKeyPair()
        return WifiLinkServerKeyPair(
            keyPair = keyPair,
            publicKeyB64 = base64UrlEncode(keyPair.public.encoded),
        )
    }

    fun decryptSnapshotPayload(
        payloadJson: String,
        privateKey: PrivateKey,
        expectedToken: String,
        serverPublicKeyB64: String,
    ): String {
        val payload = JSONObject(payloadJson)
        require(payload.optInt("v", 0) == VERSION) { "unsupported pairing payload version" }
        require(payload.optString("alg") == ALGORITHM) { "unsupported pairing payload algorithm" }

        val ephemeralPublicKeyB64 = payload.getString("epk")
        val iv = base64UrlDecode(payload.getString("iv"))
        val ciphertext = base64UrlDecode(payload.getString("ct"))
        require(iv.size == 12) { "invalid pairing payload iv" }
        require(ciphertext.isNotEmpty()) { "empty pairing payload" }

        val peerPublicKey = decodePublicKey(ephemeralPublicKeyB64)
        val sharedSecret = deriveSharedSecret(privateKey, peerPublicKey)
        val aesKey = hkdfSha256(
            ikm = sharedSecret,
            salt = pairingSalt(expectedToken, serverPublicKeyB64),
            info = "ADA-WIFI-LINK snapshot v2".toByteArray(Charsets.UTF_8),
            outputLength = AES_KEY_BYTES,
        )

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(pairingAad(expectedToken, serverPublicKeyB64, ephemeralPublicKeyB64))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } finally {
            sharedSecret.fill(0)
            aesKey.fill(0)
        }
    }

    private fun decodePublicKey(publicKeyB64: String): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(base64UrlDecode(publicKeyB64)))

    private fun deriveSharedSecret(privateKey: PrivateKey, peerPublicKey: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
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

    private fun base64UrlEncode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun base64UrlDecode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
}