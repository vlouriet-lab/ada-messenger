package com.ada.messenger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.lang.ref.WeakReference
import androidx.core.app.NotificationCompat
import com.ada.messenger.MainActivity
import com.ada.messenger.R
import com.ada.messenger.core.AdaConfig
import com.ada.messenger.core.AdaCore
import com.ada.messenger.core.AdaCoreHolder
import com.ada.messenger.core.AppLockManager
import com.ada.messenger.core.IdentityManager
import org.json.JSONObject
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * AdaForegroundService — keeps the P2P connection alive when the app is backgrounded.
 *
 * BUG-009 FIX:
 *  - Runs as a foreground service with PARTIAL_WAKE_LOCK so Android's Doze / App Standby
 *    cannot kill the background thread after ~2 minutes.
 *  - Posts message / call notifications so users are alerted even when the UI is not open.
 *
 * Lifecycle:
 *  1. Start: AdaCoreViewModel calls startForegroundService(intent) after core init.
 *  2. The service spins up a polling thread, acquires the wakelock, posts the
 *     persistent foreground notification.
 *  3. Stop: ViewModel calls stopService(intent) on onCleared().
 */
class AdaForegroundService : Service() {

    companion object {
        private const val TAG = "AdaForegroundService"

        // Delegate to AdaConfig — single source of truth for all IDs
        const val CHANNEL_ID          = AdaConfig.CHANNEL_P2P_ID
        const val NOTIF_ID_FOREGROUND = AdaConfig.NOTIF_ID_FOREGROUND
        const val NOTIF_ID_MESSAGE    = AdaConfig.NOTIF_ID_MESSAGE
        const val NOTIF_ID_CALL       = AdaConfig.NOTIF_ID_CALL
        const val CHANNEL_MSG_ID      = AdaConfig.CHANNEL_MSG_ID
        const val CHANNEL_CALL_ID     = AdaConfig.CHANNEL_CALL_ID
        const val CHANNEL_NOTIF_ID    = AdaConfig.CHANNEL_NOTIF_ID

        const val EXTRA_DISPLAY_NAME  = "display_name"
        const val EXTRA_DATA_DIR      = "data_dir"

        fun buildStartIntent(ctx: Context, displayName: String, dataDir: String): Intent =
            Intent(ctx, AdaForegroundService::class.java).apply {
                putExtra(EXTRA_DISPLAY_NAME, displayName)
                putExtra(EXTRA_DATA_DIR, dataDir)
            }

        // ── Static instance ref for type promotion (screen sharing) ────────
        // WeakReference so we never prevent GC of the service object.
        @Volatile private var serviceRef: WeakReference<AdaForegroundService>? = null

        /**
         * Promote the foreground service type to include MEDIA_PROJECTION before starting
         * ScreenCapturerAndroid. Required on Android 14+ (API 34) — the system validates the
         * FGS type against the MediaProjection token at capture-start time.
         * No-op on API < Q (10).
         */
        fun promoteToMediaProjection() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            serviceRef?.get()?.promoteTypeInternal()
        }

        /**
         * Demote the foreground service type back to DATA_SYNC only after screen sharing ends.
         * No-op on API < Q (10).
         */
        fun demoteFromMediaProjection() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            serviceRef?.get()?.demoteTypeInternal()
        }

        /**
         * Returns `true` if the service is currently alive in this process.
         * Used by [AdaBootReceiver] to avoid duplicate `startForegroundService` calls.
         */
        fun isRunning(): Boolean = serviceRef?.get() != null
    }

    private var wakeLock: PowerManager.WakeLock? = null
    // Holds a Wi-Fi multicast lock so Android does not filter the UDP multicast
    // packets used by libp2p mDNS peer discovery.  Without this lock, Android's
    // Wi-Fi driver silently drops multicast frames and the gossipsub mesh between
    // local devices never forms, leaving every message stuck in "sending" state.
    private var multicastLock: WifiManager.MulticastLock? = null
    private var core: AdaCore? = null
    /// NetworkCallback registered in onCreate to detect network restore events.
    /// On onAvailable we re-acquire the wake lock so Doze losing the old lock
    /// doesn't kill our polling thread after a device wake-up.
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile private var running = false
    private var serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
    private var pollingJob: kotlinx.coroutines.Job? = null
    private var wakeLockRenewalJob: kotlinx.coroutines.Job? = null

    // ── Service lifecycle ──────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        serviceRef = WeakReference(this)
        ensureChannels()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val displayName: String
        val dataDir: String

        if (intent != null) {
            displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: "Unknown"
            dataDir     = intent.getStringExtra(EXTRA_DATA_DIR) ?: filesDir.absolutePath
            // Persist for START_STICKY restarts
            runCatching { ADANotificationWorker.saveCredentials(this, displayName, dataDir) }
                .onFailure { Log.w(TAG, "Failed to persist worker credentials", it) }
        } else {
            // START_STICKY restart — read saved credentials
            val credentials = ADANotificationWorker.readCredentials(this) ?: run {
                stopSelf()
                return START_NOT_STICKY
            }
            displayName = credentials.displayName
            dataDir = credentials.dataDir
        }

        // Android 14 (API 34) enforces that startForeground() without an explicit type applies
        // ALL types declared in the manifest — including mediaProjection, which requires an active
        // MediaProjection token. Pass DATA_SYNC explicitly so the system ignores other types.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID_FOREGROUND,
                buildForegroundNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID_FOREGROUND, buildForegroundNotification())
        }

        acquireWakeLock()
        val connectionProfile = AdaConfig.normalizeConnectionProfile(
            getSharedPreferences(AdaConfig.PROFILE_PREFS, Context.MODE_PRIVATE)
                .getString(AdaConfig.KEY_CONNECTION_PROFILE, AdaConfig.DEFAULT_CONNECTION_PROFILE)
        )

        if (core == null) {
            // D1 / C2: reuse ViewModel's core if alive.
            val existing = AdaCoreHolder.instance
            if (existing != null) {
                Log.i(TAG, "onStartCommand: reusing existing AdaCore from AdaCoreHolder")
                existing.setConnectionProfile(connectionProfile)
                core = existing
            } else {
                // Process was killed (OEM kill, Doze, reboot). Try to restore the core
                // automatically using cells encrypted with the hardware-backed Keystore key
                // that was saved on the last successful unlock (no user interaction needed).
                // This is the same model as WhatsApp / Telegram: the pattern lock is a UI
                // gate; the app can still run in the background after process death.
                val identityMgr = IdentityManager(this)
                val cells = identityMgr.loadBackgroundCells()
                if (cells != null && cells.size == 32) {
                    Log.i(TAG, "onStartCommand: restoring core from background Keystore cells")
                    val instance = AdaCore.createFromPattern(cells, displayName, dataDir, connectionProfile)
                    if (instance != null) {
                        AdaCoreHolder.instance = instance
                        core = instance
                        Log.i(TAG, "onStartCommand: background core restored successfully")
                    } else {
                        Log.w(TAG, "onStartCommand: createFromPattern from background cells failed")
                    }
                }
                if (core == null) {
                    // Background cells unavailable (first install, reinstall, factory reset).
                    // Post notification asking the user to re-open the app.
                    Log.w(TAG, "onStartCommand: no authenticated core and no background cells — stopping")
                    postLockedNotification()
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }

        if (!running) {
            running = true
            startPollingThread()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy — running=$running")
        serviceRef = null
        running = false
        pollingJob?.cancel()
        pollingJob = null
        wakeLockRenewalJob?.cancel()
        wakeLockRenewalJob = null
        unregisterNetworkCallback()
        // D1: do NOT close the core here — the ViewModel owns its lifetime.
        // On START_STICKY restart the ViewModel will adopt the existing instance.
        core = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Called when the user swipes the app away from the recent-tasks list.
     * On stock Android a foreground service survives this event, but many OEM
     * firmware variants (Samsung, Xiaomi, OPPO…) kill the entire process anyway.
     *
     * If we are still running (onTaskRemoved fires before the kill) we schedule
     * a deferred restart via a 2-second AlarmManager one-shot so the service comes
     * back even after OEM process kill.  On stock Android this is a redundant but
     * harmless no-op — the service never actually died.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val credentials = ADANotificationWorker.readCredentials(this)
        if (credentials == null) {
            Log.i(TAG, "onTaskRemoved — no stored credentials, skipping restart")
            super.onTaskRemoved(rootIntent)
            return
        }

        Log.i(TAG, "onTaskRemoved — scheduling restart in 2s")
        val restartIntent = buildStartIntent(this, credentials.displayName, credentials.dataDir)
        val pi = android.app.PendingIntent.getService(
            this, 1,
            restartIntent,
            android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val am = getSystemService(android.app.AlarmManager::class.java)
        val triggerAt = android.os.SystemClock.elapsedRealtime() + 2_000L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                // No SCHEDULE_EXACT_ALARM permission — fall back to inexact alarm
                am.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        } else {
            am.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        }
        super.onTaskRemoved(rootIntent)
    }

    // ── WakeLock ───────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        // B45: re-acquire if expired (isHeld = false after 20-min timeout).
        if (wakeLock?.isHeld == true) return
        wakeLock?.release()   // release stale reference before re-creating
        wakeLock = null
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ADA::P2PKeepAlive"
        ).also {
            // acquire() without timeout is deprecated on API 29+; use explicit timeout.
            // WorkManager wakes the process every 15 min, so 20 min is a safe ceiling.
            it.acquire(20 * 60 * 1000L)
        }
        // Acquire multicast lock so Android passes UDP multicast frames (mDNS)
        // to our libp2p Rust layer.  Re-acquire on every wake to handle Doze cycles.
        if (multicastLock?.isHeld != true) {
            multicastLock?.release()
            multicastLock = null
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            multicastLock = wm.createMulticastLock("ADA::mDNS").also {
                it.setReferenceCounted(false)
                it.acquire()
            }
            Log.d(TAG, "MulticastLock acquired")
        }
    }
    // ── Network callback: re-acquire wake lock on network restore ───────────────────
    //
    // When the device exits Doze / Standby with network available, Android fires
    // onAvailable(). We take this as the signal to renew the wake lock immediately
    // (it may have expired during the Doze window) and ensure the polling thread
    // is alive so arriving messages are picked up within one 500 ms poll cycle.

    private fun registerNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Network restored — renewing wake lock and ensuring poll loop")
                acquireWakeLock()
                // Notify Rust core so iroh re-probes interfaces and triggers an
                // immediate pkarr republish instead of waiting for the backoff timer.
                core?.notifyNetworkAvailable()
                // If the polling thread died (e.g. InterruptedException during Doze),
                // restart it so events continue to be drained.
                if (!running || pollingJob?.isActive != true) {
                    running = true
                    startPollingThread()
                }
            }
        }
        cm.registerDefaultNetworkCallback(networkCallback!!)
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            runCatching {
                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            }
            networkCallback = null
        }
    }
    // ── FGS type promotion for screen sharing ─────────────────────────────

    /**
     * Called just before ScreenCapturerAndroid is created. On Android 14+ the system
     * validates that the FGS type includes MEDIA_PROJECTION before granting the token.
     */
    private fun promoteTypeInternal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID_FOREGROUND,
                buildForegroundNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
            Log.d(TAG, "FGS type promoted to DATA_SYNC|MEDIA_PROJECTION")
        }
    }

    /** Called after screen sharing stops — reverts to DATA_SYNC only. */
    private fun demoteTypeInternal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID_FOREGROUND,
                buildForegroundNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            Log.d(TAG, "FGS type demoted back to DATA_SYNC")
        }
    }

    // ── Event polling thread ───────────────────────────────────────────────

    @Synchronized
    private fun startPollingThread() {
        if (pollingJob?.isActive == true) return
        
        wakeLockRenewalJob?.cancel()
        wakeLockRenewalJob = serviceScope.launch {
            while (running) {
                kotlinx.coroutines.delay(18 * 60 * 1000L) // 18 min
                acquireWakeLock()
                Log.d(TAG, "Wake lock renewed")
            }
        }

        pollingJob = serviceScope.launch {
            com.ada.messenger.core.AdaCoreHolder.events.collect { json ->
                if (!com.ada.messenger.core.AdaCoreHolder.isViewModelActive) {
                    handleEvent(json)
                }
            }
        }
    }

    private fun drainEvents() {
        // Do NOT poll while ViewModel is alive — both share the same MutableSharedFlow channel now.
        // It's safe to receive, but we should let the UI handle UI-related concerns.
        // Actually, since it's a SharedFlow, we don't *have* to drain anymore here.
        // We'll leave this empty and rely on a coroutine for events, or just let AdaCoreHolder.events
        // be collected directly here.
    }

    private fun handleEvent(json: String) {
        val obj  = runCatching { JSONObject(json) }.getOrNull() ?: return
        val type = obj.optString("type") ?: return
        Log.d(TAG, "bg handleEvent type=$type")
        when (type) {
            "MessageReceived" -> {
                // Use display_name if known, fall back to truncated peer_id
                val senderName = obj.optString("sender_name", "").ifBlank {
                    obj.optString("sender", "?").take(8) + "…"
                }
                val text = obj.optString("text", getString(R.string.notification_default_message)).let {
                    if (it.length > 80) it.take(80) + "…" else it
                }
                val sender = obj.optString("sender", "")
                if (sender.isEmpty()) return
                val convId = "d:$sender"
                postMessageNotification(senderName, text, convId)
            }
            "IncomingCall" -> {
                val callId   = obj.optString("call_id", "")
                val peer     = obj.optString("peer", "Unknown")
                val hasVideo = obj.optBoolean("has_video")
                val label    = if (hasVideo) getString(R.string.notification_incoming_video_call) else getString(R.string.notification_incoming_audio_call)
                postIncomingCallNotification(label, peer.take(20), callId, peer, hasVideo)
            }
        }
    }

    // ── Notifications ──────────────────────────────────────────────────────

    private fun ensureChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // 1. Persistent foreground channel (silent)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ADA P2P connection", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Keeps your P2P connection alive"; setSound(null, null) }
        )

        // 2. Message notifications — IMPORTANCE_HIGH (heads-up banner), default notification sound
        val notifSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val notifAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MSG_ID, getString(R.string.notification_channel_messages_name), NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = getString(R.string.notification_channel_messages_desc)
                    setSound(notifSound, notifAttrs)
                    enableVibration(true)
                }
        )

        // 3. Incoming call notifications — IMPORTANCE_MAX (full-screen), device ringtone
        val ringtoneSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val ringtoneAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CALL_ID, getString(R.string.notification_channel_calls_name), NotificationManager.IMPORTANCE_MAX)
                .apply {
                    description = getString(R.string.notification_channel_calls_desc)
                    setSound(ringtoneSound, ringtoneAttrs)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 500)
                    // K8: Do NOT set lockscreenVisibility at channel level — let each
                    // notification control its own visibility so the user's lock-screen
                    // privacy setting is respected.
                }
        )

        // Legacy channel — kept so old installs that still reference it don't crash
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_NOTIF_ID, "ADA alerts", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Legacy alert channel" }
        )
    }

    private fun buildForegroundNotification(): Notification {
        val mainIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ADA")
            .setContentText("Secure P2P connection active")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(mainIntent)
            .setOngoing(true)
            .build()
    }

    // Cached — avoids expensive Keystore init on every notification burst
    private val appLockManager: AppLockManager by lazy { AppLockManager(applicationContext) }

    /** Post a message notification with a deep-link to the specific conversation. */
    private fun postMessageNotification(from: String, text: String, convId: String) {
        val showContent = appLockManager.notificationShowContent
        val deepLinkIntent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_CONV, convId)
            putExtra(MainActivity.EXTRA_OPEN_CONV_NAME, from)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, convId.hashCode() and 0x7FFFFFFF, // K12: mask to non-negative to avoid system confusion
            deepLinkIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_MSG_ID)
            .setContentTitle(if (showContent) from else "ADA")
            .setContentText(if (showContent) text else getString(R.string.notification_default_message))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(if (showContent) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_SECRET)
            .setAutoCancel(true)
            .build()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID_MESSAGE, notif)
    }

    private fun postNotification(id: Int, channel: String, title: String, text: String) {
        val mainIntent = PendingIntent.getActivity(
            this, id,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(this, channel)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(mainIntent)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(id, notif)
    }

    /**
     * C2 fix: post a notification when the service restarts after process kill
     * but has no authenticated core. Asks the user to unlock the app.
     */
    private fun postLockedNotification() {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = android.app.PendingIntent.getActivity(
            this, 0, openIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notif = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_locked_title))
            .setContentText(getString(R.string.notification_locked_text))
            .setSmallIcon(com.ada.messenger.R.drawable.ic_notification)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID_FOREGROUND, notif)
    }

    /**
     * Post a heads-up notification for an incoming call with a deep-link to
     * the call screen (`route = "call"`).
     */
    private fun postIncomingCallNotification(
        title: String,
        peerDisplay: String,
        callIdHex: String,
        peerIdB64: String,
        hasVideo: Boolean,
    ) {
        val callIntent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_CALL, true)
            putExtra(MainActivity.EXTRA_CALL_ID,   callIdHex)
            putExtra(MainActivity.EXTRA_CALL_PEER,  peerIdB64)
            putExtra(MainActivity.EXTRA_CALL_VIDEO, hasVideo)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, NOTIF_ID_CALL,
            callIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val showContent = AppLockManager(applicationContext).notificationShowContent
        val callRingtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val notif = NotificationCompat.Builder(this, CHANNEL_CALL_ID)
            .setContentTitle(title)
            .setContentText(if (showContent) peerDisplay else "")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pi, true)
            .setVibrate(longArrayOf(0, 500, 250, 500, 250, 500))
            .setSound(callRingtoneUri)
            .setVisibility(if (showContent) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID_CALL, notif)
    }
}
