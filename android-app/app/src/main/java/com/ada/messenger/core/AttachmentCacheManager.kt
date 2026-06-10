package com.ada.messenger.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "AttachmentCacheManager"
private const val MAX_CACHE_SIZE_BYTES = 500L * 1024 * 1024 // 500 MB
private val MAX_AGE_MS = TimeUnit.DAYS.toMillis(30)

object AttachmentCacheManager {

    suspend fun cleanupOldAndOversized(context: Context) = withContext(Dispatchers.IO) {
        val attachmentsDir = File(context.cacheDir, "attachments")
        if (!attachmentsDir.exists() || !attachmentsDir.isDirectory) return@withContext

        try {
            val now = System.currentTimeMillis()
            var totalSize = 0L
            val filesList = mutableListOf<File>()

            // Traverse attachments directory (level 1 is fileId, level 2 is the file)
            attachmentsDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val age = now - file.lastModified()
                if (age > MAX_AGE_MS) {
                    Log.i(TAG, "Deleting old attachment: \${file.name} (age: \${age / 86400000} days)")
                    file.delete()
                } else {
                    filesList.add(file)
                    totalSize += file.length()
                }
            }

            // Cleanup empty directories
            attachmentsDir.walkBottomUp().filter { it.isDirectory && it != attachmentsDir }.forEach { dir ->
                if (dir.listFiles()?.isEmpty() == true) {
                    dir.delete()
                }
            }

            // If total cache size > 500MB, delete oldest files until under 400MB
            if (totalSize > MAX_CACHE_SIZE_BYTES) {
                val targetSize = 400L * 1024 * 1024
                filesList.sortBy { it.lastModified() }
                
                for (file in filesList) {
                    if (totalSize <= targetSize) break
                    val len = file.length()
                    if (file.delete()) {
                        Log.i(TAG, "Evicted attachment to free space: \${file.name}")
                        totalSize -= len
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up attachment cache", e)
        }
    }
}
