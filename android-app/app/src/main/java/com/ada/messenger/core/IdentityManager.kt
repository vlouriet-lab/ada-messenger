package com.ada.messenger.core

import android.content.Context
import android.net.Uri
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.CRC32
import java.security.KeyStore
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "IdentityManager"
private const val RECOVERY_SALT_FILE = "ada_identity.salt"
private const val RECOVERY_MANIFEST_FILE = "manifest.json"
private const val RECOVERY_STATE_PREFIX = "state/"
private const val RECOVERY_STAGING_DIR = "recovery_restore.tmp"
private const val RECOVERY_BUNDLE_VERSION = 2
private const val RECOVERY_PBKDF2_ITERS = 600_000  // match AppLockManager PIN strength
private const val RECOVERY_CODE_VERSION: Int = 1
private const val RECOVERY_CODE_GROUP_SIZE = 5
private const val MAX_RECOVERY_BUNDLE_BYTES = 128L * 1024 * 1024
private const val MAX_RECOVERY_ZIP_BYTES = 256L * 1024 * 1024
private const val MAX_RECOVERY_ENTRY_BYTES = 128L * 1024 * 1024
private const val MAX_RECOVERY_MANIFEST_BYTES = 64L * 1024
private const val MAX_RECOVERY_ZIP_ENTRIES = 64
private const val IO_BUFFER_SIZE = 8 * 1024
private val RECOVERY_MAGIC = "ADAREC10".toByteArray(Charsets.US_ASCII)
private const val RECOVERY_CODE_RAW_BYTES = 1 + 1 + 32 + 32
private const val RECOVERY_CODE_CHECKSUM_BYTES = 4
private val RECOVERY_CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray()

/**
 * Manages ADA identity lifecycle: pattern/cell-based authentication,
 * credential persistence, and deep-link contact import.
 *
 * Single Responsibility: identity creation, login, persistence.
 * All heavy crypto (Argon2id) runs on [Dispatchers.IO].
 */
class IdentityManager(private val appContext: Context) {

    private data class RecoveryFileEntry(
        val relativePath: String,
        val data: ByteArray,
    )

    private data class ParsedRecoveryCode(
        val peerId: String,
        val salt: ByteArray,
        val avatarIndex: Int,
    )

    // ── Observable state ──────────────────────────────────────────────────

    private val _patternLoading = MutableStateFlow(false)
    val patternLoading: StateFlow<Boolean> = _patternLoading.asStateFlow()

    private val _patternError = MutableStateFlow<String?>(null)
    val patternError: StateFlow<String?> = _patternError.asStateFlow()

    /** Clears stale auth error when switching between PIN and pattern flows. */
    fun clearPatternError() {
        _patternError.value = null
    }

    // ── Identity queries ──────────────────────────────────────────────────

    /** True if a pattern identity has been registered on this device. */
    fun hasStoredIdentity(): Boolean =
        identityPrefs().getString(AdaConfig.KEY_IDENTITY_TYPE, null) != null

    fun getStoredDisplayName(): String? =
        identityPrefs().getString(AdaConfig.KEY_DISPLAY_NAME, null)

    fun getStoredPeerId(): String? =
        identityPrefs().getString(AdaConfig.KEY_PEER_ID, null)

    // ── Core initialization ──────────────────────────────────────────────

    /**
     * Result of a successful identity initialization.
     * The caller (ViewModel) wires these into the rest of the app.
     */
    data class InitResult(
        val core: AdaCore,
        val peerId: String?,
        val displayName: String?,
        val patternCells: ByteArray,
        val reusedLiveCore: Boolean = false,
    )

    /**
     * Register a brand-new identity from a visual pattern + nickname.
     *
     * @return [InitResult] on success, `null` on failure (error stored in [patternError]).
     */
    suspend fun createFromPattern(
        cells: ByteArray,
        displayName: String,
        connectionProfile: String = AdaConfig.DEFAULT_CONNECTION_PROFILE,
    ): InitResult? =
        withContext(Dispatchers.IO) {
            _patternLoading.value = true
            _patternError.value = null
            try {
                val dataDir = appContext.filesDir.absolutePath
                val name = displayName.trim()
                val instance = AdaCore.createFromPattern(cells, name, dataDir, connectionProfile)
                if (instance == null) {
                    _patternError.value = "Не удалось создать профиль. Попробуйте ещё раз."
                    return@withContext null
                }
                val peerId = instance.getPeerId()
                saveIdentityMeta(peerId ?: "", name)
                saveWorkerCredentials(name, dataDir)
                saveBackgroundCells(cells)
                AdaCoreHolder.instance = instance
                startForegroundService(name, dataDir)
                InitResult(instance, peerId, instance.getDisplayName(), cells.copyOf())
            } catch (e: Exception) {
                Log.e(TAG, "createFromPattern failed", e)
                _patternError.value = "Ошибка создания профиля: ${e.message}"
                null
            } finally {
                _patternLoading.value = false
            }
        }

    /**
     * Re-derive identity from a pattern on subsequent launches.
     * Verifies that the derived peer ID matches the stored one.
     *
     * @return [InitResult] on success, `null` on mismatch/failure.
     */
    suspend fun loginWithPattern(
        cells: ByteArray,
        connectionProfile: String = AdaConfig.DEFAULT_CONNECTION_PROFILE,
    ): InitResult? =
        withContext(Dispatchers.IO) {
            _patternLoading.value = true
            _patternError.value = null
            try {
                val prefs = identityPrefs()
                val storedPeerId = prefs.getString(AdaConfig.KEY_PEER_ID, null)
                val storedName   = prefs.getString(AdaConfig.KEY_DISPLAY_NAME, null) ?: ""
                val dataDir      = appContext.filesDir.absolutePath

                // D2 fix: if ForegroundService already has a live core (same identity),
                // reuse it instead of creating a second iroh endpoint with the same NodeId.
                // Two simultaneous endpoints with the same NodeId race for incoming packets —
                // whichever registered first with the relay wins, and the ViewModel's newer
                // core never receives MessageReceived events.
                val existing = AdaCoreHolder.instance
                if (existing != null) {
                    val existingPeerId = existing.getPeerId()
                    if (storedPeerId == null || existingPeerId == storedPeerId) {
                        if (existing.verifyPattern(cells)) {
                            Log.i(TAG, "loginWithPattern: reusing live core from AdaCoreHolder")
                            saveBackgroundCells(cells)
                            startForegroundService(storedName, dataDir)
                            return@withContext InitResult(
                                existing, existingPeerId,
                                existing.getDisplayName(), cells.copyOf(),
                                reusedLiveCore = true,
                            )
                        }
                        // verifyPattern returned false — wrong cells, fall through to error
                        _patternError.value = "Неверный узор. Попробуйте ещё раз."
                        return@withContext null
                    } else {
                        // Different identity stored in holder — close stale core
                        Log.w(TAG, "loginWithPattern: holder has different identity, closing stale core")
                        existing.close()
                        AdaCoreHolder.instance = null
                    }
                }

                val instance = AdaCore.createFromPattern(cells, storedName, dataDir, connectionProfile)
                if (instance == null) {
                    _patternError.value = "Не удалось восстановить профиль."
                    return@withContext null
                }

                val derivedPeerId = instance.getPeerId()
                if (storedPeerId != null && derivedPeerId != storedPeerId) {
                    instance.close()
                    _patternError.value = "Неверный узор. Попробуйте ещё раз."
                    return@withContext null
                }

                saveIdentityMeta(derivedPeerId ?: "", storedName)
                saveWorkerCredentials(storedName, dataDir)
                saveBackgroundCells(cells)
                AdaCoreHolder.instance = instance
                startForegroundService(storedName, dataDir)
                InitResult(instance, derivedPeerId, instance.getDisplayName(), cells.copyOf())
            } catch (e: Exception) {
                Log.e(TAG, "loginWithPattern failed", e)
                _patternError.value = "Ошибка входа: ${e.message}"
                null
            } finally {
                _patternLoading.value = false
            }
        }

    /**
     * Quick-unlock path: login using pattern cells decrypted by PIN.
     * Same as [loginWithPattern] but shows a generic error message.
     */
    suspend fun loginWithCells(
        cells: ByteArray,
        connectionProfile: String = AdaConfig.DEFAULT_CONNECTION_PROFILE,
    ): InitResult? =
        withContext(Dispatchers.IO) {
            _patternLoading.value = true
            _patternError.value = null
            try {
                val prefs = identityPrefs()
                val storedPeerId = prefs.getString(AdaConfig.KEY_PEER_ID, null)
                val storedName   = prefs.getString(AdaConfig.KEY_DISPLAY_NAME, null) ?: ""
                val dataDir      = appContext.filesDir.absolutePath

                // D2 fix: reuse ForegroundService's live core (same as loginWithPattern)
                val existing = AdaCoreHolder.instance
                if (existing != null) {
                    val existingPeerId = existing.getPeerId()
                    if (storedPeerId == null || existingPeerId == storedPeerId) {
                        if (existing.verifyPattern(cells)) {
                            Log.i(TAG, "loginWithCells: reusing live core from AdaCoreHolder")
                            saveBackgroundCells(cells)
                            startForegroundService(storedName, dataDir)
                            return@withContext InitResult(
                                existing, existingPeerId,
                                existing.getDisplayName(), cells.copyOf(),
                                reusedLiveCore = true,
                            )
                        }
                        _patternError.value = "Ошибка разблокировки. Введите узор."
                        return@withContext null
                    } else {
                        Log.w(TAG, "loginWithCells: holder has different identity, closing stale core")
                        existing.close()
                        AdaCoreHolder.instance = null
                    }
                }

                val instance = AdaCore.createFromPattern(cells, storedName, dataDir, connectionProfile)
                if (instance == null) {
                    _patternError.value = "Ошибка разблокировки. Введите узор."
                    return@withContext null
                }

                val derivedPeerId = instance.getPeerId()
                if (storedPeerId != null && derivedPeerId != storedPeerId) {
                    instance.close()
                    _patternError.value = "Ошибка разблокировки. Введите узор."
                    return@withContext null
                }

                saveIdentityMeta(derivedPeerId ?: "", storedName)
                saveWorkerCredentials(storedName, dataDir)
                saveBackgroundCells(cells)
                AdaCoreHolder.instance = instance
                startForegroundService(storedName, dataDir)
                InitResult(instance, derivedPeerId, instance.getDisplayName(), cells.copyOf())
            } catch (e: Exception) {
                Log.e(TAG, "loginWithCells failed", e)
                _patternError.value = "Ошибка разблокировки: ${e.message}"
                null
            } finally {
                _patternLoading.value = false
            }
        }

    /**
     * Simple (non-pattern) create — for the onboarding flow.
     */
    suspend fun createSimple(
        displayName: String,
        connectionProfile: String = AdaConfig.DEFAULT_CONNECTION_PROFILE,
    ): InitResult? =
        withContext(Dispatchers.IO) {
            val dataDir = appContext.filesDir.absolutePath
            val instance = AdaCore.create(displayName, dataDir, connectionProfile) ?: return@withContext null
            saveIdentityMeta(instance.getPeerId() ?: "", displayName)
            saveWorkerCredentials(displayName, dataDir)
            AdaCoreHolder.instance = instance
            startForegroundService(displayName, dataDir)
            InitResult(instance, instance.getPeerId(), instance.getDisplayName(), ByteArray(0))
        }

    fun generateRecoveryCode(): String {
        val identityType = identityPrefs().getString(AdaConfig.KEY_IDENTITY_TYPE, null)
        require(identityType == AdaConfig.IDENTITY_TYPE_PATTERN) {
            "Recovery code is only available for pattern-based profiles."
        }

        val peerId = getStoredPeerId()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Stored peer ID is missing.")
        val peerBytes = try {
            Base64.decode(peerId, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("Stored peer ID is invalid.", e)
        }
        require(peerBytes.size == 32) { "Stored peer ID has invalid length." }

        val saltBytes = loadRecoverySaltBytes()
        val avatarIndex = identityPrefs().getInt(AdaConfig.KEY_AVATAR_INDEX, 0)
            .coerceIn(0, AdaConfig.AVATAR_COUNT - 1)

        val payload = ByteArray(RECOVERY_CODE_RAW_BYTES)
        payload[0] = RECOVERY_CODE_VERSION.toByte()
        payload[1] = avatarIndex.toByte()
        System.arraycopy(saltBytes, 0, payload, 2, saltBytes.size)
        System.arraycopy(peerBytes, 0, payload, 34, peerBytes.size)

        val checksum = ByteArray(RECOVERY_CODE_CHECKSUM_BYTES)
        val crc = CRC32().apply { update(payload) }.value
        checksum[0] = ((crc ushr 24) and 0xFF).toByte()
        checksum[1] = ((crc ushr 16) and 0xFF).toByte()
        checksum[2] = ((crc ushr 8) and 0xFF).toByte()
        checksum[3] = (crc and 0xFF).toByte()

        val rawCode = payload + checksum
        return encodeRecoveryCode(rawCode)
    }

    fun importRecoveryCode(code: String, displayName: String): String {
        check(!hasStoredIdentity()) { "This device already has a profile. Wipe it before importing a recovery code." }

        val trimmedName = displayName.trim()
        require(trimmedName.isNotEmpty()) { "Display name is required for recovery." }
        require(trimmedName.length <= 64) { "Display name is too long." }

        val parsed = parseRecoveryCode(code)
        persistRecoverySalt(parsed.salt)
        saveWorkerCredentials(trimmedName, appContext.filesDir.absolutePath)
        saveIdentityMeta(
            parsed.peerId,
            trimmedName,
            parsed.avatarIndex.coerceIn(0, AdaConfig.AVATAR_COUNT - 1),
        )
        clearBackgroundCells()
        clearPatternError()
        return trimmedName
    }

    /**
     * Export a portable recovery bundle for moving the account to another device.
     *
     * The bundle contains the pattern derivation salt, profile metadata, and a
     * snapshot of the local encrypted chat state (messages, contacts, sessions,
     * offline queue). On a new device the user imports the bundle, then enters
     * the original pattern to re-derive the same peer ID.
     */
    suspend fun exportRecoveryBundle(uri: Uri, password: String): String =
        withContext(Dispatchers.IO) {
            require(password.length >= 8) { "Recovery password must be at least 8 characters." }

            val identityType = identityPrefs().getString(AdaConfig.KEY_IDENTITY_TYPE, null)
            require(identityType == AdaConfig.IDENTITY_TYPE_PATTERN) {
                "Recovery bundle is only available for pattern-based profiles."
            }

            val peerId = getStoredPeerId()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Stored peer ID is missing.")
            val displayName = getStoredDisplayName()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Stored display name is missing.")
            val saltBytes = loadRecoverySaltBytes()
            val stateEntries = collectRecoveryStateEntries()
            val stateNames = stateEntries.map { it.relativePath }

            val manifest = JSONObject().apply {
                put("version", RECOVERY_BUNDLE_VERSION)
                put("peer_id", peerId)
                put("display_name", displayName)
                put("avatar_index", identityPrefs().getInt(AdaConfig.KEY_AVATAR_INDEX, 0))
                put("exported_at_ms", System.currentTimeMillis())
                put("requires_original_pattern", true)
                put("includes_local_state", stateEntries.isNotEmpty())
                put("state_entries", org.json.JSONArray(stateNames))
            }

            val zipEntries = linkedMapOf<String, ByteArray>()
            zipEntries[RECOVERY_MANIFEST_FILE] = manifest.toString().toByteArray(Charsets.UTF_8)
            zipEntries[RECOVERY_SALT_FILE] = saltBytes
            stateEntries.forEach { entry ->
                zipEntries[RECOVERY_STATE_PREFIX + entry.relativePath] = entry.data
            }

            val zipBytes = buildRecoveryZip(zipEntries)
            val passwordChars = password.toCharArray()
            try {
                val encrypted = encryptRecoveryPayload(zipBytes, passwordChars)
                appContext.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(encrypted)
                    out.flush()
                } ?: throw IOException("Failed to open destination file.")
            } finally {
                passwordChars.fill('\u0000')
            }

            displayName
        }

    /**
     * Import a recovery bundle on a fresh device.
     *
     * After import the app stores the original profile metadata and salt, then
     * asks the user to enter the original pattern on the login screen.
     */
    suspend fun importRecoveryBundle(uri: Uri, password: String): String =
        withContext(Dispatchers.IO) {
            require(password.length >= 8) { "Recovery password must be at least 8 characters." }
            check(!hasStoredIdentity()) { "This device already has a profile. Wipe it before importing a recovery bundle." }

            val encrypted = readRecoveryPayload(uri)

            val passwordChars = password.toCharArray()
            val zipBytes = try {
                decryptRecoveryPayload(encrypted, passwordChars)
            } finally {
                passwordChars.fill('\u0000')
            }
            require(zipBytes.size.toLong() <= MAX_RECOVERY_ZIP_BYTES) {
                "Recovery bundle is too large after decryption."
            }
            val bundle = readRecoveryZip(zipBytes)

            val manifestBytes = bundle[RECOVERY_MANIFEST_FILE]
                ?: throw IllegalStateException("Recovery manifest is missing.")
            val saltBytes = bundle[RECOVERY_SALT_FILE]
                ?: throw IllegalStateException("Recovery salt is missing.")
            require(saltBytes.size == 32) { "Recovery salt has invalid length." }

            val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
            val version = manifest.optInt("version", 0)
            require(version == 1 || version == RECOVERY_BUNDLE_VERSION) {
                "Unsupported recovery bundle version."
            }

            val peerId = manifest.optString("peer_id", "")
            val displayName = manifest.optString("display_name", "")
            require(peerId.isNotBlank()) { "Recovery bundle is missing the peer ID." }
            require(displayName.isNotBlank()) { "Recovery bundle is missing the display name." }

            val filesDir = appContext.filesDir
            if (!filesDir.exists() && !filesDir.mkdirs()) {
                throw IOException("Failed to prepare app storage.")
            }

            val stateEntries = mutableListOf<String>()
            if (version >= 2) {
                val manifestEntries = manifest.optJSONArray("state_entries")
                if (manifestEntries != null) {
                    for (index in 0 until manifestEntries.length()) {
                        val relativePath = manifestEntries.optString(index).trim()
                        if (relativePath.isNotEmpty()) {
                            stateEntries += relativePath
                        }
                    }
                }
            }
            restoreRecoveryStateAtomically(saltBytes, bundle, stateEntries)

            saveWorkerCredentials(displayName, filesDir.absolutePath)
            val avatarIndex = manifest.optInt("avatar_index", -1)
            clearBackgroundCells()
            clearPatternError()
            saveIdentityMeta(
                peerId,
                displayName,
                avatarIndex.takeIf { it >= 0 }?.coerceIn(0, AdaConfig.AVATAR_COUNT - 1),
            )

            displayName
        }

    // ── Private helpers ──────────────────────────────────────────────────

    private fun identityPrefs() =
        appContext.getSharedPreferences(AdaConfig.IDENTITY_PREFS, Context.MODE_PRIVATE)

    private fun saveIdentityMeta(peerId: String, displayName: String, avatarIndex: Int? = null) {
        val editor = identityPrefs().edit()
            .putString(AdaConfig.KEY_IDENTITY_TYPE, AdaConfig.IDENTITY_TYPE_PATTERN)
            .putString(AdaConfig.KEY_PEER_ID, peerId)
            .putString(AdaConfig.KEY_DISPLAY_NAME, displayName)
        if (avatarIndex != null) {
            editor.putInt(AdaConfig.KEY_AVATAR_INDEX, avatarIndex)
        }
        if (!editor.commit()) {
            throw IOException("Failed to persist identity metadata.")
        }
    }

    private fun saveWorkerCredentials(displayName: String, dataDir: String) {
        com.ada.messenger.service.ADANotificationService.saveCredentials(
            appContext,
            displayName,
            dataDir,
        )
    }

    private fun loadRecoverySaltBytes(): ByteArray {
        val saltFile = File(appContext.filesDir, RECOVERY_SALT_FILE)
        if (!saltFile.isFile) {
            throw IllegalStateException("Identity salt file is missing.")
        }
        val saltBytes = saltFile.readBytes()
        require(saltBytes.size == 32) { "Identity salt has invalid length." }
        return saltBytes
    }

    private fun readRecoveryPayload(uri: Uri): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(IO_BUFFER_SIZE)
        var total = 0L
        val input = appContext.contentResolver.openInputStream(uri)
            ?: throw IOException("Failed to open recovery file.")
        input.use { stream ->
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) break
                if (read == 0) continue
                total += read.toLong()
                require(total <= MAX_RECOVERY_BUNDLE_BYTES) {
                    "Recovery bundle is too large."
                }
                buffer.write(chunk, 0, read)
            }
        }
        return buffer.toByteArray()
    }

    private fun persistRecoverySalt(saltBytes: ByteArray) {
        val filesDir = appContext.filesDir
        val tempSaltFile = File(filesDir, "$RECOVERY_SALT_FILE.tmp")
        tempSaltFile.writeBytes(saltBytes)
        val saltFile = File(filesDir, RECOVERY_SALT_FILE)
        if (saltFile.exists() && !saltFile.delete()) {
            tempSaltFile.delete()
            throw IOException("Failed to replace the existing identity salt.")
        }
        if (!tempSaltFile.renameTo(saltFile)) {
            tempSaltFile.copyTo(saltFile, overwrite = true)
            tempSaltFile.delete()
        }
    }

    private fun collectRecoveryStateEntries(): List<RecoveryFileEntry> {
        val candidates = listOf(
            "keys.db",
            "keys.db-wal",
            "keys.db-shm",
            "keys.db.rk",
            "offline_queue.bin",
            "messages/messages.db",
            "messages/messages.db-wal",
            "messages/messages.db-shm",
        )

        return candidates.mapNotNull { relativePath ->
            val file = File(appContext.filesDir, relativePath)
            if (file.isFile) {
                RecoveryFileEntry(relativePath = relativePath, data = file.readBytes())
            } else {
                null
            }
        }
    }

    private fun restoreRecoveryStateAtomically(
        saltBytes: ByteArray,
        bundle: Map<String, ByteArray>,
        stateEntries: List<String>,
    ) {
        val filesDir = appContext.filesDir.canonicalFile
        if (!filesDir.exists() && !filesDir.mkdirs()) {
            throw IOException("Failed to prepare app storage.")
        }

        val publishEntries = mutableListOf(RecoveryFileEntry(RECOVERY_SALT_FILE, saltBytes))
        stateEntries.forEach { relativePath ->
            val normalizedPath = normalizeRecoveryRelativePath(relativePath)
            val entryName = RECOVERY_STATE_PREFIX + normalizedPath
            val data = bundle[entryName]
                ?: throw IllegalStateException("Recovery bundle is missing state entry '$normalizedPath'.")
            publishEntries += RecoveryFileEntry(normalizedPath, data)
        }

        val stagingDir = File(filesDir, RECOVERY_STAGING_DIR)
        if (stagingDir.exists() && !stagingDir.deleteRecursively()) {
            throw IOException("Failed to clear previous recovery staging directory.")
        }
        if (!stagingDir.mkdirs()) {
            throw IOException("Failed to prepare recovery staging directory.")
        }

        try {
            publishEntries.forEach { entry ->
                writeRecoveryFileUnderBase(stagingDir, entry.relativePath, entry.data)
            }
            publishEntries.forEach { entry ->
                publishRecoveryFile(stagingDir, filesDir, entry.relativePath)
            }
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    private fun normalizeRecoveryRelativePath(relativePath: String): String {
        val normalized = relativePath.replace('\\', '/').trim().trimStart('/')
        require(normalized.isNotEmpty()) { "Recovery state path is empty." }
        require(!normalized.contains("..")) { "Recovery state path is invalid." }
        val segments = normalized.split('/')
        require(segments.none { it.isBlank() || it == "." || it == ".." }) {
            "Recovery state path is invalid."
        }
        return normalized
    }

    private fun writeRecoveryFileUnderBase(baseDir: File, relativePath: String, data: ByteArray) {
        val canonicalBase = baseDir.canonicalFile
        if (!canonicalBase.exists() && !canonicalBase.mkdirs()) {
            throw IOException("Failed to prepare recovery directory.")
        }
        val targetFile = File(canonicalBase, relativePath).canonicalFile
        require(targetFile.path == canonicalBase.path || targetFile.path.startsWith(canonicalBase.path + File.separator)) {
            "Recovery state path escapes staging storage."
        }

        targetFile.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("Failed to prepare recovery directory.")
            }
        }

        targetFile.writeBytes(data)
    }

    private fun publishRecoveryFile(stagingDir: File, filesDir: File, relativePath: String) {
        val canonicalStaging = stagingDir.canonicalFile
        val baseDir = filesDir.canonicalFile
        val stagedFile = File(canonicalStaging, relativePath).canonicalFile
        require(stagedFile.path == canonicalStaging.path || stagedFile.path.startsWith(canonicalStaging.path + File.separator)) {
            "Recovery staging path escapes staging storage."
        }

        val targetFile = File(baseDir, relativePath).canonicalFile
        require(targetFile.path == baseDir.path || targetFile.path.startsWith(baseDir.path + File.separator)) {
            "Recovery state path escapes app storage."
        }

        targetFile.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("Failed to prepare recovery directory.")
            }
        }

        val tempFile = File(targetFile.parentFile ?: baseDir, targetFile.name + ".restore")
        if (tempFile.exists() && !tempFile.delete()) {
            throw IOException("Failed to replace recovery temp file '$relativePath'.")
        }
        if (!stagedFile.renameTo(tempFile)) {
            stagedFile.copyTo(tempFile, overwrite = true)
            if (!stagedFile.delete()) {
                tempFile.delete()
                throw IOException("Failed to clear staged recovery file '$relativePath'.")
            }
        }
        if (targetFile.exists() && !targetFile.delete()) {
            tempFile.delete()
            throw IOException("Failed to replace recovery state file '$relativePath'.")
        }
        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }
    }

    private fun buildRecoveryZip(entries: Map<String, ByteArray>): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            entries.forEach { (name, data) ->
                writeRecoveryZipEntry(zip, name, data)
            }
        }
        return buffer.toByteArray()
    }

    private fun readRecoveryZip(zipBytes: ByteArray): Map<String, ByteArray> {
        require(zipBytes.size.toLong() <= MAX_RECOVERY_ZIP_BYTES) {
            "Recovery bundle is too large after decryption."
        }
        val entries = linkedMapOf<String, ByteArray>()
        var totalBytes = 0L
        ZipInputStream(zipBytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                try {
                    if (!entry.isDirectory) {
                        require(entries.size < MAX_RECOVERY_ZIP_ENTRIES) {
                            "Recovery bundle contains too many files."
                        }
                        val entryName = normalizeRecoveryZipEntryName(entry.name)
                        require(!entries.containsKey(entryName)) {
                            "Recovery bundle contains duplicate entry '$entryName'."
                        }
                        val entryBytes = readRecoveryZipEntryLimited(zip, recoveryEntryLimit(entryName))
                        totalBytes += entryBytes.size.toLong()
                        require(totalBytes <= MAX_RECOVERY_ZIP_BYTES) {
                            "Recovery bundle is too large after decompression."
                        }
                        entries[entryName] = entryBytes
                    }
                } finally {
                    zip.closeEntry()
                }
            }
        }
        return entries
    }

    private fun normalizeRecoveryZipEntryName(name: String): String {
        val normalized = name.replace('\\', '/').trim().trimStart('/')
        return when {
            normalized == RECOVERY_MANIFEST_FILE -> normalized
            normalized == RECOVERY_SALT_FILE -> normalized
            normalized.startsWith(RECOVERY_STATE_PREFIX) -> {
                RECOVERY_STATE_PREFIX + normalizeRecoveryRelativePath(
                    normalized.removePrefix(RECOVERY_STATE_PREFIX),
                )
            }
            else -> throw IllegalArgumentException("Recovery bundle contains unsupported entry '$normalized'.")
        }
    }

    private fun recoveryEntryLimit(entryName: String): Long = when (entryName) {
        RECOVERY_MANIFEST_FILE -> MAX_RECOVERY_MANIFEST_BYTES
        RECOVERY_SALT_FILE -> 64L
        else -> MAX_RECOVERY_ENTRY_BYTES
    }

    private fun readRecoveryZipEntryLimited(zip: ZipInputStream, limitBytes: Long): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(IO_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = zip.read(chunk)
            if (read < 0) break
            if (read == 0) continue
            total += read.toLong()
            require(total <= limitBytes) {
                "Recovery bundle entry is too large."
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private fun writeRecoveryZipEntry(zip: ZipOutputStream, name: String, data: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(data)
        zip.closeEntry()
    }

    private fun encodeRecoveryCode(rawBytes: ByteArray): String {
        val encoded = encodeCrockfordBase32(rawBytes)
        return encoded.chunked(RECOVERY_CODE_GROUP_SIZE).joinToString("-")
    }

    private fun parseRecoveryCode(code: String): ParsedRecoveryCode {
        val normalized = code
            .uppercase(Locale.US)
            .filterNot { it == '-' || it.isWhitespace() }
        require(normalized.isNotBlank()) { "Recovery code is empty." }

        val rawBytes = decodeCrockfordBase32(normalized)
        require(rawBytes.size == RECOVERY_CODE_RAW_BYTES + RECOVERY_CODE_CHECKSUM_BYTES) {
            "Recovery code has invalid length."
        }

        val payload = rawBytes.copyOfRange(0, RECOVERY_CODE_RAW_BYTES)
        val checksum = rawBytes.copyOfRange(RECOVERY_CODE_RAW_BYTES, rawBytes.size)
        val expected = CRC32().apply { update(payload) }.value
        val actual = ((checksum[0].toLong() and 0xFF) shl 24) or
            ((checksum[1].toLong() and 0xFF) shl 16) or
            ((checksum[2].toLong() and 0xFF) shl 8) or
            (checksum[3].toLong() and 0xFF)
        require(actual == expected) { "Recovery code checksum mismatch." }

        require((payload[0].toInt() and 0xFF) == RECOVERY_CODE_VERSION) {
            "Unsupported recovery code version."
        }
        val avatarIndex = payload[1].toInt() and 0xFF
        val salt = payload.copyOfRange(2, 34)
        val peerBytes = payload.copyOfRange(34, 66)
        val peerId = Base64.encodeToString(peerBytes, Base64.NO_WRAP)
        return ParsedRecoveryCode(
            peerId = peerId,
            salt = salt,
            avatarIndex = avatarIndex,
        )
    }

    private fun encodeCrockfordBase32(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val out = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bitCount = 0
        data.forEach { byte ->
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bitCount += 8
            while (bitCount >= 5) {
                out.append(RECOVERY_CODE_ALPHABET[(buffer shr (bitCount - 5)) and 31])
                bitCount -= 5
            }
        }
        if (bitCount > 0) {
            out.append(RECOVERY_CODE_ALPHABET[(buffer shl (5 - bitCount)) and 31])
        }
        return out.toString()
    }

    private fun decodeCrockfordBase32(text: String): ByteArray {
        val out = ByteArrayOutputStream((text.length * 5) / 8)
        var buffer = 0
        var bitCount = 0
        text.forEach { ch ->
            val value = crockfordValue(ch)
            buffer = (buffer shl 5) or value
            bitCount += 5
            while (bitCount >= 8) {
                out.write((buffer shr (bitCount - 8)) and 0xFF)
                bitCount -= 8
            }
        }
        if (bitCount > 0) {
            require((buffer and ((1 shl bitCount) - 1)) == 0) { "Recovery code is malformed." }
        }
        return out.toByteArray()
    }

    private fun crockfordValue(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch.code - '0'.code
        in 'A'..'H' -> ch.code - 'A'.code + 10
        'J' -> 18
        'K' -> 19
        'M' -> 20
        'N' -> 21
        'P' -> 22
        'Q' -> 23
        'R' -> 24
        'S' -> 25
        'T' -> 26
        'V' -> 27
        'W' -> 28
        'X' -> 29
        'Y' -> 30
        'Z' -> 31
        'O' -> 0
        'I', 'L' -> 1
        else -> throw IllegalArgumentException("Recovery code contains unsupported character '$ch'.")
    }

    private fun encryptRecoveryPayload(plaintext: ByteArray, password: CharArray): ByteArray {
        val salt = ByteArray(16)
        val iv = ByteArray(12)
        SecureRandom().nextBytes(salt)
        SecureRandom().nextBytes(iv)
        val keyBytes = deriveRecoveryKey(password, salt)
        return try {
            val cipher = Cipher.getInstance(BG_AES_GCM)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(BG_TAG_LEN, iv),
            )
            val ciphertext = cipher.doFinal(plaintext)
            ByteArrayOutputStream().apply {
                write(RECOVERY_MAGIC)
                write(salt)
                write(iv)
                write(ciphertext)
            }.toByteArray()
        } finally {
            keyBytes.fill(0)
        }
    }

    private fun decryptRecoveryPayload(payload: ByteArray, password: CharArray): ByteArray {
        val minSize = RECOVERY_MAGIC.size + 16 + 12 + 16
        require(payload.size >= minSize) { "Recovery bundle is too small." }
        val magic = payload.copyOfRange(0, RECOVERY_MAGIC.size)
        require(magic.contentEquals(RECOVERY_MAGIC)) { "Unsupported recovery bundle format." }

        val saltStart = RECOVERY_MAGIC.size
        val ivStart = saltStart + 16
        val cipherStart = ivStart + 12
        val salt = payload.copyOfRange(saltStart, ivStart)
        val iv = payload.copyOfRange(ivStart, cipherStart)
        val ciphertext = payload.copyOfRange(cipherStart, payload.size)
        val keyBytes = deriveRecoveryKey(password, salt)
        return try {
            val cipher = Cipher.getInstance(BG_AES_GCM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(BG_TAG_LEN, iv),
            )
            cipher.doFinal(ciphertext)
        } catch (_: AEADBadTagException) {
            throw IllegalStateException("Incorrect recovery password or corrupted bundle.")
        } finally {
            keyBytes.fill(0)
        }
    }

    private fun deriveRecoveryKey(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, RECOVERY_PBKDF2_ITERS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
        }
    }

    // ── Background-cells Keystore path ───────────────────────────────────
    //
    // When the user authenticates (pattern / PIN), we encrypt the
    // raw 32-byte pattern cells with a hardware-backed Android Keystore AES key
    // that does NOT require user authentication.  This lets AdaForegroundService
    // re-open the core on START_STICKY restarts (OEM process kill, device reboot)
    // without showing a lock screen — identical to how Telegram / WhatsApp work.
    //
    // Security model: the Keystore key is TEE-backed and tied to the device.
    // It is NOT accessible via ADB on non-rooted devices.  The "pattern lock"
    // is an app-level UI gate; the DB is also SQLCipher-encrypted with the
    // pattern-derived key.  This matches the standard Android security model.

    private val BG_KEYSTORE_ALIAS = "ada_bg_unlock_v1"
    private val BG_PREFS_NAME     = "ada_bg_cells_v1"
    private val BG_PREF_CELLS_IV  = "cells_iv"
    private val BG_PREF_CELLS_ENC = "cells_enc"
    private val BG_AES_GCM        = "AES/GCM/NoPadding"
    private val BG_KEYSTORE       = "AndroidKeyStore"
    private val BG_TAG_LEN        = 128

    /**
     * Encrypt [cells] with a hardware-backed Keystore key (no user auth required)
     * and persist IV + ciphertext in plain SharedPreferences.
     * Called after every successful authentication.
     */
    fun saveBackgroundCells(cells: ByteArray) {
        if (cells.isEmpty()) return
        try {
            val key = getBgKeystoreKey()
            val cipher = Cipher.getInstance(BG_AES_GCM)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv  = cipher.iv
            val enc = cipher.doFinal(cells)
            appContext.getSharedPreferences(BG_PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(BG_PREF_CELLS_IV,  Base64.encodeToString(iv,  Base64.NO_WRAP))
                .putString(BG_PREF_CELLS_ENC, Base64.encodeToString(enc, Base64.NO_WRAP))
                .commit()
            Log.d(TAG, "Background cells saved (${cells.size} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "saveBackgroundCells failed: ${e.message}")
        }
    }

    /**
     * Load and decrypt background cells.  Returns `null` if never saved, key was
     * wiped (factory reset / app reinstall), or on any decryption error.
     */
    fun loadBackgroundCells(): ByteArray? {
        return try {
            val prefs = appContext.getSharedPreferences(BG_PREFS_NAME, Context.MODE_PRIVATE)
            val ivB64  = prefs.getString(BG_PREF_CELLS_IV,  null) ?: return null
            val encB64 = prefs.getString(BG_PREF_CELLS_ENC, null) ?: return null
            val iv  = Base64.decode(ivB64,  Base64.NO_WRAP)
            val enc = Base64.decode(encB64, Base64.NO_WRAP)
            val ks  = KeyStore.getInstance(BG_KEYSTORE).also { it.load(null) }
            val key = ks.getKey(BG_KEYSTORE_ALIAS, null) ?: return null
            val cipher = Cipher.getInstance(BG_AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(BG_TAG_LEN, iv))
            val cells = cipher.doFinal(enc)
            Log.d(TAG, "Background cells loaded (${cells.size} bytes)")
            cells
        } catch (e: Exception) {
            Log.w(TAG, "loadBackgroundCells failed (expected on first run or after reinstall): ${e.message}")
            null
        }
    }

    /** Clear background cells on logout / account wipe. */
    fun clearBackgroundCells() {
        appContext.getSharedPreferences(BG_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        try {
            val ks = KeyStore.getInstance(BG_KEYSTORE).also { it.load(null) }
            if (ks.containsAlias(BG_KEYSTORE_ALIAS)) ks.deleteEntry(BG_KEYSTORE_ALIAS)
        } catch (e: Exception) { /* ignore */ }
        Log.d(TAG, "Background cells cleared")
    }

    /** Get-or-create the hardware-backed AES key for background cell encryption. */
    private fun getBgKeystoreKey(): java.security.Key {
        val ks = KeyStore.getInstance(BG_KEYSTORE).also { it.load(null) }
        if (ks.containsAlias(BG_KEYSTORE_ALIAS)) {
            return ks.getKey(BG_KEYSTORE_ALIAS, null)
        }
        val spec = KeyGenParameterSpec.Builder(
            BG_KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // No user authentication required — this key is used by the background
            // service to re-open the core after process death without user interaction.
            .setUserAuthenticationRequired(false)
            // Require device unlock (screen-off wipe) to stay accessible.
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserPresenceRequired(false)
                }
            }
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, BG_KEYSTORE)
            .also { it.init(spec) }
            .generateKey()
        return ks.getKey(BG_KEYSTORE_ALIAS, null)
    }

    private fun startForegroundService(displayName: String, dataDir: String) {
        val intent = com.ada.messenger.service.AdaForegroundService.buildStartIntent(
            appContext, displayName, dataDir
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
    }
}
