package com.ada.messenger.core

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ada.messenger.R

private const val TAG = "NotificationHelper"

/**
 * Builds and posts notifications for incoming calls and messages.
 *
 * Single Responsibility: notification construction.
 * Respects the user's "show content" privacy preference.
 */
object NotificationHelper {

    fun postIncomingCall(
        context: Context,
        callIdHex: String,
        peerDisplay: String,
        hasVideo: Boolean,
        showContent: Boolean,
    ) {
        try {
            val callIntent = Intent(context, com.ada.messenger.MainActivity::class.java).apply {
                putExtra(com.ada.messenger.MainActivity.EXTRA_OPEN_CALL, true)
                putExtra(com.ada.messenger.MainActivity.EXTRA_CALL_ID, callIdHex)
                putExtra(com.ada.messenger.MainActivity.EXTRA_CALL_PEER, peerDisplay)
                putExtra(com.ada.messenger.MainActivity.EXTRA_CALL_VIDEO, hasVideo)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context, AdaConfig.NOTIF_ID_CALL, callIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val title = if (hasVideo) context.getString(R.string.notification_incoming_video_call) else context.getString(R.string.notification_incoming_audio_call)
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val notif = NotificationCompat.Builder(context, AdaConfig.CHANNEL_CALL_ID)
                .setContentTitle(title)
                .setContentText(if (showContent) peerDisplay else "")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(pi, true)
                .setVibrate(longArrayOf(0, 500, 250, 500, 250, 500))
                .setSound(ringtoneUri)
                .setVisibility(
                    if (showContent) NotificationCompat.VISIBILITY_PUBLIC
                    else NotificationCompat.VISIBILITY_PRIVATE
                )
                .setAutoCancel(true)
                .build()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(AdaConfig.NOTIF_ID_CALL, notif)
        } catch (e: Exception) {
            Log.w(TAG, "postIncomingCall failed: ${e.message}")
        }
    }

    /** Cancel the incoming-call notification (call was answered or declined). */
    fun cancelCallNotification(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(AdaConfig.NOTIF_ID_CALL)
        } catch (e: Exception) {
            Log.w(TAG, "cancelCallNotification failed: ${e.message}")
        }
    }

    fun postMessage(
        context: Context,
        from: String,
        text: String,
        convId: String,
        showContent: Boolean,
    ) {
        try {
            val deepLinkIntent = Intent(context, com.ada.messenger.MainActivity::class.java).apply {
                putExtra(com.ada.messenger.MainActivity.EXTRA_OPEN_CONV, convId)
                putExtra(com.ada.messenger.MainActivity.EXTRA_OPEN_CONV_NAME, from)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context, convId.hashCode(), deepLinkIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notif = NotificationCompat.Builder(context, AdaConfig.CHANNEL_MSG_ID)
                .setContentTitle(if (showContent) from else "ADA")
                .setContentText(if (showContent) text else context.getString(R.string.notification_default_message))
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(
                    if (showContent) NotificationCompat.VISIBILITY_PRIVATE
                    else NotificationCompat.VISIBILITY_SECRET
                )
                .setAutoCancel(true)
                .build()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(AdaConfig.NOTIF_ID_MESSAGE, notif)
        } catch (e: Exception) {
            Log.w(TAG, "postMessage failed: ${e.message}")
        }
    }
}
