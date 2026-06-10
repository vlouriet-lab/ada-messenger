package com.ada.messenger.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import com.ada.messenger.R
import java.io.File
import java.io.IOException
import kotlin.math.min

private fun extForMime(mimeType: String): String = when {
    mimeType.startsWith("image/") -> ".jpg"
    mimeType.startsWith("video/") -> ".mp4"
    mimeType.startsWith("audio/") -> ".ogg"
    else -> ".bin"
}

private const val TAG = "TransferManager"
// Auto-clear cache: wipe attachments after 30 days or when total size > 500 MB.
private const val CACHE_PREFS_NAME = "ada_transfer_cache_v1"
private const val PREF_LAST_CLEARED_MS = "last_cleared_ms"
private const val CACHE_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1_000   // 30 days
private const val CACHE_MAX_BYTES = 500L * 1024 * 1024              // 500 MB
// Maximum pending-queue retry attempts before a blob is permanently dropped.
// Raised from 12 → 200 so a blob is retried for ~30 minutes (at 60s max backoff)
// rather than giving up in ~90 s.  With relay_hint pre-registration, most blobs
// succeed within the first 2 attempts on new sessions; the high limit exists as a
// safety net for very slow pkarr DNS propagation on mobile LTE/5G.
private const val MAX_BLOB_FETCH_ATTEMPTS = 200

private data class PendingBlobFetch(
    val fileIdHex: String,
    val fromPeerB64: String,
    val hashHex: String,
    val fileName: String,
    var attempts: Int = 0,
    var nextAttemptAtMs: Long = 0L,
)

/**
 * Manages file transfer lifecycle: sending, receiving, caching, and progress tracking.
 *
 * Single Responsibility: file transfer state.
 */
class TransferManager(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val coreProvider: () -> AdaCore?,
) {

    private val _transfers = MutableStateFlow<List<TransferItem>>(emptyList())
    val transfers: StateFlow<List<TransferItem>> = _transfers.asStateFlow()

    /** Maps transfer_id_hex → absolute path of the saved file in the app cache. */
    private val _savedFiles = MutableStateFlow<Map<String, String>>(emptyMap())
    val savedFiles: StateFlow<Map<String, String>> = _savedFiles.asStateFlow()
    private val pendingBlobFetches = linkedMapOf<String, PendingBlobFetch>()
    private val blobMutex = Mutex()

    private val mapFile = File(appContext.filesDir, "saved_files_map.json")

    init {
        // Purge any leftover plaintext temp files from previous session
        purgeTempAttachments()
        // Restore persisted file mappings and scan cache dir for existing files
        scope.launch(Dispatchers.IO) {
            val restored = loadSavedFilesMap()
            val scanned = scanCachedAttachments()
            val merged = restored + scanned
            if (merged.isNotEmpty()) {
                _savedFiles.value = merged
                if (scanned.isNotEmpty()) persistSavedFilesMap(merged)
            }
            // Auto-clear cache after restore so stale entries are cleaned up too
            maybeAutoClearCache()
        }
        // Background recovery loop: if network is flaky and immediate blob fetch
        // failed, retry with exponential backoff until success.
        scope.launch(Dispatchers.IO) {
            while (true) {
                processPendingBlobFetches()
                kotlinx.coroutines.delay(3_000L)
            }
        }
    }

    // ── Attachment encryption helpers ─────────────────────────────────────

    /**
     * Keystore-backed master key for EncryptedFile.
     * Lazily initialised on first use; the key is hardware-backed (StrongBox /
     * TEE) on supported devices and is never extractable.
     */
    private val attachmentMasterKey: MasterKey by lazy {
        MasterKey.Builder(appContext, "ada_attachment_master_key_v1")
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    /**
     * Build an [EncryptedFile] instance for [file].
     * Each attachment file is encrypted individually with AES256-GCM-HKDF-4KB
     * streaming AEAD; the per-file keyset is derived from the master key and the
     * absolute path, so files can only be opened on the same device.
     *
     * The [EncryptedFile] API requires that the target file either does not exist
     * or was created by a previous call with the same parameters; calling this with
     * an existing plaintext file will throw [IOException].
     */
    private fun encryptedFileFor(file: File): EncryptedFile =
        EncryptedFile.Builder(
            appContext,
            file,
            attachmentMasterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()

    /**
     * Write [bytes] to [destFile] using EncryptedFile (AES256-GCM-HKDF-4KB).
     * Returns the path to the written file (same as [destFile]) or `null` on error.
     *
     * The file name is stored with an `.enc` suffix so callers can distinguish
     * encrypted attachments from plain files.  The suffix is purely informational —
     * the file contains Tink-framed ciphertext and cannot be opened as plaintext.
     */
    private fun writeEncrypted(destFile: File, bytes: ByteArray): Boolean {
        // Delete any previous ciphertext so EncryptedFile can write a fresh keyset.
        if (destFile.exists()) destFile.delete()
        return try {
            encryptedFileFor(destFile).openFileOutput().use { it.write(bytes) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "writeEncrypted failed for ${destFile.name}: ${e.message}")
            destFile.delete()
            false
        }
    }

    /**
     * Open an attachment for reading.
     *
     * • If [path] ends with `.enc`, the file is decrypted on-the-fly via the
     *   Keystore-backed master key and the bytes are returned.
     * • Otherwise the file is read as plain bytes (backward compat / outbound files).
     *
     * Returns `null` if the file does not exist or decryption fails.
     */
    fun readAttachment(path: String): ByteArray? {
        val file = File(path)
        if (!file.isFile) return null
        return try {
            if (path.endsWith(".enc")) {
                encryptedFileFor(file).openFileInput().use { it.readBytes() }
            } else {
                file.readBytes()
            }
        } catch (e: Exception) {
            Log.e(TAG, "readAttachment failed for ${file.name}: ${e.message}")
            null
        }
    }

    /**
     * Decrypt an encrypted attachment to a temporary plaintext file so that
     * standard Android media APIs (Coil, ExoPlayer, ContentResolver) can access it.
     *
     * The decrypted copy lives in [appContext.cacheDir]/tmp_attachments/ and is
     * named after the original file (without `.enc`).  Callers must not retain
     * the returned [File] reference beyond the current display lifecycle — the
     * directory is purged at every app start via [purgeTempAttachments].
     *
     * @return the decrypted [File], or `null` if decryption failed.
     */
    fun decryptToTempFile(encPath: String): File? {
        val bytes = readAttachment(encPath) ?: return null
        val tmpDir = File(appContext.cacheDir, "tmp_attachments")
        tmpDir.mkdirs()
        val plainName = File(encPath).name.removeSuffix(".enc")
        val tmp = File(tmpDir, plainName)
        return try {
            tmp.writeBytes(bytes)
            tmp
        } catch (e: Exception) {
            Log.e(TAG, "decryptToTempFile failed for $plainName: ${e.message}")
            null
        }
    }

    /** Delete all temporary decrypted copies created by [decryptToTempFile]. */
    fun purgeTempAttachments() {
        val tmpDir = File(appContext.cacheDir, "tmp_attachments")
        if (tmpDir.isDirectory) {
            tmpDir.deleteRecursively()
            Log.d(TAG, "Purged tmp_attachments/")
        }
    }

    // ── Sending ──────────────────────────────────────────────────────────

    /**
     * Send a file attachment to a direct conversation.
     *
     * @return error message if validation fails, `null` on success.
     */
    fun sendAttachment(
        convId: String,
        fileName: String,
        mimeType: String,
        filePath: String,
        onRefresh: () -> Unit,
    ): String? {
        if (convId.startsWith("g:")) {
            return appContext.getString(R.string.error_file_transfer_group_not_supported)
        }
        val outboundFile = File(filePath)
        if (!outboundFile.isFile) {
            return appContext.getString(R.string.error_file_prepare_failed)
        }
        if (outboundFile.length() > AdaConfig.MAX_FILE_SIZE) {
            return appContext.getString(R.string.error_file_too_large)
        }
        val safeName = File(fileName).name.ifBlank { outboundFile.name.ifBlank { "attachment" } }
        val peerIdB64 = convId.removePrefix("d:")
        scope.launch(Dispatchers.IO) {
            val transferId = coreProvider()?.sendFileFromPath(peerIdB64, safeName, mimeType, outboundFile.absolutePath)
            if (transferId == null) {
                Log.w(TAG, "sendFileFromPath returned null for $safeName")
            } else {
                updateSavedFiles(mapOf(
                    transferId to outboundFile.absolutePath,
                    safeName to outboundFile.absolutePath,
                ))
                refresh()
                onRefresh()
            }
        }
        return null
    }

    fun sendFile(convId: String, fileName: String, filePath: String) {
        val error = sendAttachment(convId, fileName, "application/octet-stream", filePath) {}
        if (error != null) {
            Log.w(TAG, "sendFile validation failed for $fileName: $error")
        }
    }

    fun cancelTransfer(transferIdHex: String) {
        scope.launch(Dispatchers.IO) {
            coreProvider()?.cancelTransfer(transferIdHex)
            refresh()
        }
    }

    // ── Receiving ─────────────────────────────────────────────────────────

    /** Save a completed transfer to the cache directory (encrypted). */
    fun onTransferCompleted(transferId: String, fileName: String, onRefreshMessages: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                // A-2: Sanitize fileName to prevent path traversal (../ injection)
                val safeName = java.io.File(fileName).name.ifBlank { "attachment" }
                val dir = java.io.File(appContext.cacheDir, "attachments/$transferId")
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.e(TAG, "Failed to create transfer dir for $transferId")
                    return@launch
                }
                // Write to a plain staging file first (saveTransferToFile needs a real path).
                val stagingFile = java.io.File(dir, safeName)
                // Extra guard: ensure resolved path stays inside the target directory
                if (!stagingFile.canonicalPath.startsWith(dir.canonicalPath)) {
                    Log.e(TAG, "Path traversal blocked for fileName=$fileName")
                    return@launch
                }
                val metaJson = coreProvider()?.saveTransferToFile(transferId, stagingFile.absolutePath)
                if (metaJson == null) {
                    Log.w(TAG, "saveTransferToFile returned null for $transferId file=$safeName")
                    return@launch
                }

                // Encrypt the staged plain file → {safeName}.enc, then delete staging file.
                val encFile = java.io.File(dir, "$safeName.enc")
                val encOk = runCatching {
                    val plainBytes = stagingFile.readBytes()
                    val encrypted = writeEncrypted(encFile, plainBytes)
                    if (encrypted) stagingFile.delete()
                    encrypted
                }.getOrElse { e ->
                    Log.e(TAG, "Encryption failed for $safeName: ${e.message}")
                    false
                }

                if (!encOk) {
                    stagingFile.delete()
                    encFile.delete()
                    Log.e(TAG, "Transfer encryption failed; plaintext copy discarded for $safeName")
                    return@launch
                }

                val savedPath = encFile.absolutePath

                updateSavedFiles(mapOf(
                    transferId to savedPath,
                    safeName to savedPath,
                ))
                onRefreshMessages()
                Log.i(TAG, "Transfer $transferId saved as encrypted attachment")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save transfer $transferId: ${e.message}", e)
            }
        }
    }

    /** Fetch a BlobRef attachment into cache and expose it in savedFiles map. */
    fun onBlobAvailable(
        fileIdHex: String,
        fromPeerB64: String,
        hashHex: String,
        fileName: String,
        onRefreshMessages: () -> Unit,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val safeName = java.io.File(fileName).name.ifBlank { "attachment" }
                if (tryFetchBlobNow(fileIdHex, fromPeerB64, hashHex, safeName, onRefreshMessages)) {
                    return@launch
                }

                enqueuePendingBlob(
                    fileIdHex = fileIdHex,
                    fromPeerB64 = fromPeerB64,
                    hashHex = hashHex,
                    fileName = safeName,
                )
                Log.w(TAG, "fetchBlobToFile deferred id=$fileIdHex hash=${hashHex.take(12)}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch blob $fileIdHex: ${e.message}", e)
            }
        }
    }

    fun retryPendingBlobs(onRefreshMessages: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            processPendingBlobFetches(onRefreshMessages)
        }
    }

    private suspend fun processPendingBlobFetches(onRefreshMessages: (() -> Unit)? = null) {
        val now = System.currentTimeMillis()
        val due = blobMutex.withLock {
            pendingBlobFetches.values
                .filter { it.nextAttemptAtMs <= now }
                .map { it.copy() }
        }
        if (due.isEmpty()) return

        for (entry in due) {
            val ok = tryFetchBlobNow(
                fileIdHex = entry.fileIdHex,
                fromPeerB64 = entry.fromPeerB64,
                hashHex = entry.hashHex,
                fileName = entry.fileName,
                onRefreshMessages = { onRefreshMessages?.invoke() },
            )
            blobMutex.withLock {
                val current = pendingBlobFetches[entry.fileIdHex] ?: return@withLock
                if (ok) {
                    pendingBlobFetches.remove(entry.fileIdHex)
                } else {
                    current.attempts += 1
                    if (current.attempts >= MAX_BLOB_FETCH_ATTEMPTS) {
                        pendingBlobFetches.remove(entry.fileIdHex)
                        Log.w(TAG, "Blob fetch dropped after max attempts id=${entry.fileIdHex}")
                    } else {
                        val backoffSec = min(60L, 2L * (1L shl min(5, current.attempts)))
                        current.nextAttemptAtMs = System.currentTimeMillis() + backoffSec * 1000L
                    }
                }
            }
        }
    }

    private suspend fun enqueuePendingBlob(
        fileIdHex: String,
        fromPeerB64: String,
        hashHex: String,
        fileName: String,
    ) {
        blobMutex.withLock {
            val existing = pendingBlobFetches[fileIdHex]
            if (existing == null) {
                pendingBlobFetches[fileIdHex] = PendingBlobFetch(
                    fileIdHex = fileIdHex,
                    fromPeerB64 = fromPeerB64,
                    hashHex = hashHex,
                    fileName = fileName,
                    attempts = 0,
                    nextAttemptAtMs = System.currentTimeMillis() + 2_000L,
                )
            } else {
                pendingBlobFetches[fileIdHex] = existing.copy(
                    fromPeerB64 = fromPeerB64,
                    hashHex = hashHex,
                    fileName = fileName,
                    nextAttemptAtMs = System.currentTimeMillis() + 2_000L,
                )
            }
        }
    }

    private suspend fun tryFetchBlobNow(
        fileIdHex: String,
        fromPeerB64: String,
        hashHex: String,
        fileName: String,
        onRefreshMessages: () -> Unit,
    ): Boolean {
        val dir = java.io.File(appContext.cacheDir, "attachments/$fileIdHex")
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "Failed to create blob dir for $fileIdHex")
            return false
        }
        // Stage to a plain file first; blob fetch needs a real FS path.
        val stagingFile = java.io.File(dir, fileName)
        if (!stagingFile.canonicalPath.startsWith(dir.canonicalPath)) {
            Log.e(TAG, "Path traversal blocked for blob fileName=$fileName")
            return false
        }

        var ok = false
        for (attempt in 0 until 3) {
            ok = coreProvider()?.fetchBlobToFile(fromPeerB64, hashHex, stagingFile.absolutePath) == true
            if (ok) break
            kotlinx.coroutines.delay((attempt + 1) * 1500L)
        }
        if (!ok) return false

        // Encrypt in-place → {fileName}.enc; fail closed if Keystore encryption is unavailable.
        val encFile = java.io.File(dir, "$fileName.enc")
        val savedPath = runCatching {
            val plainBytes = stagingFile.readBytes()
            try {
                if (!writeEncrypted(encFile, plainBytes)) {
                    stagingFile.delete()
                    encFile.delete()
                    Log.e(TAG, "Blob encryption failed; plaintext copy discarded for $fileName")
                    return false
                }
                stagingFile.delete()
                encFile.absolutePath
            } finally {
                plainBytes.fill(0)
            }
        }.getOrElse { e ->
            Log.e(TAG, "Post-fetch encryption failed for $fileName: ${e.message}")
            stagingFile.delete()
            encFile.delete()
            return false
        }

        updateSavedFiles(mapOf(
            fileIdHex to savedPath,
            fileName to savedPath,
        ))
        onRefreshMessages()
        Log.i(TAG, "Blob $fileIdHex saved as encrypted attachment")
        return true
    }

    // ── Refresh ──────────────────────────────────────────────────────────

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            val json = coreProvider()?.getTransfersJson() ?: "[]"
            val arr = runCatching { JSONArray(json) }.getOrNull() ?: return@launch
            val list = mutableListOf<TransferItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list += TransferItem(
                    id = o.optString("id"),
                    peer = o.optString("peer"),
                    fileName = o.optString("file_name"),
                    fileSize = o.optLong("file_size"),
                    mimeType = o.optString("mime_type"),
                    progress = o.optDouble("progress", 0.0).toFloat(),
                    isOutbound = o.optBoolean("is_outbound"),
                )
            }
            _transfers.value = list
        }
    }

    /** Clear all cached files — called on kill code. */
    fun clearCache() {
        _savedFiles.value = emptyMap()
        _transfers.value = emptyList()
        mapFile.delete()
        val attachDir = File(appContext.cacheDir, "attachments")
        if (attachDir.isDirectory) attachDir.deleteRecursively()
        purgeTempAttachments()
        cachePrefs().edit().putLong(PREF_LAST_CLEARED_MS, System.currentTimeMillis()).apply()
    }

    /**
     * Automatically wipe the attachment cache when it is older than 30 days
     * **or** larger than 500 MB.  Called at startup after restoring the saved-files
     * map so the UI starts with a clean slate when the threshold is exceeded.
     */
    private fun maybeAutoClearCache() {
        val now = System.currentTimeMillis()
        val lastCleared = cachePrefs().getLong(PREF_LAST_CLEARED_MS, 0L)
        val ageExceeded = (now - lastCleared) > CACHE_MAX_AGE_MS
        val sizeExceeded = cacheSize() > CACHE_MAX_BYTES
        if (ageExceeded || sizeExceeded) {
            val reason = if (sizeExceeded) "size=${cacheSize() / (1024 * 1024)} MB" else "age=${(now - lastCleared) / 86_400_000} days"
            Log.i(TAG, "Auto-clearing attachment cache ($reason)")
            clearCache()
        }
    }

    private fun cachePrefs(): SharedPreferences =
        appContext.getSharedPreferences(CACHE_PREFS_NAME, Context.MODE_PRIVATE)

    /** Total size of cached attachment files in bytes. */
    fun cacheSize(): Long {
        val attachDir = File(appContext.cacheDir, "attachments")
        return if (attachDir.isDirectory) attachDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
    }

    // ── Persistence helpers ──────────────────────────────────────────────

    private fun updateSavedFiles(newEntries: Map<String, String>) {
        val merged = _savedFiles.value + newEntries
        _savedFiles.value = merged
        scope.launch(Dispatchers.IO) { persistSavedFilesMap(merged) }
    }

    private fun persistSavedFilesMap(map: Map<String, String>) {
        try {
            val obj = JSONObject()
            for ((k, v) in map) obj.put(k, v)
            mapFile.writeText(obj.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist saved files map: ${e.message}")
        }
    }

    private fun loadSavedFilesMap(): Map<String, String> {
        if (!mapFile.isFile) return emptyMap()
        return try {
            val obj = JSONObject(mapFile.readText())
            val map = mutableMapOf<String, String>()
            for (key in obj.keys()) {
                val path = obj.optString(key, "")
                if (path.isNotEmpty() && File(path).isFile) {
                    map[key] = path
                }
            }
            Log.i(TAG, "Restored ${map.size} saved-file entries from disk")
            map
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load saved files map: ${e.message}")
            emptyMap()
        }
    }

    /** Scan cacheDir/attachments/ for existing files not in the persisted map. */
    private fun scanCachedAttachments(): Map<String, String> {
        val attachDir = File(appContext.cacheDir, "attachments")
        if (!attachDir.isDirectory) return emptyMap()
        val found = mutableMapOf<String, String>()
        attachDir.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val fileIdOrTransferId = dir.name
            // Prefer the encrypted version (.enc) when both exist.
            val file = dir.listFiles()
                ?.filter { it.isFile }
                ?.maxByOrNull { if (it.name.endsWith(".enc")) 1 else 0 }
                ?: return@forEach
            found[fileIdOrTransferId] = file.absolutePath
            // Also expose by plain name (without .enc suffix for display)
            val plainName = file.name.removeSuffix(".enc")
            found[plainName] = file.absolutePath
        }
        if (found.isNotEmpty()) Log.i(TAG, "Scanned ${found.size / 2} cached attachment dirs")
        return found
    }
}
