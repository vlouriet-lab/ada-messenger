package com.ada.messenger.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ada.messenger.core.AdaCoreHolder
import com.ada.messenger.core.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles action buttons from incoming call notifications without opening the full UI.
 *
 * For example, pressing "Decline" directly cancels the notification and notifies
 * the remote peer via [AdaCore.declineCall] on an IO thread.
 */
class CallActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallActionReceiver"
        const val ACTION_DECLINE_CALL = "com.ada.messenger.ACTION_DECLINE_CALL"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_PEER_ID = "peer_id"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        Log.i(TAG, "CallActionReceiver received action: $action")

        if (action == ACTION_DECLINE_CALL) {
            val callIdHex = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
            val peerIdB64 = intent.getStringExtra(EXTRA_PEER_ID).orEmpty()

            Log.i(TAG, "Declining incoming call $callIdHex from peer=${peerIdB64.take(12)}")

            // Immediately cancel the notification sound and banner
            NotificationHelper.cancelCallNotification(context)

            // Send decline over network in background
            if (callIdHex.isNotEmpty() && peerIdB64.isNotEmpty()) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val core = AdaCoreHolder.instance
                        if (core != null) {
                            core.declineCall(callIdHex, peerIdB64)
                            Log.i(TAG, "Call $callIdHex successfully declined via core")
                        } else {
                            Log.w(TAG, "Cannot decline call: AdaCore instance is null")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error declining call $callIdHex: ${e.message}", e)
                    }
                }
            }
        }
    }
}
