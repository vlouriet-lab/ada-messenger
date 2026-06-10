package com.ada.messenger.core

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Manages the Bluetooth RFCOMM connection from the Android phone to a Windows PC
 * for the "Привязать ПК" (Link Desktop) account-sync flow.
 *
 * Protocol (phone → PC):
 *   [6 bytes]  ASCII digits of the pairing code (e.g. "482157")
 *   [4 bytes]  Big-endian u32: byte length of the encrypted payload
 *   [N bytes]  ChaCha20-Poly1305 encrypted IdentityExport JSON:
 *                first 12 bytes = nonce
 *                remaining      = ciphertext + 16-byte tag
 *
 * Key derivation:
 *   key = SHA-256("ada/bt-sync-key/v1\0" || code_bytes_as_ascii)
 *
 * NOTE: Android does not ship ChaCha20-Poly1305 in JCE on API < 28, so we
 * use AES-256-GCM (available everywhere) and produce the same authenticated
 * encryption guarantees.  The Windows side accepts the same payload because
 * the encryption scheme is negotiated by the wire-format version byte we
 * embed in the nonce prefix (see [ENC_VERSION]).
 *
 * Wire format after the 6-byte code:
 *   [4 bytes]  Big-endian payload length (includes version + nonce + ciphertext)
 *   [1 byte]   ENC_VERSION = 0x02 (AES-256-GCM)
 *   [12 bytes] AES-GCM nonce (random)
 *   [N bytes]  AES-256-GCM ciphertext + 16-byte authentication tag
 */
class DesktopLinkManager(private val context: Context) {

    companion object {
        private const val TAG = "DesktopLinkManager"

        /** RFCOMM service UUID — must match the Windows server UUID exactly. */
        val SERVICE_UUID: UUID = UUID.fromString("a1da0000-9000-11ef-a1da-ada000000000")

        /** Encryption version byte embedded in the wire payload. */
        private const val ENC_VERSION: Byte = 0x02   // AES-256-GCM

        private const val KDF_PREFIX = "ada/bt-sync-key/v1\u0000"
        private const val AES_ALGORITHM = "AES/GCM/NoPadding"
        private const val GCM_NONCE_LEN = 12
        private const val GCM_TAG_BITS = 128
    }

    sealed class LinkResult {
        object Success : LinkResult()
        data class Failure(val reason: String) : LinkResult()
    }

    /**
     * Connect to [device] via RFCOMM and transfer the identity JSON encrypted
     * with [code].
     *
     * Must be called from a coroutine.  The heavy work runs on [Dispatchers.IO].
     *
     * @param device      Bluetooth device representing the Windows PC.
     * @param code        6-digit pairing code shown on the PC.
     * @param identityJson  JSON string from [AdaCore.exportIdentityJson].
     */
    @SuppressLint("MissingPermission")
    suspend fun sendIdentityToPC(
        device: BluetoothDevice,
        code: String,
        identityJson: String,
    ): LinkResult = withContext(Dispatchers.IO) {
        if (code.length != 6 || !code.all { it.isDigit() }) {
            return@withContext LinkResult.Failure("Код должен состоять ровно из 6 цифр")
        }

        val key = deriveKey(code)
        val payload = encryptIdentity(identityJson, key)

        var socket: BluetoothSocket? = null
        try {
            socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
            Log.i(TAG, "Connecting to ${device.address}…")

            // Cancel discovery to speed up connection
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            btManager?.adapter?.cancelDiscovery()

            socket.connect()
            Log.i(TAG, "Connected to PC")

            writeFrame(socket.outputStream, code, payload)
            Log.i(TAG, "Identity sent successfully")

            LinkResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "sendIdentityToPC failed", e)
            LinkResult.Failure(e.localizedMessage ?: "Неизвестная ошибка")
        } finally {
            runCatching { socket?.close() }
        }
    }

    /**
     * Write the wire frame to [out]:
     *   [6 bytes]  ASCII code
     *   [4 bytes]  Big-endian payload length
     *   [N bytes]  version + nonce + ciphertext
     */
    private fun writeFrame(out: OutputStream, code: String, payload: ByteArray) {
        out.write(code.toByteArray(Charsets.US_ASCII))          // 6 bytes
        val len = payload.size
        out.write(byteArrayOf(
            (len shr 24).toByte(),
            (len shr 16).toByte(),
            (len shr 8).toByte(),
            len.toByte(),
        ))                                                       // 4 bytes
        out.write(payload)                                       // N bytes
        out.flush()
    }

    /**
     * Encrypt [identityJson] using AES-256-GCM.
     *
     * Payload layout:
     *   [1 byte]   ENC_VERSION
     *   [12 bytes] GCM nonce
     *   [N bytes]  ciphertext + 16-byte GCM tag
     */
    private fun encryptIdentity(identityJson: String, key: ByteArray): ByteArray {
        val nonce = ByteArray(GCM_NONCE_LEN).also { java.security.SecureRandom().nextBytes(it) }
        val secretKey = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance(AES_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
        val ciphertext = cipher.doFinal(identityJson.toByteArray(Charsets.UTF_8))

        return byteArrayOf(ENC_VERSION) + nonce + ciphertext
    }

    /**
     * Derive AES-256 key from the 6-digit pairing code using SHA-256.
     *
     * key = SHA-256("ada/bt-sync-key/v1\0" || code_ascii)
     */
    private fun deriveKey(code: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(KDF_PREFIX.toByteArray(Charsets.UTF_8))
        digest.update(code.toByteArray(Charsets.US_ASCII))
        return digest.digest()
    }

    // ── Device discovery helpers ────────────────────────────────────────────

    /**
     * Return the list of Bluetooth Classic devices already paired with this phone.
     * The user can pick one that represents their Windows PC.
     *
     * Requires [android.Manifest.permission.BLUETOOTH_CONNECT] on API 31+.
     */
    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter = btManager?.adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        return adapter.bondedDevices?.toList() ?: emptyList()
    }
}
