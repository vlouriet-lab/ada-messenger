package com.ada.messenger.desktop.core

import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private const val PIN_PBKDF2_ITERATIONS = 600_000
private const val PIN_KEY_BITS = 256
private const val PIN_GCM_TAG_BITS = 128
private const val PIN_UNLOCK_VERSION = 1

data class StoredIdentityMeta(
    val displayName: String,
    val peerId: String,
    val connectionProfile: String = DEFAULT_DESKTOP_CONNECTION_PROFILE,
)

class DesktopIdentityStore private constructor(private val baseDir: Path) {
    private val metaPath = baseDir.resolve("identity.json")
    private val pinUnlockPath = baseDir.resolve("pin_unlock.json")
    val coreDataDir: Path = baseDir.resolve("core")

    init {
        Files.createDirectories(baseDir)
        Files.createDirectories(coreDataDir)
    }

    fun hasStoredIdentity(): Boolean = Files.isRegularFile(metaPath)

    fun loadIdentityMeta(): StoredIdentityMeta? {
        if (!hasStoredIdentity()) return null
        return runCatching {
            val json = JSONObject(Files.readString(metaPath))
            val displayName = json.optString("displayName", "").trim()
            val peerId = json.optString("peerId", "").trim()
            val connectionProfile = normalizeConnectionProfile(
                json.optString("connectionProfile", DEFAULT_DESKTOP_CONNECTION_PROFILE),
            )
            if (displayName.isBlank() || peerId.isBlank()) {
                null
            } else {
                StoredIdentityMeta(
                    displayName = displayName,
                    peerId = peerId,
                    connectionProfile = connectionProfile,
                )
            }
        }.getOrNull()
    }

    fun saveIdentityMeta(
        peerId: String,
        displayName: String,
        connectionProfile: String = DEFAULT_DESKTOP_CONNECTION_PROFILE,
    ) {
        Files.createDirectories(baseDir)
        val json = JSONObject()
            .put("displayName", displayName)
            .put("peerId", peerId)
            .put("connectionProfile", normalizeConnectionProfile(connectionProfile))
        Files.writeString(metaPath, json.toString(2))
    }

    fun updateConnectionProfile(connectionProfile: String) {
        val current = loadIdentityMeta() ?: return
        saveIdentityMeta(
            peerId = current.peerId,
            displayName = current.displayName,
            connectionProfile = connectionProfile,
        )
    }

    fun isPinEnabled(): Boolean = Files.isRegularFile(pinUnlockPath)

    fun enablePin(pin: String, patternCells: ByteArray) {
        require(pin.length == 4 && pin.all(Char::isDigit)) { "PIN должен быть ровно из 4 цифр" }
        require(patternCells.size == 32) { "expected 32 pattern bytes" }

        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val keyBytes = derivePinKey(pin, salt)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
            val ciphertext = cipher.doFinal(patternCells)
            val json = JSONObject()
                .put("version", PIN_UNLOCK_VERSION)
                .put("salt", Base64.getEncoder().encodeToString(salt))
                .put("iv", Base64.getEncoder().encodeToString(cipher.iv))
                .put("ciphertext", Base64.getEncoder().encodeToString(ciphertext))

            Files.createDirectories(baseDir)
            Files.writeString(pinUnlockPath, json.toString(2))
        } finally {
            keyBytes.fill(0)
        }
    }

    fun disablePin() {
        Files.deleteIfExists(pinUnlockPath)
    }

    /** Removes the identity metadata and PIN unlock file. Does not touch the core data directory. */
    fun deleteAll() {
        Files.deleteIfExists(metaPath)
        Files.deleteIfExists(pinUnlockPath)
    }

    fun decryptCellsWithPin(pin: String): ByteArray? {
        if (!isPinEnabled()) return null
        return runCatching {
            val json = JSONObject(Files.readString(pinUnlockPath))
            if (json.optInt("version", 0) != PIN_UNLOCK_VERSION) {
                error("Unsupported PIN unlock payload version")
            }

            val salt = Base64.getDecoder().decode(json.getString("salt"))
            val iv = Base64.getDecoder().decode(json.getString("iv"))
            val ciphertext = Base64.getDecoder().decode(json.getString("ciphertext"))
            val keyBytes = derivePinKey(pin, salt)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(keyBytes, "AES"),
                    GCMParameterSpec(PIN_GCM_TAG_BITS, iv),
                )
                cipher.doFinal(ciphertext)
            } finally {
                keyBytes.fill(0)
            }
        }.getOrNull()
    }

    private fun derivePinKey(pin: String, salt: ByteArray): ByteArray {
        val pinChars = pin.toCharArray()
        val spec = PBEKeySpec(pinChars, salt, PIN_PBKDF2_ITERATIONS, PIN_KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } finally {
            pinChars.fill('\u0000')
            spec.clearPassword()
        }
    }

    companion object {
        internal fun defaultBaseDir(): Path {
            val appData = System.getenv("APPDATA")
                ?.takeIf { it.isNotBlank() }
                ?.let(Paths::get)
            return appData?.resolve("ADA Messenger")?.resolve("desktop")
                ?: Paths.get(System.getProperty("user.home", "."), ".ada-messenger", "desktop")
        }

        fun default(): DesktopIdentityStore {
            return DesktopIdentityStore(defaultBaseDir())
        }
    }
}