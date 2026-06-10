package com.ada.messenger.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "AppLockManager"

// v2: preferences now stored via EncryptedSharedPreferences (AES256-SIV keys, AES256-GCM values)
private const val PREFS_NAME           = "ada_app_lock_v2"
private const val KEY_PIN_ENABLED      = "pin_enabled"
private const val KEY_BIO_ENABLED      = "bio_enabled"
private const val KEY_PIN_SALT         = "pin_salt"        // 16-byte random salt, base64
private const val KEY_PIN_ENC_CELLS    = "pin_enc_cells"   // AES-GCM ciphertext of pattern cells, base64
private const val KEY_PIN_ENC_IV       = "pin_enc_iv"      // AES-GCM IV, base64
private const val KEY_BIO_ENC_CELLS    = "bio_enc_cells"   // ciphertext encrypted by Keystore key
private const val KEY_BIO_ENC_IV       = "bio_enc_iv"      // GCM IV
// Clean PIN — opens an empty "decoy" messenger UI
private const val KEY_CLEAN_PIN_ENABLED = "clean_pin_enabled"
private const val KEY_CLEAN_PIN_SALT    = "clean_pin_salt"
private const val KEY_CLEAN_PIN_HASH    = "clean_pin_hash"  // PBKDF2 verifier (no cells stored)
// Kill PIN — wipes all app data
private const val KEY_KILL_PIN_ENABLED  = "kill_pin_enabled"
private const val KEY_KILL_PIN_SALT     = "kill_pin_salt"
private const val KEY_KILL_PIN_HASH     = "kill_pin_hash"   // PBKDF2 verifier
// H2/H3: notification content visibility setting
private const val KEY_NOTIF_SHOW_CONTENT = "notif_show_content"
private const val KEY_ALLOW_SCREENSHOTS = "allow_screenshots"
// H4: configurable retention
private const val KEY_AUTO_WIPE_DAYS = "auto_wipe_days"
private const val KEY_CACHE_SIZE_MB = "cache_size_mb"
private const val KEY_ATTACHMENT_AGE_DAYS = "attachment_age_days"
private const val KEY_LAST_ACTIVE_MS = "last_active_ms"
// L1: timestamp of last successful PIN/pattern login — used for grace-period skip
private const val KEY_LAST_UNLOCK_MS = "last_unlock_ms"
// K1: persisted brute-force lockout state for pattern (visual) login
private const val KEY_PATTERN_FAILED_ATTEMPTS = "pat_failed_attempts"
private const val KEY_PATTERN_LOCKED_UNTIL_MS = "pat_locked_until_ms"
// C-NEW-1: persisted brute-force lockout state
private const val KEY_FAILED_ATTEMPTS  = "bf_failed_attempts"
private const val KEY_LOCKED_UNTIL_MS  = "bf_locked_until_ms"
private const val KEYSTORE_ALIAS       = "ada_bio_key_v1"
private const val ANDROID_KEYSTORE     = "AndroidKeyStore"
private const val AES_GCM_NOPAD        = "AES/GCM/NoPadding"
private const val PBKDF2_ALGO          = "PBKDF2WithHmacSHA256"
// H-2: NIST SP 800-132 (2024) recommends ≥ 600 000 PBKDF2-HMAC-SHA-256 iterations
private const val PBKDF2_ITERATIONS    = 600_000
private const val KEY_SIZE_BITS        = 256
private const val GCM_TAG_SIZE         = 128

/** Result of checking which PIN was entered. */
enum class PinCheckResult { REAL, CLEAN, KILL, NONE }

data class PinCheckWithCellsResult(
    val result: PinCheckResult,
    val realCells: ByteArray? = null,
)

/**
 * Manages app-level quick unlock via PIN and auxiliary Clean/Kill PIN flows.
 *
 * The pattern cells (the 32-byte auth secret) are stored only in the PIN path:
 *  - **PIN path**: AES-256-GCM with key derived from PIN via PBKDF2
 *
 * Legacy quick-unlock state from older installs is purged on startup.
 */
class AppLockManager(private val context: Context) {

    // M-2: store all PIN/lock metadata in EncryptedSharedPreferences (keys: AES256-SIV,
    // values: AES256-GCM) backed by an Android Keystore master key.
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    init {
        purgeLegacyQuickUnlockState()
    }

    fun registerPreferenceListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterPreferenceListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    // ── Status ──────────────────────────────────────────────────────────────

    val isPinEnabled: Boolean
        get() = prefs.getBoolean(KEY_PIN_ENABLED, false)

    // ── PIN ──────────────────────────────────────────────────────────────────

    /**
     * Enable PIN unlock. Derives a PBKDF2 key from the PIN and stores AES-GCM ciphertext of the
     * pattern cells. Stores a separate PIN hash for fast verification UI feedback.
     */
    fun enablePin(pin: String, patternCells: ByteArray) {
        require(pin.length >= 4) { "PIN too short" }

        // 1. Random salt for PBKDF2
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }

        // 2. Derive key
        val key = pbkdf2Key(pin, salt)

        // 3. Encrypt cells
        val cipher = Cipher.getInstance(AES_GCM_NOPAD)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val ciphertext = cipher.doFinal(patternCells)
        val iv = cipher.iv

        prefs.edit()
            .putBoolean(KEY_PIN_ENABLED, true)
            .putString(KEY_PIN_SALT, b64(salt))
            .remove("pin_hash")   // Proactively wipe legacy SHA-256 oracle from old installs
            .putString(KEY_PIN_ENC_CELLS, b64(ciphertext))
            .putString(KEY_PIN_ENC_IV, b64(iv))
            .apply()
        Log.i(TAG, "PIN enabled")
    }

    /** Disable PIN unlock and clear all stored PIN material. */
    fun disablePin() {
        prefs.edit()
            .putBoolean(KEY_PIN_ENABLED, false)
            .remove(KEY_PIN_SALT)
            .remove("pin_hash")   // Wipe legacy SHA-256 hash oracle if present
            .remove(KEY_PIN_ENC_CELLS)
            .remove(KEY_PIN_ENC_IV)
            .apply()
        Log.i(TAG, "PIN disabled")
    }

    /**
     * Verify PIN using PBKDF2 key-derivation + AES-GCM decryption.
     * Returns true if and only if [decryptCellsWithPin] succeeds (key derived correctly).
     * **Must be called on a background thread** — PBKDF2 takes ~300 ms.
     */
    fun verifyPinFast(pin: String): Boolean = decryptCellsWithPin(pin) != null

    /**
     * Decrypt pattern cells using the given PIN.
     * Returns null if PIN is wrong or data is corrupt.
     */
    fun decryptCellsWithPin(pin: String): ByteArray? = runCatching {
        val salt = unb64(prefs.getString(KEY_PIN_SALT, null) ?: return null)
        val iv   = unb64(prefs.getString(KEY_PIN_ENC_IV, null) ?: return null)
        val enc  = unb64(prefs.getString(KEY_PIN_ENC_CELLS, null) ?: return null)

        val key = pbkdf2Key(pin, salt)
        val cipher = Cipher.getInstance(AES_GCM_NOPAD)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_SIZE, iv))
        cipher.doFinal(enc)
    }.onFailure { Log.w(TAG, "decryptCellsWithPin failed: ${it.message}") }.getOrNull()

    fun clearLegacyQuickUnlockState() {
        purgeLegacyQuickUnlockState(logIfChanged = true)
    }

    private fun purgeLegacyQuickUnlockState(logIfChanged: Boolean = false) {
        val hadStoredState = prefs.getBoolean(KEY_BIO_ENABLED, false) ||
            prefs.contains(KEY_BIO_ENC_CELLS) ||
            prefs.contains(KEY_BIO_ENC_IV)

        prefs.edit()
            .putBoolean(KEY_BIO_ENABLED, false)
            .remove(KEY_BIO_ENC_CELLS)
            .remove(KEY_BIO_ENC_IV)
            .apply()

        val deletedKey = runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
            if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
                keyStore.deleteEntry(KEYSTORE_ALIAS)
                true
            } else {
                false
            }
        }.onFailure {
            Log.w(TAG, "purgeLegacyQuickUnlockState failed: ${it.message}")
        }.getOrDefault(false)

        if (logIfChanged && (hadStoredState || deletedKey)) {
            Log.i(TAG, "Legacy quick-unlock state purged")
        }
    }

    // ── Wipe all ─────────────────────────────────────────────────────────────

    fun clearAll() {
        disablePin()
        clearLegacyQuickUnlockState()
        disableCleanPin()
        disableKillPin()
    }

    // ── Notification content visibility ───────────────────────────────────

    /** Whether to show sender name and message body in notifications (default: true). */
    var notificationShowContent: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_SHOW_CONTENT, true)
        set(value) { prefs.edit().putBoolean(KEY_NOTIF_SHOW_CONTENT, value).apply() }

    var allowScreenshots: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_SCREENSHOTS, false)
        set(value) { prefs.edit().putBoolean(KEY_ALLOW_SCREENSHOTS, value).apply() }
        
    var autoWipeDays: Int
        get() = prefs.getInt(KEY_AUTO_WIPE_DAYS, 0)
        set(value) { prefs.edit().putInt(KEY_AUTO_WIPE_DAYS, value.coerceAtLeast(0)).apply() }
        
    var cacheSizeMb: Int
        get() = prefs.getInt(KEY_CACHE_SIZE_MB, 500)
        set(value) { prefs.edit().putInt(KEY_CACHE_SIZE_MB, value).apply() }

    var attachmentAgeDays: Int
        get() = prefs.getInt(KEY_ATTACHMENT_AGE_DAYS, 30)
        set(value) { prefs.edit().putInt(KEY_ATTACHMENT_AGE_DAYS, value).apply() }

    var lastActiveMs: Long
        get() = prefs.getLong(KEY_LAST_ACTIVE_MS, System.currentTimeMillis())
        set(value) { prefs.edit().putLong(KEY_LAST_ACTIVE_MS, value).apply() }

    /** Epoch millis of the last successful PIN or pattern unlock. Used to skip auth within a grace period. */
    var lastUnlockMs: Long
        get() = prefs.getLong(KEY_LAST_UNLOCK_MS, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_UNLOCK_MS, value).apply() }

    // ── Brute-force lockout (C-NEW-1) ─────────────────────────────────────

    /** Persisted failed attempt counter — survives process death and screen rotation. */
    var failedPinAttempts: Int
        get() = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
        set(value) { prefs.edit().putInt(KEY_FAILED_ATTEMPTS, value).apply() }

    /** Epoch millis until which PIN entry is locked. 0 = not locked. */
    var pinLockedUntilMs: Long
        get() = prefs.getLong(KEY_LOCKED_UNTIL_MS, 0L)
        set(value) { prefs.edit().putLong(KEY_LOCKED_UNTIL_MS, value).apply() }

    /** Record a failed PIN attempt and apply exponential back-off if threshold is reached.
     *  Returns the lockout duration in seconds (0 if not yet locked). */
    fun recordFailedPinAttempt(): Long {
        val attempts = failedPinAttempts + 1
        failedPinAttempts = attempts
        return if (attempts >= 3) {
            val delaySec = minOf(30L shl (attempts - 3), 3600L)
            pinLockedUntilMs = System.currentTimeMillis() + delaySec * 1000L
            delaySec
        } else 0L
    }

    /** Reset failed attempts and lockout after a successful unlock. */
    fun resetFailedPinAttempts() {
        prefs.edit()
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKED_UNTIL_MS, 0L)
            .apply()
    }

    // ── Pattern brute-force lockout (K1) ──────────────────────────────────

    /** Persisted failed pattern attempt counter — survives process death / Force Stop. */
    var failedPatternAttempts: Int
        get() = prefs.getInt(KEY_PATTERN_FAILED_ATTEMPTS, 0)
        private set(value) { prefs.edit().putInt(KEY_PATTERN_FAILED_ATTEMPTS, value).apply() }

    /** Epoch millis until which pattern entry is locked. 0 = not locked. */
    var patternLockedUntilMs: Long
        get() = prefs.getLong(KEY_PATTERN_LOCKED_UNTIL_MS, 0L)
        private set(value) { prefs.edit().putLong(KEY_PATTERN_LOCKED_UNTIL_MS, value).apply() }

    /** Record a failed pattern attempt. Returns lockout duration in seconds (0 = not yet locked). */
    fun recordFailedPatternAttempt(): Long {
        val attempts = failedPatternAttempts + 1
        failedPatternAttempts = attempts
        return if (attempts >= 3) {
            val delaySec = minOf(30L shl (attempts - 3), 3600L)  // same as PIN lockout
            patternLockedUntilMs = System.currentTimeMillis() + delaySec * 1000L
            delaySec
        } else 0L
    }

    /** Reset pattern lockout after successful login. */
    fun resetFailedPatternAttempts() {
        prefs.edit()
            .putInt(KEY_PATTERN_FAILED_ATTEMPTS, 0)
            .putLong(KEY_PATTERN_LOCKED_UNTIL_MS, 0L)
            .apply()
    }

    // ── Clean PIN (decoy mode) ─────────────────────────────────────────────

    val isCleanPinEnabled: Boolean
        get() = prefs.getBoolean(KEY_CLEAN_PIN_ENABLED, false)

    /**
     * Set a Clean PIN. On entry it shows a blank messenger without real messages.
     * [pin] must be 4 digits and must not match the real PIN or Kill PIN.
     */
    fun enableCleanPin(pin: String): Boolean {
        requireValidExtraPin(pin, isKill = false)

        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val hash = pbkdf2Key(pin, salt)
        prefs.edit()
            .putBoolean(KEY_CLEAN_PIN_ENABLED, true)
            .putString(KEY_CLEAN_PIN_SALT, b64(salt))
            .putString(KEY_CLEAN_PIN_HASH, b64(hash))
            .apply()
        Log.i(TAG, "Clean PIN enabled")
        return true
    }

    fun disableCleanPin() {
        prefs.edit()
            .putBoolean(KEY_CLEAN_PIN_ENABLED, false)
            .remove(KEY_CLEAN_PIN_SALT)
            .remove(KEY_CLEAN_PIN_HASH)
            .apply()
    }

    /** Returns true if [pin] matches the clean PIN. Must be called on a background thread. */
    fun verifyCleanPin(pin: String): Boolean {
        val salt = unb64(prefs.getString(KEY_CLEAN_PIN_SALT, null) ?: return false)
        val stored = prefs.getString(KEY_CLEAN_PIN_HASH, null) ?: return false
        val derived = pbkdf2Key(pin, salt)
        return java.security.MessageDigest.isEqual(derived, unb64(stored))
    }

    // ── Kill PIN (wipe data) ─────────────────────────────────────────────────

    val isKillPinEnabled: Boolean
        get() = prefs.getBoolean(KEY_KILL_PIN_ENABLED, false)

    /**
     * Set a Kill PIN. On entry all app data is wiped irreversibly.
     * [pin] must be 4 digits and must not match the real PIN or Clean PIN.
     */
    fun enableKillPin(pin: String): Boolean {
        requireValidExtraPin(pin, isKill = true)

        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val hash = pbkdf2Key(pin, salt)
        prefs.edit()
            .putBoolean(KEY_KILL_PIN_ENABLED, true)
            .putString(KEY_KILL_PIN_SALT, b64(salt))
            .putString(KEY_KILL_PIN_HASH, b64(hash))
            .apply()
        Log.i(TAG, "Kill PIN enabled")
        return true
    }

    fun disableKillPin() {
        prefs.edit()
            .putBoolean(KEY_KILL_PIN_ENABLED, false)
            .remove(KEY_KILL_PIN_SALT)
            .remove(KEY_KILL_PIN_HASH)
            .apply()
    }

    /** Returns true if [pin] matches the kill PIN. Must be called on a background thread. */
    fun verifyKillPin(pin: String): Boolean {
        val salt = unb64(prefs.getString(KEY_KILL_PIN_SALT, null) ?: return false)
        val stored = prefs.getString(KEY_KILL_PIN_HASH, null) ?: return false
        val derived = pbkdf2Key(pin, salt)
        return java.security.MessageDigest.isEqual(derived, unb64(stored))
    }

    // ── Unified PIN check ─────────────────────────────────────────────────

    /**
     * Check which role [pin] fulfils. Call on a background thread (PBKDF2 is slow).
     * Order: REAL → CLEAN → KILL → NONE.
     * If REAL: [decryptCellsWithPin] also succeeds so call it to get the cells.
     */
    fun checkPin(pin: String): PinCheckResult {
        if (isPinEnabled && decryptCellsWithPin(pin) != null) return PinCheckResult.REAL
        if (isCleanPinEnabled && verifyCleanPin(pin)) return PinCheckResult.CLEAN
        if (isKillPinEnabled && verifyKillPin(pin)) return PinCheckResult.KILL
        return PinCheckResult.NONE
    }

    /**
     * Single-pass PIN check used by quick unlock screen.
     * Avoids decrypting REAL PIN cells twice.
     */
    fun checkPinWithCells(pin: String): PinCheckWithCellsResult {
        if (isPinEnabled) {
            val cells = decryptCellsWithPin(pin)
            if (cells != null) return PinCheckWithCellsResult(PinCheckResult.REAL, cells)
        }
        if (isCleanPinEnabled && verifyCleanPin(pin)) return PinCheckWithCellsResult(PinCheckResult.CLEAN)
        if (isKillPinEnabled && verifyKillPin(pin)) return PinCheckWithCellsResult(PinCheckResult.KILL)
        return PinCheckWithCellsResult(PinCheckResult.NONE)
    }


    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun requireValidExtraPin(pin: String, isKill: Boolean) {
        val roleLabel = if (isKill) "Kill PIN" else "Clean PIN"
        require(pin.length == 4 && pin.all(Char::isDigit)) {
            "$roleLabel должен состоять ровно из 4 цифр"
        }
        require(!verifyPinFast(pin)) {
            "$roleLabel не должен совпадать с основным PIN"
        }
        if (isKill) {
            require(!isCleanPinEnabled || !verifyCleanPin(pin)) {
                "$roleLabel не должен совпадать с Clean PIN"
            }
        } else {
            require(!isKillPinEnabled || !verifyKillPin(pin)) {
                "$roleLabel не должен совпадать с Kill PIN"
            }
        }
    }

    private fun pbkdf2Key(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_SIZE_BITS)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGO)
        return factory.generateSecret(spec).encoded
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}
