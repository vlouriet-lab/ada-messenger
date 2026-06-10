package com.ada.messenger.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.*
import com.ada.messenger.MainActivity
import com.ada.messenger.core.AdaCore
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ─────────────────────────────────────────────────────────────────────────────
// ADANotificationService
//
// Uses WorkManager PeriodicWorkRequest to poll ada_core events every 15 minutes
// (minimum interval allowed by WorkManager) while the app is in the background.
// Posts system notifications for:
//   • MessageReceived  → "New message from <peer>"
//   • IncomingCall     → "Incoming call from <peer>"
//
// How to wire up (call once from Application.onCreate):
//   ADANotificationService.schedule(applicationContext)
// ─────────────────────────────────────────────────────────────────────────────

class ADANotificationWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext

        // BUG-008 FIX: The old implementation created a brand-new AdaCore which
        // had no events in its queue (fresh in-memory state). Now that we have
        // AdaForegroundService handling background notifications, the worker just
        // ensures the foreground service is running so it can pick up events.
        val credentials = readCredentials(ctx) ?: return Result.success()

        val intent = com.ada.messenger.service.AdaForegroundService.buildStartIntent(
            ctx, credentials.displayName, credentials.dataDir
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }

        return Result.success()
    }

    companion object {
        private const val TAG = "ADANotificationWorker"

        // K14: store is an encrypted preferences file so display name and data dir
        // are not readable from backup / ADB on non-rooted devices.
        const val PREFS_NAME        = "ada_worker_prefs_v2"
        const val PREF_DISPLAY_NAME = "display_name"
        const val PREF_DATA_DIR     = "data_dir"

        internal data class WorkerCredentials(
            val displayName: String,
            val dataDir: String,
        )

        internal fun readCredentials(context: Context): WorkerCredentials? {
            readLegacyPlainCredentials(context)?.let { legacy ->
                Log.w(TAG, "Migrating legacy plain worker credentials to encrypted prefs")
                context.deleteSharedPreferences(PREFS_NAME)
                saveCredentials(context, legacy.displayName, legacy.dataDir)
                return legacy
            }

            return runCatching {
                val prefs = getEncryptedPrefs(context)
                val displayName = prefs.getString(PREF_DISPLAY_NAME, null)
                    ?.takeIf { it.isNotBlank() }
                    ?: return null
                val dataDir = prefs.getString(PREF_DATA_DIR, null)
                    ?: context.filesDir.absolutePath
                WorkerCredentials(displayName, dataDir)
            }.getOrElse { error ->
                Log.w(TAG, "Encrypted worker credentials are unreadable; resetting prefs", error)
                context.deleteSharedPreferences(PREFS_NAME)
                null
            }
        }

        internal fun saveCredentials(context: Context, displayName: String, dataDir: String) {
            val ok = getEncryptedPrefs(context)
                .edit()
                .putString(PREF_DISPLAY_NAME, displayName)
                .putString(PREF_DATA_DIR, dataDir)
                .commit()
            if (!ok) {
                throw IllegalStateException("Failed to persist encrypted worker credentials.")
            }
        }

        internal fun getEncryptedPrefs(context: Context): SharedPreferences {
            if (hasLegacyPlainCredentials(context)) {
                Log.w(TAG, "Discarding legacy plain worker credentials before encrypted prefs open")
                context.deleteSharedPreferences(PREFS_NAME)
            }
            return runCatching { createEncryptedPrefs(context) }
                .getOrElse { error ->
                    Log.w(TAG, "Encrypted worker prefs open failed; resetting prefs", error)
                    context.deleteSharedPreferences(PREFS_NAME)
                    createEncryptedPrefs(context)
                }
        }

        private fun createEncryptedPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        private fun readLegacyPlainCredentials(context: Context): WorkerCredentials? {
            val plainPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val displayName = plainPrefs.getString(PREF_DISPLAY_NAME, null)
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val dataDir = plainPrefs.getString(PREF_DATA_DIR, null)
                ?: context.filesDir.absolutePath
            return WorkerCredentials(displayName, dataDir)
        }

        private fun hasLegacyPlainCredentials(context: Context): Boolean {
            val plainPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return plainPrefs.contains(PREF_DISPLAY_NAME) || plainPrefs.contains(PREF_DATA_DIR)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Scheduling helper
// ─────────────────────────────────────────────────────────────────────────────

object ADANotificationService {

    private const val WORK_TAG = "ada_background_poll"

    /** Schedule (or reschedule) the periodic background poll. Idempotent. */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<ADANotificationWorker>(
            repeatInterval = 15,
            repeatIntervalTimeUnit = TimeUnit.MINUTES
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(WORK_TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_TAG,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /** Cancel background polling (e.g. when user logs out). */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG)
    }

    /**
     * Persist display name + data dir so the background worker can create
     * a short-lived AdaCore instance without going through the main Activity.
     */
    fun saveCredentials(context: Context, displayName: String, dataDir: String) {
        ADANotificationWorker.saveCredentials(context, displayName, dataDir)
    }
}
