package com.ada.messenger.core

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "CallManager"

/**
 * Manages WebRTC call lifecycle: initiating, answering, declining,
 * ICE negotiation, screen sharing, and ring timeouts.
 *
 * Single Responsibility: call state machine.
 * Delegates WebRTC media to [WebRTCBridge] and signaling to [AdaCore].
 */
class CallManager(
    private val scope: CoroutineScope,
    private val coreProvider: () -> AdaCore?,
    private val webRtcProvider: () -> WebRTCBridge?,
    private val conversationsProvider: () -> List<ConversationItem>,
    private val groupInfoProvider: () -> GroupInfo?,
    private val myPeerIdProvider: () -> String?,
    private val onCallError: (String) -> Unit,
    private val callUnavailableMessageProvider: () -> String,
) {

    // ── Observable state ──────────────────────────────────────────────────

    private val _incomingCall = MutableStateFlow<IncomingCallInfo?>(null)
    val incomingCall: StateFlow<IncomingCallInfo?> = _incomingCall.asStateFlow()

    private val _activeCall = MutableStateFlow<ActiveCallInfo?>(null)
    val activeCall: StateFlow<ActiveCallInfo?> = _activeCall.asStateFlow()

    private val _isScreenSharing = MutableStateFlow(false)
    val isScreenSharing: StateFlow<Boolean> = _isScreenSharing.asStateFlow()

    // ── Outgoing calls ───────────────────────────────────────────────────

    fun startAudioCall(peerIdB64: String, onCallId: (String?) -> Unit) {
        Log.i(TAG, "startAudioCall peer=${peerIdB64.take(12)}")
        scope.launch(Dispatchers.IO) {
            if (!isRealtimeCallAvailable()) {
                rejectUnavailableCall(onCallId)
                return@launch
            }
            withContext(Dispatchers.Main) {
                _activeCall.value = ActiveCallInfo(
                    callIdHex = "", peerIdB64 = peerIdB64,
                    hasVideo = false, isOutgoing = true, state = CallState.Initiating,
                    displayName = peerDisplayName(peerIdB64),
                )
            }
            val offerSdp = try {
                webRtcProvider()?.createOfferedCall(peerIdB64, hasVideo = false)
                    ?: run { clearCallAndNotify(onCallId); return@launch }
            } catch (e: Exception) {
                Log.e(TAG, "createOfferedCall failed: ${e.message}")
                clearCallAndNotify(onCallId); return@launch
            }
            val callId = coreProvider()?.callAudio(peerIdB64, offerSdp)
            if (callId != null) {
                webRtcProvider()?.onOutgoingCallStarted(callId, peerIdB64, hasVideo = false)
                scheduleRingTimeout(callId, peerIdB64)
                ensureWebRtcProxyRunning(callId, peerIdB64)
            } else {
                webRtcProvider()?.onCallEnded()
        val currentPeer = _activeCall.value?.peerIdB64 ?: _incomingCall.value?.peerIdB64
        if (currentPeer != null) {
            ensureWebRtcProxyStopped(currentPeer)
        }

                withContext(Dispatchers.Main) { _activeCall.value = null }
            }
            withContext(Dispatchers.Main) { onCallId(callId) }
        }
    }

    fun startVideoCall(peerIdB64: String, onCallId: (String?) -> Unit) {
        scope.launch(Dispatchers.IO) {
            if (!isRealtimeCallAvailable()) {
                rejectUnavailableCall(onCallId)
                return@launch
            }
            withContext(Dispatchers.Main) {
                _activeCall.value = ActiveCallInfo(
                    callIdHex = "", peerIdB64 = peerIdB64,
                    hasVideo = true, isOutgoing = true, state = CallState.Initiating,
                    displayName = peerDisplayName(peerIdB64),
                )
            }
            val offerSdp = try {
                webRtcProvider()?.createOfferedCall(peerIdB64, hasVideo = true)
                    ?: run { clearCallAndNotify(onCallId); return@launch }
            } catch (e: Exception) {
                Log.e(TAG, "createOfferedCall(video) failed: ${e.message}")
                clearCallAndNotify(onCallId); return@launch
            }
            val callId = coreProvider()?.callVideo(peerIdB64, offerSdp)
            if (callId != null) {
                webRtcProvider()?.onOutgoingCallStarted(callId, peerIdB64, hasVideo = true)
                scheduleRingTimeout(callId, peerIdB64)
                ensureWebRtcProxyRunning(callId, peerIdB64)
            } else {
                webRtcProvider()?.onCallEnded()
        val currentPeer = _activeCall.value?.peerIdB64 ?: _incomingCall.value?.peerIdB64
        if (currentPeer != null) {
            ensureWebRtcProxyStopped(currentPeer)
        }

                withContext(Dispatchers.Main) { _activeCall.value = null }
            }
            withContext(Dispatchers.Main) { onCallId(callId) }
        }
    }

    // ── Incoming calls ───────────────────────────────────────────────────

    fun answerCall(callIdHex: String, peerIdB64: String) {
        val info = _incomingCall.value ?: return
        // Clear incoming call immediately so the notification banner disappears
        // and the UI switches to ActiveCallView without any stale popup.
        _incomingCall.value = null
        _activeCall.value = ActiveCallInfo(
            callIdHex = callIdHex, peerIdB64 = peerIdB64,
            hasVideo = info.hasVideo, isOutgoing = false, state = CallState.Connecting,
            displayName = info.displayName,
            groupIdHex = info.groupIdHex,
            callSessionId = info.callSessionId,
            participants = info.participants,
        )
        scope.launch(Dispatchers.IO) {
            try {
                webRtcProvider()?.acceptIncomingCall(callIdHex, peerIdB64, info.offerSdp, info.hasVideo)
                                ensureWebRtcProxyRunning(callIdHex, peerIdB64)
            } catch (e: Exception) {
                Log.e(TAG, "acceptIncomingCall failed: ${e.message}")
                webRtcProvider()?.onCallEnded()
        val currentPeer = _activeCall.value?.peerIdB64 ?: _incomingCall.value?.peerIdB64
        if (currentPeer != null) {
            ensureWebRtcProxyStopped(currentPeer)
        }

                withContext(Dispatchers.Main) {
                    _activeCall.value = null
                    _incomingCall.value = null
                }
            }
        }
    }

    fun declineCall() {
        val incoming = _incomingCall.value ?: return
        webRtcProvider()?.onCallEnded()
        val currentPeer = _activeCall.value?.peerIdB64 ?: _incomingCall.value?.peerIdB64
        if (currentPeer != null) {
            ensureWebRtcProxyStopped(currentPeer)
        }

        _incomingCall.value = null
        _activeCall.value = null
        _isScreenSharing.value = false
        scope.launch(Dispatchers.IO) {
            coreProvider()?.declineCall(incoming.callIdHex, incoming.peerIdB64)
        }
    }

    fun hangup(callIdHex: String, peerIdB64: String) {
        webRtcProvider()?.onCallEnded()
        val roomSessionId = _activeCall.value?.callSessionId
        val currentPeer = _activeCall.value?.peerIdB64 ?: _incomingCall.value?.peerIdB64
        if (currentPeer != null) {
            ensureWebRtcProxyStopped(currentPeer)
        }

        _activeCall.value = null
        _incomingCall.value = null
        _isScreenSharing.value = false
        if (!roomSessionId.isNullOrBlank()) {
            scope.launch(Dispatchers.IO) {
                coreProvider()?.hangupGroupCallRoom(roomSessionId)
            }
        } else if (callIdHex.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                coreProvider()?.hangup(callIdHex, peerIdB64)
            }
        }
    }

    // ── SDP exchange ─────────────────────────────────────────────────────

    /** Called by WebRTCBridge after generating the SDP answer. */
    fun sendRustAnswer(callIdHex: String, peerIdB64: String, answerSdp: String) {
        val hasVideo = _activeCall.value?.hasVideo ?: false
        _incomingCall.value = null
        _activeCall.value = ActiveCallInfo(
            callIdHex = callIdHex, peerIdB64 = peerIdB64,
            hasVideo = hasVideo, isOutgoing = false, state = CallState.Active,
            displayName = peerDisplayName(peerIdB64),
        )
        scope.launch(Dispatchers.IO) {
            Log.d(TAG, "sendRustAnswer callId=${callIdHex.take(12)} sdp=${answerSdp.length}ch")
            coreProvider()?.answerCall(callIdHex, peerIdB64, answerSdp)
        }
    }

    // ── ICE ──────────────────────────────────────────────────────────────

    fun sendIceCandidate(callIdHex: String, peerIdB64: String, candidate: String, sdpMid: String, sdpMlineIndex: Int) {
        scope.launch(Dispatchers.IO) {
            coreProvider()?.sendIceCandidate(callIdHex, peerIdB64, candidate, sdpMid, sdpMlineIndex)
        }
    }

    fun sendIceRestartOffer(callIdHex: String, peerIdB64: String, offerSdp: String) {
        scope.launch(Dispatchers.IO) {
            coreProvider()?.sendIceRestartOffer(callIdHex, peerIdB64, offerSdp)
        }
    }

    fun sendIceRestartAnswer(callIdHex: String, peerIdB64: String, answerSdp: String) {
        scope.launch(Dispatchers.IO) {
            coreProvider()?.sendIceRestartAnswer(callIdHex, peerIdB64, answerSdp)
        }
    }

    // ── Media controls ───────────────────────────────────────────────────

    fun setMuted(muted: Boolean) { webRtcProvider()?.setMuted(muted) }
    fun setSpeaker(on: Boolean)  { webRtcProvider()?.setSpeaker(on) }
    fun switchCamera()           { webRtcProvider()?.switchCamera() }
    fun setRemoteVideoSink(sink: org.webrtc.VideoSink?) { webRtcProvider()?.setRemoteVideoSink(sink) }
    fun selectRemoteVideoPeer(peerIdB64: String?) { webRtcProvider()?.selectRemoteVideoPeer(peerIdB64) }
    fun setLocalVideoSink(sink: org.webrtc.VideoSink?)  { webRtcProvider()?.setLocalVideoSink(sink) }
    fun setVideoEnabled(enabled: Boolean) { webRtcProvider()?.setVideoEnabled(enabled) }

    fun startScreenShare(resultData: android.content.Intent) { webRtcProvider()?.startScreenShare(resultData) }
    fun stopScreenShare() { webRtcProvider()?.stopScreenShare() }

    fun onScreenShareStateChanged(sharing: Boolean) {
        _isScreenSharing.value = sharing
    }

    // ── Group calls ──────────────────────────────────────────────────────

    fun startGroupAudioCall(groupIdHex: String, onStarted: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            if (!isRealtimeCallAvailable()) {
                notifyCallUnavailable()
                return@launch
            }
            val sessionId = coreProvider()?.startGroupCall(groupIdHex, "", false)
            if (sessionId != null) {
                withContext(Dispatchers.Main) {
                    _activeCall.value = pendingGroupCallInfo(groupIdHex, sessionId, false)
                    onStarted()
                }
                dialGroupCallParticipants(groupIdHex, sessionId, false)
                refreshActiveCalls()
            }
        }
    }

    fun startGroupVideoCall(groupIdHex: String, memberCount: Int, onStarted: () -> Unit, onError: (String) -> Unit) {
        if (memberCount > AdaConfig.MAX_VIDEO_CALL_GROUP_SIZE) {
            onError("Видеоконференция доступна для групп до ${AdaConfig.MAX_VIDEO_CALL_GROUP_SIZE} участников")
            return
        }
        scope.launch(Dispatchers.IO) {
            if (!isRealtimeCallAvailable()) {
                val message = callUnavailableMessageProvider()
                onCallError(message)
                withContext(Dispatchers.Main) { onError(message) }
                return@launch
            }
            val sessionId = coreProvider()?.startGroupCall(groupIdHex, "", true)
            if (sessionId != null) {
                withContext(Dispatchers.Main) {
                    _activeCall.value = pendingGroupCallInfo(groupIdHex, sessionId, true)
                    onStarted()
                }
                dialGroupCallParticipants(groupIdHex, sessionId, true)
                refreshActiveCalls()
            }
        }
    }

    fun joinGroupCall(
        groupIdHex: String,
        sessionIdHex: String,
        hasVideo: Boolean,
        onStarted: () -> Unit,
        onError: (String) -> Unit,
    ) {
        scope.launch(Dispatchers.IO) {
            if (!isRealtimeCallAvailable()) {
                val message = callUnavailableMessageProvider()
                onCallError(message)
                withContext(Dispatchers.Main) { onError(message) }
                return@launch
            }
            val joinedSession = coreProvider()?.joinGroupCall(groupIdHex, sessionIdHex, "", hasVideo)
            if (joinedSession != null) {
                withContext(Dispatchers.Main) {
                    _activeCall.value = pendingGroupCallInfo(groupIdHex, joinedSession, hasVideo)
                    onStarted()
                }
                dialGroupCallParticipants(groupIdHex, joinedSession, hasVideo)
                refreshActiveCalls()
            } else {
                withContext(Dispatchers.Main) {
                    onError("Не удалось присоединиться к групповому звонку")
                }
            }
        }
    }

    // ── Active calls query ───────────────────────────────────────────────

    fun refreshActiveCalls() {
        scope.launch(Dispatchers.IO) {
            val json = coreProvider()?.getActiveCallsJson() ?: "[]"
            val arr = runCatching { JSONArray(json) }.getOrNull() ?: return@launch
            if (arr.length() == 0) {
                val current = _activeCall.value
                if (current?.groupIdHex != null && current.state == CallState.Initiating) {
                    return@launch
                }
                _activeCall.value = null
                return@launch
            }
            val obj = arr.getJSONObject(0)
            _activeCall.value = activeCallFromJson(
                obj = obj,
                fallbackState = CallState.fromString(obj.optString("state", "Active")),
                fallbackOutgoing = obj.optBoolean("outgoing"),
            )
        }
    }

    // ── Event handling (called by ViewModel's event router) ──────────────

    /** Handle an IncomingCall event. Auto-declines if already in a call. */
    fun onIncomingCallEvent(
        callId: String,
        peer: String,
        hasVideo: Boolean,
        offerSdp: String,
        groupIdHex: String? = null,
        sessionIdHex: String? = null,
        participants: List<String> = emptyList(),
    ) {
        if (_activeCall.value != null) {
            Log.w(TAG, "IncomingCall during active call — auto-declining $callId")
            scope.launch(Dispatchers.IO) { coreProvider()?.declineCall(callId, peer) }
            return
        }
        val displayName = when {
            !groupIdHex.isNullOrBlank() -> groupDisplayName(groupIdHex)
            else -> peerDisplayName(peer)
        }
        _incomingCall.value = IncomingCallInfo(
            callIdHex = callId,
            peerIdB64 = peer,
            hasVideo = hasVideo,
            offerSdp = offerSdp,
            displayName = displayName,
            groupIdHex = groupIdHex,
            callSessionId = sessionIdHex,
            participants = participants,
        )
    }

    fun onIceCandidateEvent(callId: String, peerId: String, candidate: String, sdpMid: String, sdpMLineIdx: Int) {
        if (candidate.isNotBlank()) {
            webRtcProvider()?.onRemoteIceCandidate(callId, peerId, candidate, sdpMid, sdpMLineIdx)
        }
    }

    fun onIceRestartOfferEvent(callId: String, peer: String, offerSdp: String) {
        val activeId = _activeCall.value?.callIdHex ?: ""
        if (offerSdp.isBlank() || callId != activeId) {
            Log.w(TAG, "IceRestartOffer ignored: callId=$callId activeId=$activeId")
            return
        }
        scope.launch(Dispatchers.IO) {
            webRtcProvider()?.acceptIceRestartOffer(callId, peer, offerSdp)
        }
    }

    /** M-6: Handle ICE restart answer from the answerer (offerer side). */
    fun onIceRestartAnswerEvent(callId: String, answerSdp: String) {
        val activeId = _activeCall.value?.callIdHex ?: ""
        if (answerSdp.isBlank() || callId != activeId) {
            Log.w(TAG, "IceRestartAnswer ignored: callId=$callId activeId=$activeId")
            return
        }
        scope.launch(Dispatchers.IO) {
            webRtcProvider()?.onIceRestartAnswer(callId, answerSdp)
        }
    }

    fun onCallStateChangedEvent(raw: org.json.JSONObject) {
        val stateStr = raw.optString("state", "")
        val state = CallState.fromString(stateStr)
        if (state == CallState.Ended || state == CallState.Failed) {
            webRtcProvider()?.onCallEnded(raw.optString("call_id").takeIf { it.isNotBlank() })
            val currentPeer = _activeCall.value?.peerIdB64 ?: _incomingCall.value?.peerIdB64
            if (currentPeer != null) {
                ensureWebRtcProxyStopped(currentPeer)
            }

            _incomingCall.value = null
            _isScreenSharing.value = false
            if (raw.optString("call_session_id").isNotBlank() || _activeCall.value?.callSessionId != null) {
                refreshActiveCalls()
            } else {
                _activeCall.value = null
            }
        } else {
            if (state == CallState.Active) {
                val answerSdp = raw.optString("answer_sdp", "")
                val callId = raw.optString("call_id")
                if (answerSdp.isNotBlank() && callId.isNotBlank()) {
                    webRtcProvider()?.onRemoteAnswerReceived(callId, answerSdp)
                }
            }
            val prevOutgoing = _activeCall.value?.isOutgoing ?: false
            _activeCall.value = activeCallFromJson(
                obj = raw,
                fallbackState = state,
                fallbackOutgoing = if (raw.has("outgoing")) raw.getBoolean("outgoing") else prevOutgoing,
            )
        }
    }

    /** Release all resources (called from ViewModel#onCleared). */
    fun release() {
        webRtcProvider()?.onCallEnded()
        val currentPeer = _activeCall.value?.peerIdB64 ?: _incomingCall.value?.peerIdB64
        if (currentPeer != null) {
            ensureWebRtcProxyStopped(currentPeer)
        }

        webRtcProvider()?.dispose()
        _activeCall.value = null
        _incomingCall.value = null
        _isScreenSharing.value = false
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private fun peerDisplayName(peerIdB64: String): String =
        conversationsProvider().firstOrNull { it.peerIdB64 == peerIdB64 }?.displayName
            ?: (peerIdB64.take(8) + "…")

    private suspend fun clearCallAndNotify(onCallId: (String?) -> Unit) {
        withContext(Dispatchers.Main) {
            _activeCall.value = null
            onCallId(null)
        }
    }

    private fun isRealtimeCallAvailable(): Boolean {
        val json = coreProvider()?.getCallAvailabilityJson() ?: return false
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return false
        return obj.optBoolean("available", false)
    }

    private suspend fun rejectUnavailableCall(onCallId: (String?) -> Unit) {
        notifyCallUnavailable()
        withContext(Dispatchers.Main) { onCallId(null) }
    }

    private suspend fun notifyCallUnavailable() {
        val message = callUnavailableMessageProvider()
        Log.i(TAG, "Call unavailable: $message")
        withContext(Dispatchers.Main) {
            _activeCall.value = null
            _incomingCall.value = null
            _isScreenSharing.value = false
            onCallError(message)
        }
    }

    private fun ensureWebRtcProxyRunning(callIdHex: String, peerIdB64: String) {
        val proxyPort = coreProvider()?.startWebRtcProxy(peerIdB64) ?: 0
        if (proxyPort > 0) {
            Log.i(TAG, "Started Iroh WebRTC Proxy on port $proxyPort")
            // Inject local proxy as a remote ICE host candidate.
            // This tricks WebRTC into tunneling traffic unconditionally into the Rust core.
            val candidate = "candidate:9999 1 udp 2122260223 127.0.0.1 $proxyPort typ host generation 0"
            webRtcProvider()?.onRemoteIceCandidate(callIdHex, peerIdB64, candidate, "0", 0)
            webRtcProvider()?.onRemoteIceCandidate(callIdHex, peerIdB64, candidate, "1", 1)
        } else {
            Log.w(TAG, "Failed to start Iroh WebRTC proxy.")
        }
    }

    private fun ensureWebRtcProxyStopped(peerIdB64: String) {
        if (peerIdB64.isBlank()) return
        coreProvider()?.stopWebRtcProxy(peerIdB64)
    }

    private fun groupDisplayName(groupIdHex: String): String =
        conversationsProvider().firstOrNull { it.groupIdHex == groupIdHex }?.displayName
            ?: "Group call"

    private fun parseParticipants(obj: org.json.JSONObject): List<String> {
        val participants = obj.optJSONArray("participants") ?: return emptyList()
        return buildList(participants.length()) {
            for (index in 0 until participants.length()) {
                val peerId = participants.optString(index)
                if (peerId.isNotBlank()) add(peerId)
            }
        }
    }

    private fun pendingGroupCallInfo(groupIdHex: String, sessionIdHex: String, hasVideo: Boolean): ActiveCallInfo {
        return ActiveCallInfo(
            callIdHex = sessionIdHex,
            peerIdB64 = "",
            hasVideo = hasVideo,
            isOutgoing = true,
            state = CallState.Initiating,
            displayName = groupDisplayName(groupIdHex),
            groupIdHex = groupIdHex,
            callSessionId = sessionIdHex,
            participants = emptyList(),
        )
    }

    private fun groupParticipantIds(groupIdHex: String): List<String> {
        val groupInfo = groupInfoProvider()?.takeIf { it.id == groupIdHex } ?: return emptyList()
        val myPeerId = myPeerIdProvider()
        return groupInfo.members
            .map { it.peerIdB64 }
            .filter { it.isNotBlank() && it != myPeerId }
            .distinct()
    }

    private suspend fun dialGroupCallParticipants(groupIdHex: String, sessionIdHex: String, hasVideo: Boolean) {
        for (peerId in groupParticipantIds(groupIdHex)) {
            startGroupParticipantCall(groupIdHex, sessionIdHex, peerId, hasVideo)
        }
    }

    private suspend fun startGroupParticipantCall(
        groupIdHex: String,
        sessionIdHex: String,
        peerIdB64: String,
        hasVideo: Boolean,
    ) {
        val offerSdp = try {
            webRtcProvider()?.createRoomParticipantOffer(peerIdB64, hasVideo)
                ?: return
        } catch (e: Exception) {
            Log.e(TAG, "group participant createOffer failed for ${peerIdB64.take(12)}: ${e.message}")
            return
        }

        val callId = coreProvider()?.callInGroupRoom(
            peerIdB64 = peerIdB64,
            offerSdp = offerSdp,
            groupIdHex = groupIdHex,
            sessionIdHex = sessionIdHex,
            hasVideo = hasVideo,
        )

        if (callId.isNullOrBlank()) {
            webRtcProvider()?.onCallEnded(peerIdB64 = peerIdB64)
            return
        }

        webRtcProvider()?.onOutgoingCallStarted(callId, peerIdB64, hasVideo)
        scheduleRingTimeout(callId, peerIdB64)
        ensureWebRtcProxyRunning(callId, peerIdB64)
    }

    private fun activeCallFromJson(
        obj: org.json.JSONObject,
        fallbackState: CallState,
        fallbackOutgoing: Boolean,
    ): ActiveCallInfo {
        val eventPeer = obj.optString("peer")
        val groupIdHex = obj.optString("group_id").takeIf { it.isNotBlank() }
        val sessionIdHex = obj.optString("call_session_id").takeIf { it.isNotBlank() }
        val participants = parseParticipants(obj)
        val displayName = when {
            !groupIdHex.isNullOrBlank() -> groupDisplayName(groupIdHex)
            eventPeer.isNotBlank() -> peerDisplayName(eventPeer)
            else -> _activeCall.value?.displayName.orEmpty()
        }
        return ActiveCallInfo(
            callIdHex = obj.optString("call_id").ifBlank { sessionIdHex.orEmpty() },
            peerIdB64 = eventPeer,
            hasVideo = obj.optBoolean("has_video"),
            isOutgoing = fallbackOutgoing,
            state = fallbackState,
            displayName = displayName,
            groupIdHex = groupIdHex,
            callSessionId = sessionIdHex,
            participants = participants,
        )
    }

    private fun scheduleRingTimeout(callId: String, peerIdB64: String) {
        scope.launch(Dispatchers.IO) {
            delay(AdaConfig.RING_TIMEOUT_MS)
            val cur = _activeCall.value
            if (cur?.callIdHex == callId && cur.state != CallState.Active) {
                Log.i(TAG, "Ring timeout for $callId — auto-hangup")
                hangup(callId, peerIdB64)
            }
        }
    }
}








