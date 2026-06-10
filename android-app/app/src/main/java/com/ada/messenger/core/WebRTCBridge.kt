package com.ada.messenger.core

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import com.ada.messenger.service.AdaForegroundService
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * WebRTC bridge between Android's native PeerConnection API and the Rust signaling core.
 *
 * Responsibilities:
 *   1. Create a [PeerConnectionFactory] backed by the WebRTC AAR (`io.github.webrtc-sdk:android`).
 *   2. On outgoing call  → prepare local media tracks; Rust has already sent the SDP offer.
 *   3. On incoming call  → set remote offer → createAnswer → deliver answer SDP back via Rust.
 *   4. Forward locally discovered ICE candidates to remote peer through Rust signaling
 *      via [AdaCore.sendIceCandidate].
 *   5. Deliver remote ICE candidates received through Rust event loop to PeerConnection.
 *   6. Expose [setMuted] and [setSpeaker] for in-call audio control.
 *
 * All PeerConnection operations run on the IO dispatcher; UI-facing state changes are
 * propagated through [AdaCoreViewModel] state flows.
 */
class WebRTCBridge(
    private val context: Context,
    private val viewModel: AdaCoreViewModel,
) {
    companion object {
        private const val TAG = "WebRTCBridge"

        private val STUN_SERVERS = listOf(
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302",
            "stun:stun.cloudflare.com:3478",
            "stun:stun.nextcloud.com:443",
        )

        // TURN relay servers required for mobile CGNAT traversal.
        // freestun.net — Belgium/OVH, free, no registration. Port 5349 = TURN-over-TLS
        //   (looks like HTTPS to deep-packet inspection, passes most firewalls/RKN).
        // openrelay.metered.ca — kept as fallback (may be blocked on some Russian mobile networks).
        private val TURN_SERVERS = listOf(
            Triple("turns:freestun.net:5349",              "free", "free"),
            Triple("turn:freestun.net:3479",               "free", "free"),
            Triple("turn:freestun.net:3479?transport=tcp", "free", "free"),
            Triple("turn:openrelay.metered.ca:443?transport=tcp", "openrelayproject", "openrelayproject"),
            Triple("turn:openrelay.metered.ca:80",         "openrelayproject", "openrelayproject"),
        )

        /**
         * Initialise the WebRTC PeerConnectionFactory globals.
         * Must be called once — safe to call multiple times (idempotent).
         */
        @Volatile private var factoryGlobalInit = false

        @Synchronized
        fun ensureGlobalInit(context: Context) {
            if (factoryGlobalInit) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            factoryGlobalInit = true
        }

        // Process-wide shared EglBase — used by SurfaceTextureHelper, SurfaceViewRenderer,
        // DefaultVideoEncoderFactory, and DefaultVideoDecoderFactory so they all share the
        // same EGL context root (required for OES texture sharing between camera and renderers).
        @Volatile private var sharedEglBase: EglBase? = null

        @Synchronized
        fun getOrCreateSharedEglBase(): EglBase =
            sharedEglBase ?: EglBase.create().also { sharedEglBase = it }
    }

    private val parentJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + parentJob)
    private val sessionsLock = Any()

    private data class ParticipantSession(
        val peerId: String,
        val peerConnection: PeerConnection,
        var callId: String? = null,
        var hasVideo: Boolean = false,
        var remoteDescriptionSet: Boolean = false,
        val pendingRemoteIceCandidates: MutableList<IceCandidate> = mutableListOf(),
        val pendingLocalIceCandidates: MutableList<IceCandidate> = mutableListOf(),
        var isOfferer: Boolean = false,
        var iceWasConnected: Boolean = false,
        var remoteVideoTrack: VideoTrack? = null,
    )

    // ── Factory & tracks ──────────────────────────────────────────────────

    private var factory: PeerConnectionFactory? = null
    private var localAudioTrack: AudioTrack? = null
    private var localAudioSource: AudioSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var remoteVideoSink: VideoSink? = null
    private var localVideoSink: VideoSink? = null
    // AtomicBoolean so check-and-set in stopScreenShare is race-free across concurrent callers
    // (user button + MediaProjection.Callback.onStop can fire simultaneously).
    private val isScreenSharing = AtomicBoolean(false)
    private val participantSessions = LinkedHashMap<String, ParticipantSession>()
    private val peerIdByCallId = HashMap<String, String>()
    @Volatile private var selectedRemotePeerId: String? = null

    // ── Call state ────────────────────────────────────────────────────────

    @Volatile private var hasVideo: Boolean = false

    // ── Audio manager state ───────────────────────────────────────────────

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private var savedSpeakerphoneOn = false
    private var audioFocusRequest: AudioFocusRequest? = null

    // Guards against duplicate full cleanup (reset when a new top-level call starts).
    private val callEndedGuard = AtomicBoolean(false)

    // ── Public surface ────────────────────────────────────────────────────

    /** Cancel the internal coroutine scope. Call from CallManager.release() on ViewModel destruction. */
    fun dispose() {
        onCallEnded()
        parentJob.cancel()
    }

    /** Attach a surface for rendering the remote video stream. Call from UI thread. */
    fun setRemoteVideoSink(sink: VideoSink?) {
        val old = remoteVideoSink
        remoteVideoSink = sink
        synchronized(sessionsLock) {
            participantSessions.values.forEach { session ->
                session.remoteVideoTrack?.let { track ->
                    old?.let { track.removeSink(it) }
                }
            }
            selectedRemoteVideoTrack()?.let { track ->
                sink?.let { track.addSink(it) }
            }
        }
    }

    fun selectRemoteVideoPeer(peerIdB64: String?) {
        synchronized(sessionsLock) {
            val sink = remoteVideoSink
            val previousTrack = selectedRemoteVideoTrack()
            sink?.let { previousTrack?.removeSink(it) }
            selectedRemotePeerId = when {
                peerIdB64 != null && participantSessions.containsKey(peerIdB64) -> peerIdB64
                else -> participantSessions.values.firstOrNull { it.remoteVideoTrack != null }?.peerId
            }
            sink?.let { selectedRemoteVideoTrack()?.addSink(it) }
        }
    }

    /** Attach a surface for rendering the local camera preview. Call from UI thread. */
    fun setLocalVideoSink(sink: VideoSink?) {
        val old = localVideoSink
        localVideoSink = sink
        old?.let { localVideoTrack?.removeSink(it) }
        sink?.let { localVideoTrack?.addSink(it) }
    }

    suspend fun createRoomParticipantOffer(peerIdB64: String, hasVideo: Boolean): String {
        val session = prepareParticipantSession(peerIdB64, hasVideo, resetCleanupGuard = true, attachLocalTracks = true)
        return createOfferForSession(session)
    }

    private fun selectedRemoteVideoTrack(): VideoTrack? = synchronized(sessionsLock) {
        val selectedPeerId = selectedRemotePeerId
        when {
            selectedPeerId != null -> participantSessions[selectedPeerId]?.remoteVideoTrack
            else -> participantSessions.values.firstOrNull { it.remoteVideoTrack != null }?.also {
                selectedRemotePeerId = it.peerId
            }?.remoteVideoTrack
        }
    }

    private fun sessionForPeer(peerIdB64: String): ParticipantSession? =
        synchronized(sessionsLock) { participantSessions[peerIdB64] }

    private fun sessionForCall(callIdHex: String): ParticipantSession? =
        synchronized(sessionsLock) { peerIdByCallId[callIdHex]?.let { participantSessions[it] } }

    private fun anyActiveSessions(): Boolean = synchronized(sessionsLock) { participantSessions.isNotEmpty() }

    private fun prepareParticipantSession(
        peerIdB64: String,
        hasVideo: Boolean,
        resetCleanupGuard: Boolean,
        attachLocalTracks: Boolean,
    ): ParticipantSession {
        if (resetCleanupGuard) {
            callEndedGuard.set(false)
        } else if (callEndedGuard.get()) {
            callEndedGuard.set(false)
        }
        this.hasVideo = this.hasVideo || hasVideo
        initFactory()
        if (!anyActiveSessions()) {
            startAudioForCall()
        }
        ensureLocalTracks(hasVideo)
        val pc = createPeerConnection(peerIdB64)
        val session = ParticipantSession(
            peerId = peerIdB64,
            peerConnection = pc,
            hasVideo = hasVideo,
        )
        synchronized(sessionsLock) {
            participantSessions[peerIdB64] = session
            if (selectedRemotePeerId == null) {
                selectedRemotePeerId = peerIdB64
            }
        }
        if (attachLocalTracks) {
            attachLocalTracksToSession(session)
        }
        return session
    }

    private suspend fun createOfferForSession(session: ParticipantSession): String {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            if (session.hasVideo) {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            }
        }
        return suspendCancellableCoroutine { cont ->
            session.peerConnection.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    session.peerConnection.setLocalDescription(LogSdpObserver("setLocalOffer"), sdp)
                    cont.resume(sdp.description)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String) {
                    cont.resumeWithException(Exception("createOffer failed: $error"))
                }
                override fun onSetFailure(error: String) {
                    Log.e(TAG, "setLocalOffer failed: $error")
                }
            }, constraints)
        }
    }

    private fun removeParticipantSession(callIdHex: String? = null, peerIdB64: String? = null): Boolean {
        val resolvedPeerId = peerIdB64 ?: callIdHex?.let { callId ->
            synchronized(sessionsLock) { peerIdByCallId[callId] }
        }
        val removedSession = synchronized(sessionsLock) {
            val peerId = resolvedPeerId ?: return@synchronized null
            val session = participantSessions.remove(peerId)
            session?.callId?.let { peerIdByCallId.remove(it) }
            if (selectedRemotePeerId == peerId) {
                selectedRemotePeerId = participantSessions.values.firstOrNull { it.remoteVideoTrack != null }?.peerId
            }
            hasVideo = participantSessions.values.any { it.hasVideo }
            session
        } ?: return !anyActiveSessions()

        removedSession.remoteVideoTrack?.let { track ->
            remoteVideoSink?.let { track.removeSink(it) }
        }
        scope.launch {
            try {
                removedSession.peerConnection.close()
                removedSession.peerConnection.dispose()
            } catch (e: Exception) {
                Log.w(TAG, "participant cleanup failed: ${e.message}")
            }
        }
        remoteVideoSink?.let { sink ->
            selectedRemoteVideoTrack()?.addSink(sink)
        }
        return !anyActiveSessions()
    }

    /** Switch between front and back camera during an active video call. */
    fun switchCamera() {
        // Only works with Camera2Capturer; screen share capturer is ScreenCapturerAndroid.
        if (isScreenSharing.get()) return
        val capturer = videoCapturer as? Camera2Capturer ?: return
        capturer.switchCamera(null)
        Log.d(TAG, "switchCamera() called")
    }

    /**
     * Enable or disable the local video track (camera mute).
     * Disabling the track sends black frames to the remote peer instead of stopping capture,
     * which avoids the latency of re-initialising the camera when re-enabled.
     */
    fun setVideoEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
        Log.d(TAG, "setVideoEnabled($enabled)")
    }

    /**
     * Switch local video source to screen capture.
     * [resultData] is the permission intent obtained from
     * [MediaProjectionManager.createScreenCaptureIntent] result.
     */
    fun startScreenShare(resultData: Intent) {
        if (callEndedGuard.get()) return
        val src    = videoSource ?: return
        val helper = surfaceTextureHelper ?: return
        scope.launch {
            // Re-check after coroutine starts — onCallEnded() may have fired in the interim.
            if (callEndedGuard.get()) return@launch
            try {
                // Stop and discard the camera capturer first
                videoCapturer?.stopCapture()
                videoCapturer?.dispose()
                // Android 14+ requires the FGS type to include MEDIA_PROJECTION before the
                // MediaProjection token is consumed by ScreenCapturerAndroid.
                AdaForegroundService.promoteToMediaProjection()
                val screenCapturer = ScreenCapturerAndroid(resultData, object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.d(TAG, "MediaProjection stopped — restoring camera")
                        AdaForegroundService.demoteFromMediaProjection()
                        stopScreenShare()
                    }
                })
                screenCapturer.initialize(helper, context, src.capturerObserver)
                videoCapturer = screenCapturer
                isScreenSharing.set(true)
                viewModel.onScreenShareStateChanged(true)
                screenCapturer.startCapture(1280, 720, 30)
                Log.d(TAG, "Screen sharing started")
            } catch (e: Exception) {
                Log.e(TAG, "startScreenShare failed: ${e.message}")
                // Demote type since screen sharing never started
                AdaForegroundService.demoteFromMediaProjection()
                // Restore camera on failure so the call continues with video
                isScreenSharing.set(false)
                viewModel.onScreenShareStateChanged(false)
                try {
                    val enumerator = Camera2Enumerator(context)
                    val deviceName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                        ?: enumerator.deviceNames.firstOrNull()
                    if (deviceName != null) {
                        val capturer = enumerator.createCapturer(deviceName, null)
                        capturer.initialize(helper, context, src.capturerObserver)
                        capturer.startCapture(1280, 720, 30)
                        videoCapturer = capturer
                    }
                } catch (re: Exception) {
                    Log.e(TAG, "startScreenShare: camera restore also failed: ${re.message}")
                }
            }
        }
    }

    /** Switch back from screen capture to the front camera. */
    fun stopScreenShare() {
        // compareAndSet(true, false) is atomic — only one concurrent caller proceeds.
        if (!isScreenSharing.compareAndSet(true, false)) return
        // If call was ended between the CAS and here, don't touch the disposed resources.
        if (callEndedGuard.get()) return
        val src    = videoSource ?: return
        val helper = surfaceTextureHelper ?: return
        scope.launch {
            // Re-check inside coroutine: onCallEnded() may have disposed src while we waited.
            if (callEndedGuard.get()) return@launch
            try {
                videoCapturer?.stopCapture()
                videoCapturer?.dispose()
                val enumerator = Camera2Enumerator(context)
                val deviceName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                    ?: enumerator.deviceNames.firstOrNull() ?: return@launch
                val capturer = enumerator.createCapturer(deviceName, null)
                capturer.initialize(helper, context, src.capturerObserver)
                capturer.startCapture(1280, 720, 30)
                videoCapturer = capturer
                viewModel.onScreenShareStateChanged(false)
                Log.d(TAG, "Screen sharing stopped — camera restored")
                // Revert FGS type back to DATA_SYNC only
                AdaForegroundService.demoteFromMediaProjection()
            } catch (e: Exception) {
                Log.e(TAG, "stopScreenShare failed: ${e.message}")
            }
        }
    }

    /** Mute or unmute the local microphone. */
    fun setMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
        Log.d(TAG, "setMuted($muted)")
    }

    /** Switch audio output between earpiece and loudspeaker. */
    fun setSpeaker(speakerOn: Boolean) {
        setSpeakerphoneOn(speakerOn)
        Log.d(TAG, "setSpeaker($speakerOn)")
    }

    // ── Audio focus / mode ────────────────────────────────────────────────

    /**
     * Wrapper for the deprecated AudioManager.isSpeakerphoneOn setter.
     * On API 31+ uses the non-deprecated setCommunicationDevice / clearCommunicationDevice path.
     */
    private fun findCommunicationDevice(vararg preferredTypes: Int): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val devices = audioManager.availableCommunicationDevices
        for (desiredType in preferredTypes) {
            val match = devices.firstOrNull { it.type == desiredType }
            if (match != null) {
                return match
            }
        }
        return null
    }

    private fun logCommunicationRoute(prefix: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val route = audioManager.communicationDevice?.type?.toString() ?: "default"
            Log.d(TAG, "$prefix communicationDevice=$route")
        } else {
            @Suppress("DEPRECATION")
            Log.d(TAG, "$prefix speakerphone=${audioManager.isSpeakerphoneOn}")
        }
    }

    private fun setSpeakerphoneOn(speakerOn: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val preferredDevice = if (speakerOn) {
                findCommunicationDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            } else {
                val currentDevice = audioManager.communicationDevice
                if (currentDevice != null && currentDevice.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    currentDevice
                } else {
                    // Prefer an explicit non-speaker route for audio-only calls; relying on
                    // clearCommunicationDevice() leaves device choice up to OEM routing.
                    findCommunicationDevice(
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_BLE_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_USB_HEADSET,
                        AudioDeviceInfo.TYPE_HEARING_AID,
                        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                    )
                }
            }

            if (preferredDevice != null) {
                val applied = audioManager.setCommunicationDevice(preferredDevice)
                Log.d(TAG, "setCommunicationDevice(type=${preferredDevice.type}, speaker=$speakerOn) -> $applied")
                if (applied) {
                    logCommunicationRoute("Audio route updated")
                    return
                }
            }

            audioManager.clearCommunicationDevice()
            logCommunicationRoute("Audio route fallback (speaker=$speakerOn)")
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = speakerOn
        }
    }

    /** Returns whether the loudspeaker is currently the active communication device. */
    private fun getSpeakerphoneOn(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn
        }

    private fun startAudioForCall() {
        savedAudioMode = audioManager.mode
        savedSpeakerphoneOn = getSpeakerphoneOn()

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeakerphoneOn(hasVideo)  // speaker on for video, earpiece for audio
        logCommunicationRoute("startAudioForCall")

        // Ensure voice-call stream volume is audible (may be 0 on first install)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        if (audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL) == 0) {
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVol, 0)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attr)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { /* call lifecycle manages focus */ }
                .build()
            audioManager.requestAudioFocus(req)
            audioFocusRequest = req
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
        Log.d(TAG, "Audio mode → MODE_IN_COMMUNICATION, focus requested")
    }

    private fun stopAudioForCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        audioManager.mode = savedAudioMode
        setSpeakerphoneOn(savedSpeakerphoneOn)
        Log.d(TAG, "Audio mode restored")
    }

    // ── Outgoing call ─────────────────────────────────────────────────────

    /**
     * Creates a PeerConnection, local tracks, and a WebRTC offer SDP.
     * Call this BEFORE calling Rust callAudio/callVideo so the real SDP can be sent.
     * Returns the offer SDP string.
     */
    suspend fun createOfferedCall(peerIdB64: String, hasVideo: Boolean): String {
        if (anyActiveSessions()) {
            onCallEnded()
        }
        val session = prepareParticipantSession(peerIdB64, hasVideo, resetCleanupGuard = true, attachLocalTracks = true)
        val offerSdp = createOfferForSession(session)
        Log.d(TAG, "Outgoing call offer SDP ready (${offerSdp.length} chars)")
        return offerSdp
    }

    /**
     * Called after Rust returns the callId for the outgoing call.
     * Stores the callId so ICE candidates can be routed.
     * Flushes any ICE candidates buffered during the blocking callAudio() JNI call.
     */
    fun onOutgoingCallStarted(callIdHex: String, peerIdB64: String, hasVideo: Boolean) {
        val session = sessionForPeer(peerIdB64) ?: run {
            Log.w(TAG, "Outgoing call $callIdHex has no prepared session for $peerIdB64")
            return
        }
        synchronized(sessionsLock) {
            session.callId = callIdHex
            session.hasVideo = hasVideo
            session.isOfferer = true
            peerIdByCallId[callIdHex] = peerIdB64
        }
        Log.d(TAG, "Outgoing call $callIdHex → $peerIdB64 (video=$hasVideo)")
        // Flush local ICE candidates that were generated while callAudio() was blocking.
        val pending: List<IceCandidate> = synchronized(sessionsLock) {
            session.pendingLocalIceCandidates.toList().also {
                session.pendingLocalIceCandidates.clear()
            }
        }
        if (pending.isNotEmpty()) {
            Log.d(TAG, "Flushing ${pending.size} buffered local ICE candidates")
            scope.launch {
                pending.forEach { ice ->
                    viewModel.sendIceCandidate(
                        callIdHex = callIdHex,
                        peerIdB64 = peerIdB64,
                        candidate  = ice.sdp,
                        sdpMid     = ice.sdpMid ?: "",
                        sdpMlineIndex = ice.sdpMLineIndex,
                    )
                }
            }
        }
    }

    /**
     * Called when the remote peer sends back an SDP answer (via
     * [AdaCoreViewModel.handleEvent] "CallStateChanged { state: Active }").
     */
    fun onRemoteAnswerReceived(callIdHex: String, answerSdp: String) {
        if (callEndedGuard.get()) {
            Log.w(TAG, "onRemoteAnswerReceived: call already ended, ignoring stale answer")
            return
        }
        Log.d(TAG, "Remote answer received, setting remote description")
        scope.launch {
            val session = sessionForCall(callIdHex) ?: return@launch
            val pc = session.peerConnection
            val sdp = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
            suspendCancellableCoroutine<Unit> { cont ->
                pc.setRemoteDescription(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription) {}
                    override fun onSetSuccess() {
                        Log.d(TAG, "setRemoteAnswer onSetSuccess")
                        cont.resume(Unit)
                    }
                    override fun onCreateFailure(error: String) {}
                    override fun onSetFailure(error: String) {
                        Log.e(TAG, "setRemoteAnswer failed: $error")
                        cont.resumeWithException(Exception("setRemoteAnswer failed: $error"))
                    }
                }, sdp)
            }
            // Re-check: call may have ended while setRemoteDescription was suspending.
            // Proceeding on a disposed PeerConnection would crash native code.
            if (callEndedGuard.get()) return@launch
            // Flush buffered remote ICE candidates
            synchronized(sessionsLock) {
                session.remoteDescriptionSet = true
                session.pendingRemoteIceCandidates.forEach { pc.addIceCandidate(it) }
                Log.d(TAG, "Flushed ${session.pendingRemoteIceCandidates.size} buffered ICE candidates after answer")
                session.pendingRemoteIceCandidates.clear()
            }
            // Apply bitrate caps now that SDP negotiation is complete (encodings are populated)
            if (session.hasVideo) applyVideoEncoderSettings(pc)
        }
    }

    // ── Incoming call ─────────────────────────────────────────────────────

    /**
     * Called when user taps "Accept" on an incoming call.
     * Sets remote offer, generates WebRTC answer, then notifies ViewModel
     * which passes the real SDP to Rust for delivery to the caller.
     */
    suspend fun acceptIncomingCall(
        callIdHex: String,
        peerIdB64: String,
        offerSdp: String,
        hasVideo: Boolean,
    ) {
        this.hasVideo = hasVideo
        callEndedGuard.set(false)
        Log.d(TAG, "Incoming call $callIdHex from $peerIdB64 (video=$hasVideo)")

        val session = prepareParticipantSession(peerIdB64, hasVideo, resetCleanupGuard = false, attachLocalTracks = false)
        synchronized(sessionsLock) {
            session.callId = callIdHex
            session.hasVideo = hasVideo
            session.isOfferer = false
            peerIdByCallId[callIdHex] = peerIdB64
        }
        // NOTE: setupLocalTracks is called AFTER setRemoteDescription (UNIFIED_PLAN correctness:
        // addTrack must happen after setRemoteDescription so the local track is associated with
        // the existing audio transceiver from the offer, not a new duplicate one).

        val pc = session.peerConnection
        val offer = SessionDescription(SessionDescription.Type.OFFER, offerSdp)

        // setRemoteDescription → wait for callback
        suspendCancellableCoroutine<Unit> { cont ->
            pc.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {}
                override fun onSetSuccess() {
                    Log.d(TAG, "setRemoteOffer success")
                    cont.resume(Unit)
                }
                override fun onCreateFailure(error: String) {}
                override fun onSetFailure(error: String) {
                    Log.e(TAG, "setRemoteOffer failed: $error")
                    cont.resumeWithException(Exception("setRemoteOffer failed: $error"))
                }
            }, offer)
        }

        // Re-check BEFORE ICE flush: call may have ended while setRemoteDescription was suspending.
        // callEndedGuard=true happens-before peerConnection=null (both volatile in onCallEnded),
        // so if the guard is true the PC is already being torn down — abort immediately.
        if (callEndedGuard.get()) return

        // Flush any ICE candidates that arrived before remote description was set.
        // Re-read peerConnection (volatile) inside the block for defense-in-depth: if
        // onCallEnded races between the guard check above and here, peerConnection is null
        // and we skip addIceCandidate, avoiding a call on a concurrently-disposed native object.
        synchronized(sessionsLock) {
            session.remoteDescriptionSet = true
            session.pendingRemoteIceCandidates.forEach { pc.addIceCandidate(it) }
            Log.d(TAG, "Flushed ${session.pendingRemoteIceCandidates.size} buffered ICE candidates")
            session.pendingRemoteIceCandidates.clear()
        }

        // Add local tracks AFTER setRemoteDescription so they are associated with the
        // transceiver from the offer rather than creating a new unmatched transceiver.
        attachLocalTracksToSession(session)

        // createAnswer → wait for callback
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            if (hasVideo)
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        val answerSdp = suspendCancellableCoroutine<String> { cont ->
            pc.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    pc.setLocalDescription(LogSdpObserver("setLocalAnswer"), sdp)
                    Log.d(TAG, "createAnswer success")
                    cont.resume(sdp.description)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String) {
                    Log.e(TAG, "createAnswer failed: $error")
                    cont.resumeWithException(Exception("createAnswer failed: $error"))
                }
                override fun onSetFailure(error: String) {
                    Log.e(TAG, "setLocalAnswer failed: $error")
                }
            }, constraints)
        }

        // Re-check: call may have ended while createAnswer was suspending.
        if (callEndedGuard.get()) return

        // Deliver real answer SDP through Rust to caller
        viewModel.sendRustAnswer(callIdHex, peerIdB64, answerSdp)
        // Apply bitrate caps on answerer side after SDP exchange is complete
        if (session.hasVideo) applyVideoEncoderSettings(pc)
    }

    /**
     * Called on the answerer side when the offerer sends an ICE restart offer (after ICE failure).
     * Sets the new remote description and generates a fresh answer with new ICE credentials.
     */
    suspend fun acceptIceRestartOffer(callIdHex: String, peerIdB64: String, offerSdp: String) {
        val session = sessionForCall(callIdHex) ?: run {
            Log.w(TAG, "acceptIceRestartOffer: no session for $callIdHex, ignoring")
            return
        }
        val pc = session.peerConnection
        if (session.peerId != peerIdB64) {
            Log.w(TAG, "acceptIceRestartOffer: peer mismatch for $callIdHex (${session.peerId} != $peerIdB64)")
        }
        if (callEndedGuard.get()) return
        if (session.callId != callIdHex) {
            Log.w(TAG, "acceptIceRestartOffer: no PeerConnection, ignoring")
            return
        }
        Log.d(TAG, "ICE restart: applying new offer (${offerSdp.length} chars)")

        val offer = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        suspendCancellableCoroutine<Unit> { cont ->
            pc.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {}
                override fun onSetSuccess() { cont.resume(Unit) }
                override fun onCreateFailure(error: String) {}
                override fun onSetFailure(error: String) {
                    Log.e(TAG, "ICE restart setRemoteOffer failed: $error")
                    cont.resumeWithException(Exception(error))
                }
            }, offer)
        }

        // Re-check: call may have ended while setRemoteDescription was suspending.
        if (callEndedGuard.get()) return

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            if (session.hasVideo) mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        val answerSdp = suspendCancellableCoroutine<String> { cont ->
            pc.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    pc.setLocalDescription(LogSdpObserver("iceRestartAnswer"), sdp)
                    cont.resume(sdp.description)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String) {
                    Log.e(TAG, "ICE restart createAnswer failed: $error")
                    cont.resumeWithException(Exception(error))
                }
                override fun onSetFailure(error: String) {}
            }, constraints)
        }

        Log.d(TAG, "ICE restart: answer ready, signalling back")
        viewModel.sendIceRestartAnswer(callIdHex, peerIdB64, answerSdp)
    }

    /**
     * M-6: Called on the offerer side when the answerer sends back an ICE restart answer.
     * Sets the new remote description so both peers re-gather ICE candidates.
     */
    suspend fun onIceRestartAnswer(callIdHex: String, answerSdp: String) {
        val session = sessionForCall(callIdHex) ?: run {
            Log.w(TAG, "onIceRestartAnswer: no session for $callIdHex, ignoring")
            return
        }
        val pc = session.peerConnection
        if (callEndedGuard.get()) return
        Log.d(TAG, "ICE restart: applying answer (${answerSdp.length} chars)")
        val sdp = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        suspendCancellableCoroutine<Unit> { cont ->
            pc.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {}
                override fun onSetSuccess() {
                    Log.d(TAG, "ICE restart answer set successfully")
                    cont.resume(Unit)
                }
                override fun onCreateFailure(error: String) {}
                override fun onSetFailure(error: String) {
                    Log.e(TAG, "ICE restart setRemoteAnswer failed: $error")
                    cont.resumeWithException(Exception(error))
                }
            }, sdp)
        }
    }

    /** Deliver a remote ICE candidate received from the peer via Rust signaling. */
    fun onRemoteIceCandidate(callIdHex: String, peerIdB64: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        val ice = IceCandidate(sdpMid ?: "0", sdpMLineIndex ?: 0, candidate)
        Log.d(TAG, "Remote ICE candidate: ${candidate.take(40)}…")
        val session = sessionForCall(callIdHex) ?: sessionForPeer(peerIdB64) ?: return
        synchronized(sessionsLock) {
            if (session.remoteDescriptionSet) {
                scope.launch {
                    session.peerConnection.addIceCandidate(ice)
                }
            } else {
                session.pendingRemoteIceCandidates.add(ice)
                Log.d(TAG, "ICE candidate buffered for ${session.peerId} (remoteDesc not set yet), total=${session.pendingRemoteIceCandidates.size}")
            }
        }
    }

    // ── Hangup ────────────────────────────────────────────────────────────

    fun onCallEnded(callIdHex: String? = null, peerIdB64: String? = null) {
        if (callIdHex != null || peerIdB64 != null) {
            val removedLast = removeParticipantSession(callIdHex = callIdHex, peerIdB64 = peerIdB64)
            if (removedLast) {
                onCallEnded()
            }
            return
        }
        if (!callEndedGuard.compareAndSet(false, true)) {
            Log.d(TAG, "onCallEnded — already ended, skipping duplicate call")
            return
        }
        Log.d(TAG, "Call ended — releasing media resources")
        // Reset flags BEFORE releasing media so onRenegotiationNeeded / ICE callbacks see them.
        synchronized(sessionsLock) {
            participantSessions.values.forEach { session ->
                session.iceWasConnected = false
                session.isOfferer = false
                session.remoteDescriptionSet = false
                session.pendingRemoteIceCandidates.clear()
                session.pendingLocalIceCandidates.clear()
            }
            peerIdByCallId.clear()
            selectedRemotePeerId = null
        }
        // Bug A fix: Detach video sinks from live tracks SYNCHRONOUSLY before any pointer is
        // cleared. This prevents the SurfaceViewRenderer (released later in DisposableEffect)
        // from receiving callbacks after release() — which would crash native EGL code.
        synchronized(sessionsLock) {
            participantSessions.values.forEach { session ->
                session.remoteVideoTrack?.let { track ->
                    remoteVideoSink?.let { track.removeSink(it) }
                }
            }
        }
        localVideoTrack?.let { lt -> localVideoSink?.let { lt.removeSink(it) } }
        // Capture ALL resources + audio state SYNCHRONOUSLY so async teardown cannot race
        // with a new call that starts immediately and calls startAudioForCall(), which would
        // overwrite audioFocusRequest/savedAudioMode — causing us to abandon the NEW call's
        // audio focus and reset its MODE_IN_COMMUNICATION to MODE_NORMAL (one-way audio bug).
        val capturedPeerConnections = synchronized(sessionsLock) {
            participantSessions.values.map { it.peerConnection }.also { participantSessions.clear() }
        }
        val capturedAudio       = localAudioTrack.also  { localAudioTrack   = null }
        val capturedAudioSrc    = localAudioSource.also { localAudioSource  = null }
        val capturedVideo       = localVideoTrack.also  { localVideoTrack   = null }
        val capturedCapturer    = videoCapturer.also    { videoCapturer     = null }
        val capturedFactory     = factory.also          { factory           = null }
        val capturedVideoSrc    = videoSource.also      { videoSource       = null }
        val capturedSurfHelper  = surfaceTextureHelper.also { surfaceTextureHelper = null }
        isScreenSharing.set(false)
        remoteVideoSink = null
        localVideoSink  = null
        val capturedFocusReq    = audioFocusRequest.also{ audioFocusRequest = null }
        val capturedAudioMode   = savedAudioMode
        val capturedSpeakerOn   = savedSpeakerphoneOn
        scope.launch {
            try {
                capturedCapturer?.stopCapture()
                capturedCapturer?.dispose()
                capturedSurfHelper?.dispose()
                capturedVideoSrc?.dispose()
                capturedAudioSrc?.dispose()
                capturedAudio?.dispose()
                capturedVideo?.dispose()
                capturedPeerConnections.forEach { pc ->
                    pc.close()
                    pc.dispose()
                }
                capturedFactory?.dispose()
            } catch (e: Exception) {
                Log.w(TAG, "releaseMedia error: ${e.message}")
            } finally {
                // Only restore audio if no new call has started since this teardown began.
                // If callEndedGuard was reset to false by createOfferedCall/acceptIncomingCall,
                // a new call now owns the audio session — restoring old values would clobber it
                // (one-way audio bug: MODE_IN_COMMUNICATION → MODE_NORMAL).
                if (callEndedGuard.get()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        capturedFocusReq?.let { audioManager.abandonAudioFocusRequest(it) }
                    } else {
                        @Suppress("DEPRECATION")
                        audioManager.abandonAudioFocus(null)
                    }
                    audioManager.mode = capturedAudioMode
                    setSpeakerphoneOn(capturedSpeakerOn)
                    Log.d(TAG, "Audio state restored (captured) for ended call")
                } else {
                    Log.d(TAG, "Skipping audio restore — new call already owns audio session")
                }
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    // NOTE: releaseMedia() was removed. Cleanup is now done inline in onCallEnded() using
    // captured references so async teardown cannot race with a concurrently-starting new call.

    private fun initFactory() {
        if (factory != null) return
        ensureGlobalInit(context)
        val eglContext = getOrCreateSharedEglBase().eglBaseContext
        val options = PeerConnectionFactory.Options()
        val audioDeviceModule = JavaAudioDeviceModule.builder(context).createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
            .createPeerConnectionFactory()
        Log.d(TAG, "PeerConnectionFactory created (hwVideo=true)")
    }

    private fun createPeerConnection(peerIdB64: String): PeerConnection {
        val fact = factory ?: error("PeerConnectionFactory not initialized")

        val stunIceServers = STUN_SERVERS.map { url ->
            PeerConnection.IceServer.builder(url).createIceServer()
        }
        val turnIceServers = TURN_SERVERS.map { (url, username, password) ->
            PeerConnection.IceServer.builder(url)
                .setUsername(username)
                .setPassword(password)
                .createIceServer()
        }
        val iceServers = stunIceServers + turnIceServers
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // Explicitly allow both direct and relay candidates (default, but be explicit).
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            // TCP candidates help on restrictive networks where UDP is blocked.
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            // Pre-gather ICE candidates before the call starts so the first call connects
            // immediately without waiting for STUN cold-start (typically 1-2s).
            iceCandidatePoolSize = 4
        }

        val pc = fact.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "Local ICE candidate: ${candidate.sdp.take(40)}…")
                val session = sessionForPeer(peerIdB64) ?: return
                val cid = session.callId
                val pid = session.peerId
                if (cid == null) {
                    // callAudio() JNI call is still blocking — buffer until callId is known.
                    synchronized(sessionsLock) {
                        session.pendingLocalIceCandidates.add(candidate)
                    }
                    Log.d(TAG, "Local ICE candidate buffered (callId not set yet)")
                    return
                }
                scope.launch {
                    viewModel.sendIceCandidate(
                        callIdHex = cid,
                        peerIdB64 = pid,
                        candidate = candidate.sdp,
                        sdpMid = candidate.sdpMid ?: "",
                        sdpMlineIndex = candidate.sdpMLineIndex,
                    )
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}

            override fun onAddStream(stream: MediaStream) {
                // UNIFIED_PLAN: onTrack() is the authoritative track callback. onAddStream()
                // is fired by the SDK as a legacy compatibility shim and must NOT add video
                // sinks — that would double-register the sink alongside onTrack(), and
                // onCallEnded() only calls removeSink() once, leaving an orphaned reference
                // that crashes the EGL layer after SurfaceViewRenderer.release().
                // We only enable audio tracks here as a harmless safeguard.
                Log.d(TAG, "onAddStream (legacy compat) stream=${stream.id}")
                stream.audioTracks.forEach { track ->
                    track.setEnabled(true)
                }
            }

            override fun onRemoveStream(stream: MediaStream) {}

            override fun onDataChannel(channel: DataChannel) {}

            override fun onRenegotiationNeeded() {
                // ICE restart: re-create and re-signal the offer so both peers re-gather candidates.
                // Only the original offerer (outgoing call) should initiate renegotiation.
                // isOfferer is false during initial addTrack (set true only in onOutgoingCallStarted),
                // so initial setup never reaches here. We ONLY guard on isOfferer + callEndedGuard.
                // Removing the old !iceWasConnected guard so ICE can be restarted even on the
                // first failure (before ICE ever connected).
                val session = sessionForPeer(peerIdB64) ?: return
                if (!session.isOfferer) return
                if (callEndedGuard.get()) return
                val cid = session.callId ?: return
                val pid = session.peerId
                scope.launch {
                    try {
                        val liveSession = sessionForPeer(peerIdB64) ?: return@launch
                        val pc = liveSession.peerConnection
                        val constraints = MediaConstraints().apply {
                            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                            if (liveSession.hasVideo) mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                            // M-6: Explicit ICE restart flag — ensures new ICE credentials
                            // are generated even if restartIce() didn't propagate correctly.
                            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
                        }
                        val newOfferSdp = suspendCancellableCoroutine<String> { cont ->
                            pc.createOffer(object : SdpObserver {
                                override fun onCreateSuccess(sdp: SessionDescription) {
                                    pc.setLocalDescription(LogSdpObserver("iceRestartOffer"), sdp)
                                    cont.resume(sdp.description)
                                }
                                override fun onCreateFailure(e: String) {
                                    Log.e(TAG, "ICE restart createOffer failed: $e")
                                    cont.resumeWithException(Exception(e))
                                }
                                override fun onSetSuccess() {}
                                override fun onSetFailure(e: String) {}
                            }, constraints)
                        }
                        Log.d(TAG, "ICE restart offer ready (${newOfferSdp.length} chars), re-signalling")
                        // Re-check: call may have ended while createOffer was suspending.
                        if (callEndedGuard.get()) return@launch
                        viewModel.sendIceRestartOffer(cid, pid, newOfferSdp)
                    } catch (e: Exception) {
                        Log.e(TAG, "ICE restart failed: ${e.message}")
                    }
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                Log.d(TAG, "ICE signaling state: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "ICE state: $state")
                val session = sessionForPeer(peerIdB64) ?: return
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        session.iceWasConnected = true
                        Log.d(TAG, "ICE connected — audio flowing")
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        Log.w(TAG, "ICE FAILED — triggering ICE restart")
                        // Only restart ICE if the call is still active.
                        if (!callEndedGuard.get()) {
                            session.peerConnection.restartIce()
                        }
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        Log.w(TAG, "ICE DISCONNECTED — scheduling restart in 2s")
                        if (!callEndedGuard.get()) {
                            // Capture callId NOW so the delayed coroutine can verify it's still
                            // the same call after waking up.
                            val disconnectedCallId = session.callId
                            scope.launch {
                                delay(2_000L)
                                if (!callEndedGuard.get() &&
                                    session.callId == disconnectedCallId &&
                                    session.peerConnection.iceConnectionState() ==
                                        PeerConnection.IceConnectionState.DISCONNECTED) {
                                    Log.w(TAG, "ICE still DISCONNECTED after 2s — restarting ICE")
                                    session.peerConnection.restartIce()
                                }
                            }
                        }
                    }
                    PeerConnection.IceConnectionState.CLOSED -> {
                        Log.w(TAG, "ICE CLOSED")
                    }
                    else -> {}
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                Log.d(TAG, "ICE gathering: $state")
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                Log.d(TAG, "Remote track added: ${transceiver.mediaType}")
                val session = sessionForPeer(peerIdB64) ?: return
                val track = transceiver.receiver.track()
                when (transceiver.mediaType) {
                    MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO -> {
                        // Explicitly enable the remote audio track (UNIFIED_PLAN may not auto-enable it).
                        track?.setEnabled(true)
                        Log.d(TAG, "Remote audio track enabled: ${track?.id()}")
                    }
                    MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO -> {
                        (track as? VideoTrack)?.let { vt ->
                            synchronized(sessionsLock) {
                                session.remoteVideoTrack?.let { existing ->
                                    remoteVideoSink?.let { existing.removeSink(it) }
                                }
                                session.remoteVideoTrack = vt
                                if (selectedRemotePeerId == null || selectedRemotePeerId == peerIdB64) {
                                    selectedRemotePeerId = peerIdB64
                                    remoteVideoSink?.let { vt.addSink(it) }
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
        })

        Log.d(TAG, "PeerConnection created")
        return pc ?: error("PeerConnection could not be created")
    }

    private fun ensureLocalTracks(hasVideo: Boolean) {
        val fact = factory ?: return

        if (localAudioTrack == null) {
            val audioSource = fact.createAudioSource(MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("echoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("noiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("autoGainControl", "true"))
            })
            localAudioSource = audioSource
            localAudioTrack = fact.createAudioTrack("audio0", audioSource)
            localAudioTrack!!.setEnabled(true)
        }

        if (hasVideo && localVideoTrack == null) {
            val enumerator = Camera2Enumerator(context)
            val deviceName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                ?: enumerator.deviceNames.firstOrNull()
            if (deviceName != null) {
                val capturer = enumerator.createCapturer(deviceName, null)
                videoCapturer = capturer
                val source = fact.createVideoSource(false)
                videoSource = source
                surfaceTextureHelper?.dispose()
                val helper = SurfaceTextureHelper.create("CaptureThread", getOrCreateSharedEglBase().eglBaseContext)
                surfaceTextureHelper = helper
                capturer.initialize(helper, context, source.capturerObserver)
                capturer.startCapture(1280, 720, 30)
                localVideoTrack = fact.createVideoTrack("video0", source)
                localVideoSink?.let { localVideoTrack!!.addSink(it) }
            }
        }

        Log.d(TAG, "Local tracks ready (hasVideo=$hasVideo)")
    }

    private fun attachLocalTracksToSession(session: ParticipantSession) {
        localAudioTrack?.let { session.peerConnection.addTrack(it, listOf("stream0")) }
        if (session.hasVideo) {
            localVideoTrack?.let { session.peerConnection.addTrack(it, listOf("stream0")) }
        }
    }

    /**
     * Prefer VP9 sender codec and cap video bitrate.
     * - minBitrateBps = 400 kbps: prevents adaptive bitrate from crashing to unacceptable
     *   quality on older devices (A12) or congested networks.
     * - maxBitrateBps = 2 Mbps: headroom for HD quality on fast connections (S24 Ultra).
     * - degradationPreference = MAINTAIN_RESOLUTION: reduces frame rate before resolution
     *   when bandwidth is constrained, keeping video sharp rather than blurry.
     */
    private fun applyVideoEncoderSettings(pc: PeerConnection) {
        try {
            val senders = pc.senders
            for (sender in senders) {
                val track = sender.track() ?: continue
                if (track.kind() != "video") continue
                val params = sender.parameters
                params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
                val encodings = params.encodings
                if (encodings.isNotEmpty()) {
                    encodings[0].maxBitrateBps = 2_000_000   // 2 Mbps ceiling
                    encodings[0].minBitrateBps = 400_000     // 400 kbps floor
                }
                sender.parameters = params
                Log.d(TAG, "Video encoder settings applied (min=400k max=2M MAINTAIN_RESOLUTION)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "applyVideoEncoderSettings: ${e.message}")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private class LogSdpObserver(private val name: String) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {
            Log.d("SdpObserver", "$name onCreateSuccess type=${sdp.type}")
        }
        override fun onSetSuccess() {
            Log.d("SdpObserver", "$name onSetSuccess")
        }
        override fun onCreateFailure(error: String) {
            Log.e("SdpObserver", "$name onCreateFailure: $error")
        }
        override fun onSetFailure(error: String) {
            Log.e("SdpObserver", "$name onSetFailure: $error")
        }
    }
}
