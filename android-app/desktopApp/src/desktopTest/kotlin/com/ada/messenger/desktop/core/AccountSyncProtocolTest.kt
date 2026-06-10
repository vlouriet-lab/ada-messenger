package com.ada.messenger.desktop.core

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountSyncProtocolTest {
    @Test
    fun wifiLinkServerAcceptsEncryptedSnapshotPayload() {
        val snapshotJson = JSONObject()
            .put("identity", JSONObject().put("display_name", "sync-test"))
            .put("link_key", Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }))
            .toString()
        val receivedSnapshot = CompletableFuture<String>()
        val errors = mutableListOf<String>()
        val server = WifiLinkServer(
            onSnapshotReceived = { receivedSnapshot.complete(it) },
            onError = { errors += it },
            syncPortProvider = { 45123 },
        )

        try {
            server.start()
            val payload = encryptSnapshotForLinkUrl(server.linkUrl, snapshotJson)
            val response = post(
                url = server.linkUrl,
                body = payload,
                headers = mapOf("Content-Type" to "application/json; charset=utf-8"),
            )

            assertEquals(200, response.code)
            assertTrue(response.body.contains("\"sync_port\":45123"), response.body)
            assertEquals(snapshotJson, receivedSnapshot.get(2, TimeUnit.SECONDS))
            assertTrue(errors.isEmpty(), errors.joinToString("\n"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun syncReceiveServerAcceptsHmacAndRejectsReplayAndBearer() {
        val linkKeyHex = ByteArray(32) { (it + 1).toByte() }.toHex()
        val body = "sealed-sync-payload-base64"
        val receivedPush = CompletableFuture<Pair<String, String>>()
        val server = SyncReceiveServer(
            linkKeyProvider = { linkKeyHex },
            onSyncPush = { linkKey, data -> receivedPush.complete(linkKey to data) },
            onError = { error("SyncReceiveServer error: $it") },
        )

        try {
            server.start()
            val syncUrl = "http://${localServerHost()}:${server.port}/sync"
            val nonce = ByteArray(16) { (0xA0 + it).toByte() }
            val headers = hmacHeaders(linkKeyHex, nonce, body)

            val accepted = post(syncUrl, body, headers)
            assertEquals(200, accepted.code)
            assertEquals(linkKeyHex to body, receivedPush.get(2, TimeUnit.SECONDS))

            val replay = post(syncUrl, body, headers)
            assertEquals(409, replay.code)

            val bearer = post(
                syncUrl,
                body,
                mapOf(
                    "Authorization" to "Bearer $linkKeyHex",
                    "Content-Type" to "application/octet-stream",
                ),
            )
            assertEquals(401, bearer.code)
        } finally {
            server.stop()
        }
    }

    private data class HttpResponse(val code: Int, val body: String)

    private fun post(url: String, body: String, headers: Map<String, String>): HttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 5_000
            doOutput = true
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        return try {
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            connection.setRequestProperty("Content-Length", bodyBytes.size.toString())
            connection.outputStream.use { it.write(bodyBytes) }
            val responseBody = runCatching {
                val stream = if (connection.responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                stream?.bufferedReader()?.readText().orEmpty()
            }.getOrDefault("")
            HttpResponse(connection.responseCode, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun encryptSnapshotForLinkUrl(linkUrl: String, snapshotJson: String): String {
        val parsed = URL(linkUrl)
        val token = queryParam(parsed, "token") ?: error("missing token")
        val serverPublicKeyB64 = queryParam(parsed, "pk") ?: error("missing public key")
        val serverPublicKey = decodePublicKey(serverPublicKeyB64)
        val clientKeyPair = generateP256KeyPair()
        val clientPublicKeyB64 = base64UrlEncode(clientKeyPair.public.encoded)
        val sharedSecret = KeyAgreement.getInstance("ECDH").run {
            init(clientKeyPair.private)
            doPhase(serverPublicKey, true)
            generateSecret()
        }
        val aesKey = hkdfSha256(
            ikm = sharedSecret,
            salt = pairingSalt(token, serverPublicKeyB64),
            info = "ADA-WIFI-LINK snapshot v2".toByteArray(Charsets.UTF_8),
            outputLength = 32,
        )
        return try {
            val iv = ByteArray(12).also(SecureRandom()::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(pairingAad(token, serverPublicKeyB64, clientPublicKeyB64))
            JSONObject()
                .put("v", 2)
                .put("alg", "P256-HKDF-SHA256-AESGCM")
                .put("epk", clientPublicKeyB64)
                .put("iv", base64UrlEncode(iv))
                .put("ct", base64UrlEncode(cipher.doFinal(snapshotJson.toByteArray(Charsets.UTF_8))))
                .toString()
        } finally {
            sharedSecret.fill(0)
            aesKey.fill(0)
        }
    }

    private fun hmacHeaders(linkKeyHex: String, nonce: ByteArray, body: String): Map<String, String> {
        val nonceB64 = base64UrlEncode(nonce)
        val bodyHashB64 = base64UrlEncode(MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8)))
        val message = "ADA-Sync-HMAC-v1\nPOST\n/sync\n$nonceB64\n$bodyHashB64"
        val mac = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(linkKeyHex.hexToBytes(), "HmacSHA256"))
            doFinal(message.toByteArray(Charsets.UTF_8))
        }
        return mapOf(
            "Authorization" to "ADA-Sync-HMAC v1",
            "X-Ada-Sync-Nonce" to nonceB64,
            "X-Ada-Sync-Mac" to base64UrlEncode(mac),
            "Content-Type" to "application/octet-stream",
        )
    }

    private fun generateP256KeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        generateKeyPair()
    }

    private fun decodePublicKey(publicKeyB64: String): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(base64UrlDecode(publicKeyB64)))

    private fun pairingSalt(token: String, serverPublicKeyB64: String): ByteArray =
        sha256("ADA-WIFI-LINK salt v2\n$token\n$serverPublicKeyB64".toByteArray(Charsets.UTF_8))

    private fun pairingAad(token: String, serverPublicKeyB64: String, clientPublicKeyB64: String): ByteArray =
        "ADA-WIFI-LINK aad v2\n$token\n$serverPublicKeyB64\n$clientPublicKeyB64".toByteArray(Charsets.UTF_8)

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

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(data)
    }

    private fun localServerHost(): String {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
        for (iface in interfaces.asSequence()) {
            if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
            for (address in iface.inetAddresses.asSequence()) {
                if (!address.isLoopbackAddress && address is Inet4Address && address.isSiteLocalAddress) {
                    return address.hostAddress ?: continue
                }
            }
        }
        return InetAddress.getByName("127.0.0.1").hostAddress
    }

    private fun queryParam(url: URL, name: String): String? =
        url.query
            ?.split('&')
            ?.firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.takeIf { it.isNotBlank() }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private fun base64UrlEncode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun base64UrlDecode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}