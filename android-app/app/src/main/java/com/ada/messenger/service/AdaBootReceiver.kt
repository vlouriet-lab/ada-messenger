package com.ada.messenger.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * AdaBootReceiver — restores the foreground P2P service after system events.
 *
 * Handles two triggers:
 *
 * 1. BOOT_COMPLETED / QUICKBOOT_POWERON
 *    The process is killed on reboot. Without this receiver the foreground
 *    service never restarts unless the user opens the app (WorkManager alone
 *    has a minimum 15-minute delay AND requires network). With this receiver
 *    the service starts within seconds of the boot-complete broadcast, which
 *    means incoming messages are received immediately after the device unlocks.
 *
 * 2. MY_PACKAGE_REPLACED
 *    Fired after an app update install. Ensures the service is running on the
 *    updated code immediately instead of waiting for the next WorkManager tick.
 *
 * Registration in AndroidManifest.xml (required):
 *   <receiver android:name=".service.AdaBootReceiver"
 *       android:exported="false">
 *     <intent-filter>
 *       <action android:name="android.intent.action.BOOT_COMPLETED"/>
 *       <action android:name="android.intent.action.QUICKBOOT_POWERON"/>
 *       <action android:name="android.intent.action.MY_PACKAGE_REPLACED"/>
 *     </intent-filter>
 *   </receiver>
 *
 * Required permission in AndroidManifest.xml:
 *   <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
 */
class AdaBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AdaBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",   // HTC / some OEMs
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "Received ${intent.action} — checking if service should start")

                // Only start the service if the user has previously authenticated
                // (credentials exist in encrypted SharedPreferences). If the prefs are
                // absent the user has never registered, so there is nothing to start.
                val credentials = ADANotificationWorker.readCredentials(context)
                if (credentials == null) {
                    Log.d(TAG, "No stored credentials — not starting service")
                    return
                }

                // Reschedule WorkManager periodic background polling (it is cancelled on
                // app clear-data and needs to be re-enqueued on reboot).
                ADANotificationService.schedule(context)

                // Start the foreground service.  On API 26+ we must call
                // startForegroundService() so Android gives us 5 seconds to call
                // startForeground() — the service itself calls it in onStartCommand.
                // Guard against duplicate starts: if the service is already alive
                // in this process (e.g. MY_PACKAGE_REPLACED fires while running),
                // skip the extra startForegroundService call.
                if (AdaForegroundService.isRunning()) {
                    Log.d(TAG, "Service already running — skipping start after ${intent.action}")
                    return
                }
                val fgsIntent = AdaForegroundService.buildStartIntent(
                    context,
                    credentials.displayName,
                    credentials.dataDir,
                )
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(fgsIntent)
                    } else {
                        context.startService(fgsIntent)
                    }
                    Log.i(TAG, "Foreground service start requested after ${intent.action}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start foreground service: ${e.message}")
                }
            }
        }
    }
}
