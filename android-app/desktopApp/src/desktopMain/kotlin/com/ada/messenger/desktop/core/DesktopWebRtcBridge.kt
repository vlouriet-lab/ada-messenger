package com.ada.messenger.desktop.core

import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceConnectionState
import dev.onvoid.webrtc.RTCIceGatheringState
import dev.onvoid.webrtc.RTCIceServer
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCRtpTransceiver
import dev.onvoid.webrtc.RTCRtpSender
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.RTCSignalingState
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.MediaDevices
import dev.onvoid.webrtc.media.MediaStream
import dev.onvoid.webrtc.media.MediaStreamTrack
import dev.onvoid.webrtc.media.audio.AudioDevice
import dev.onvoid.webrtc.media.audio.AudioDeviceModule
import dev.onvoid.webrtc.media.audio.AudioOptions
import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.audio.AudioTrackSource
import dev.onvoid.webrtc.media.video.I420Buffer
import dev.onvoid.webrtc.media.video.VideoCaptureCapability
import dev.onvoid.webrtc.media.video.VideoDesktopSource
import dev.onvoid.webrtc.media.video.VideoDevice
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoFrameBuffer
import dev.onvoid.webrtc.media.video.VideoDeviceSource
import dev.onvoid.webrtc.media.video.VideoTrack
import dev.onvoid.webrtc.media.video.VideoTrackSink
import dev.onvoid.webrtc.media.video.desktop.DesktopSource
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer
import java.awt.image.BufferedImage
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DesktopWebRtcBridge(
    private val coreProvider: () -> DesktopAdaCore?,
    private val onError: (String) -> Unit,
    private val onVideoStateChanged: (DesktopVideoCallUiState) -> Unit = {},
) {
    companion object {
        private val logger = Logger.getLogger(DesktopWebRtcBridge::class.java.name)
        private const val LOCAL_FRAME_INTERVAL_NS = 125_000_000L
        private const val REMOTE_FRAME_INTERVAL_NS = 125_000_000L
        private const val LOCAL_RENDER_WIDTH = 240
        private const val REMOTE_RENDER_WIDTH = 720
        private const val SCREEN_SHARE_FRAME_RATE = 12
        private const val SCREEN_SHARE_MAX_WIDTH = 1920
        private const val SCREEN_SHARE_MAX_HEIGHT = 1080

        private val STUN_SERVERS = listOf(
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302",
            "stun:stun.cloudflare.com:3478",
            "stun:stun.nextcloud.com:443",
        )

        private val TURN_SERVERS = listOf(
            Triple("turns:freestun.net:5349", "free", "free"),
            Triple("turn:freestun.net:3479", "free", "free"),
            Triple("turn:freestun.net:3479?transport=tcp", "free", "free"),
            Triple("turn:openrelay.metered.ca:443?transport=tcp", "openrelayproject", "openrelayproject"),
            Triple("turn:openrelay.metered.ca:80", "openrelayproject", "openrelayproject"),
        )
    }

    private enum class LocalVideoSourceKind {
        CAMERA,
        SCREEN,
    }

    private data class ActiveSession(
        val peerId: String,
        val peerConnection: RTCPeerConnection,
        var callId: String? = null,
        var hasVideo: Boolean = false,
        var isOfferer: Boolean = false,
        var remoteDescriptionSet: Boolean = false,
        var localTracksAttached: Boolean = false,
        var audioSender: RTCRtpSender? = null,
        var videoSender: RTCRtpSender? = null,
        val pendingRemoteIceCandidates: MutableList<RTCIceCandidate> = mutableListOf(),
        val pendingLocalIceCandidates: MutableList<RTCIceCandidate> = mutableListOf(),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionLock = Any()

    private var audioDeviceModule: AudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var localAudioSource: AudioTrackSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var localCameraSource: VideoDeviceSource? = null
    private var localDesktopSource: VideoDesktopSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var currentSession: ActiveSession? = null
    private var videoUiState = DesktopVideoCallUiState()
    private var lastLocalFrameUpdateNs = 0L
    private var lastRemoteFrameUpdateNs = 0L
    private var currentVideoDeviceIndex = 0
    private var currentAudioOutputDescriptor: String? = null
    private var currentScreenSourceTitle: String? = null
    private var currentVideoSourceKind = LocalVideoSourceKind.CAMERA
    private var videoEnabledBeforeScreenShare = true
    private var availableVideoDevices: List<VideoDevice> = emptyList()
    private var loggedFirstLocalFrame = false
    private var loggedFirstRemoteFrame = false

    private val localVideoSink = object : VideoTrackSink {
        override fun onVideoFrame(frame: VideoFrame) {
            publishVideoFrame(frame, isRemote = false)
        }
    }

    private val remoteVideoSink = object : VideoTrackSink {
        override fun onVideoFrame(frame: VideoFrame) {
            publishVideoFrame(frame, isRemote = true)
        }
    }

    fun dispose() {
        logInfo("desktop webrtc bridge dispose requested")
        onCallEnded()
        scope.cancel()
    }

    fun setLocalAudioEnabled(enabled: Boolean): Boolean {
        val track = localAudioTrack ?: return false
        track.setEnabled(enabled)
        runCatching { audioDeviceModule?.setMicrophoneMute(!enabled) }
        updateVideoState {
            copy(localAudioEnabled = enabled)
        }
        return true
    }

    fun setLocalVideoEnabled(enabled: Boolean): Boolean {
        val track = localVideoTrack ?: return false
        track.setEnabled(enabled)
        if (currentVideoSourceKind == LocalVideoSourceKind.SCREEN) {
            videoEnabledBeforeScreenShare = enabled
        }
        updateVideoState {
            copy(
                localVideoEnabled = enabled,
                localFrame = if (enabled) localFrame else null,
            )
        }
        return true
    }

    fun cycleAudioOutputDevice(): Boolean {
        val module = audioDeviceModule ?: return false
        val devices = runCatching { module.getPlayoutDevices() }.getOrElse { emptyList() }
        if (devices.size < 2) {
            refreshDeviceState()
            return false
        }

        val currentIndex = devices.indexOfFirst { it.descriptor == currentAudioOutputDescriptor }
            .takeIf { it >= 0 }
            ?: 0
        val nextDevice = devices[(currentIndex + 1) % devices.size]
        module.setPlayoutDevice(nextDevice)
        currentAudioOutputDescriptor = nextDevice.descriptor
        refreshDeviceState()
        return true
    }

    fun cycleVideoDevice(): Boolean {
        if (currentVideoSourceKind != LocalVideoSourceKind.CAMERA) {
            return false
        }
        val devices = loadVideoDevices()
        if (devices.size < 2) {
            refreshDeviceState()
            return false
        }
        val nextIndex = (currentVideoDeviceIndex + 1) % devices.size
        return switchToCameraSource(nextIndex, videoUiState.localVideoEnabled)
    }

    fun toggleScreenShare(): Boolean {
        return if (videoUiState.isScreenSharing) {
            stopScreenShare()
        } else {
            startScreenShare()
        }
    }

    suspend fun startOutgoingCall(peerIdB64: String, hasVideo: Boolean): DesktopCallStartResult {
        return try {
            logInfo("desktop outgoing call start peer=${shortPeerId(peerIdB64)} video=$hasVideo")
            onCallEnded()
            val session = prepareSession(peerIdB64, hasVideo)
            attachLocalTracks(session)
            val offerSdp = createOffer(session, iceRestart = false)
            logDebug("desktop outgoing offer ready peer=${shortPeerId(peerIdB64)} sdpLen=${offerSdp.length}")
            val core = requireCore()
            val callStart = if (hasVideo) {
                core.callVideoDetailed(peerIdB64, offerSdp)
            } else {
                core.callAudioDetailed(peerIdB64, offerSdp)
            }
            val callId = callStart.callId

            if (callId.isNullOrBlank()) {
                onCallEnded()
                logWarn("desktop outgoing call start rejected peer=${shortPeerId(peerIdB64)} reason=${callStart.error}")
                DesktopCallStartResult(
                    error = callStart.error ?: "Не удалось отправить приглашение на звонок.",
                )
            } else {
                synchronized(sessionLock) {
                    currentSession?.takeIf { it.peerId == peerIdB64 }?.apply {
                        this.callId = callId
                        this.hasVideo = hasVideo
                        this.isOfferer = true
                    }
                }
                flushBufferedLocalIce(callId, peerIdB64)
                ensureWebRtcProxyRunning(callId, peerIdB64, hasVideo)
                logInfo("desktop outgoing call active id=$callId peer=${shortPeerId(peerIdB64)} video=$hasVideo")
                DesktopCallStartResult(callId = callId)
            }
        } catch (error: Throwable) {
            logWarn("desktop outgoing call failed peer=${shortPeerId(peerIdB64)}", error)
            onCallEnded()
            DesktopCallStartResult(error = error.message ?: "Не удалось подготовить desktop media engine для звонка.")
        }
    }

    suspend fun answerIncomingCall(
        callIdHex: String,
        peerIdB64: String,
        offerSdp: String,
        hasVideo: Boolean,
    ): DesktopCallControlResult {
        return try {
            if (offerSdp.isBlank()) {
                DesktopCallControlResult(success = false, error = "Входящий звонок не содержит SDP offer.")
            } else {
                logInfo(
                    "desktop incoming answer start id=$callIdHex peer=${shortPeerId(peerIdB64)} video=$hasVideo offerLen=${offerSdp.length}",
                )
                onCallEnded()
                val session = prepareSession(peerIdB64, hasVideo)
                synchronized(sessionLock) {
                    currentSession?.takeIf { it.peerId == peerIdB64 }?.apply {
                        callId = callIdHex
                        this.hasVideo = hasVideo
                        isOfferer = false
                    }
                }

                setRemoteDescription(session.peerConnection, RTCSessionDescription(RTCSdpType.OFFER, offerSdp))
                synchronized(sessionLock) {
                    currentSession?.takeIf { it.peerId == peerIdB64 }?.apply {
                        remoteDescriptionSet = true
                        pendingRemoteIceCandidates.forEach(session.peerConnection::addIceCandidate)
                        pendingRemoteIceCandidates.clear()
                    }
                }

                attachLocalTracks(session)
                val answerSdp = createAnswer(session)
                logDebug("desktop incoming answer prepared id=$callIdHex peer=${shortPeerId(peerIdB64)} sdpLen=${answerSdp.length}")
                val answerResult = requireCore().answerCallDetailed(callIdHex, peerIdB64, answerSdp)
                if (!answerResult.success) {
                    onCallEnded()
                    logWarn("desktop incoming answer rejected id=$callIdHex reason=${answerResult.error}")
                    DesktopCallControlResult(
                        success = false,
                        error = answerResult.error ?: "Не удалось отправить SDP answer.",
                    )
                } else {
                    ensureWebRtcProxyRunning(callIdHex, peerIdB64, hasVideo)
                    logInfo("desktop incoming answer accepted id=$callIdHex peer=${shortPeerId(peerIdB64)} video=$hasVideo")
                    DesktopCallControlResult(success = true)
                }
            }
        } catch (error: Throwable) {
            logWarn("desktop incoming call answer failed id=$callIdHex peer=${shortPeerId(peerIdB64)}", error)
            onCallEnded()
            DesktopCallControlResult(success = false, error = error.message ?: "Не удалось принять звонок на desktop.")
        }
    }

    suspend fun onRemoteAnswerReceived(callIdHex: String, answerSdp: String) {
        val session = session(callIdHex = callIdHex) ?: return
        if (answerSdp.isBlank()) return

        runCatching {
            setRemoteDescription(session.peerConnection, RTCSessionDescription(RTCSdpType.ANSWER, answerSdp))
            synchronized(sessionLock) {
                currentSession?.takeIf { it.callId == callIdHex }?.apply {
                    remoteDescriptionSet = true
                    pendingRemoteIceCandidates.forEach(session.peerConnection::addIceCandidate)
                    pendingRemoteIceCandidates.clear()
                }
            }
        }.onFailure { error ->
            logWarn("desktop remote answer apply failed id=$callIdHex", error)
            onError(error.message ?: "Не удалось применить remote answer на desktop.")
        }
    }

    fun onRemoteIceCandidate(
        callIdHex: String,
        peerIdB64: String,
        candidate: String,
        sdpMid: String?,
        sdpMLineIndex: Int,
    ) {
        if (candidate.isBlank()) return
        val iceCandidate = RTCIceCandidate(sdpMid ?: "0", sdpMLineIndex, candidate)
        val session = session(callIdHex = callIdHex, peerIdB64 = peerIdB64) ?: return
        synchronized(sessionLock) {
            currentSession?.takeIf { it.peerId == session.peerId }?.apply {
                if (remoteDescriptionSet) {
                    peerConnection.addIceCandidate(iceCandidate)
                } else {
                    pendingRemoteIceCandidates.add(iceCandidate)
                }
            }
        }
    }

    suspend fun acceptIceRestartOffer(callIdHex: String, peerIdB64: String, offerSdp: String) {
        val session = session(callIdHex = callIdHex, peerIdB64 = peerIdB64) ?: return
        if (offerSdp.isBlank()) return

        runCatching {
            setRemoteDescription(session.peerConnection, RTCSessionDescription(RTCSdpType.OFFER, offerSdp))
            val answerSdp = createAnswer(session)
            requireCore().sendIceRestartAnswer(callIdHex, peerIdB64, answerSdp)
        }.onFailure { error ->
            logWarn("desktop ICE restart offer failed id=$callIdHex peer=${shortPeerId(peerIdB64)}", error)
            onError(error.message ?: "Не удалось обработать ICE restart offer.")
        }
    }

    suspend fun onIceRestartAnswer(callIdHex: String, answerSdp: String) {
        val session = session(callIdHex = callIdHex) ?: return
        if (answerSdp.isBlank()) return

        runCatching {
            setRemoteDescription(session.peerConnection, RTCSessionDescription(RTCSdpType.ANSWER, answerSdp))
        }.onFailure { error ->
            logWarn("desktop ICE restart answer failed id=$callIdHex", error)
            onError(error.message ?: "Не удалось применить ICE restart answer.")
        }
    }

    fun onCallEnded(callIdHex: String? = null, peerIdB64: String? = null) {
        val sessionToRelease = synchronized(sessionLock) {
            currentSession?.takeIf {
                (callIdHex == null || it.callId == callIdHex) &&
                    (peerIdB64 == null || it.peerId == peerIdB64)
            }?.also {
                currentSession = null
            }
        } ?: run {
            if (callIdHex == null && peerIdB64 == null) {
                releaseSharedMedia()
            }
            return
        }

        runCatching {
            requireCoreOrNull()?.stopWebRtcProxy(sessionToRelease.peerId)
        }
        runCatching {
            remoteVideoTrack?.removeSink(remoteVideoSink)
            remoteVideoTrack = null
            sessionToRelease.peerConnection.close()
        }.onFailure { error ->
            logWarn("desktop peer connection cleanup failed", error)
        }
        releaseSharedMedia()
    }

    private fun prepareSession(peerIdB64: String, hasVideo: Boolean): ActiveSession {
        initFactory()
        ensureLocalTracks(hasVideo)
        val peerConnection = createPeerConnection(peerIdB64)
        return ActiveSession(
            peerId = peerIdB64,
            peerConnection = peerConnection,
            hasVideo = hasVideo,
        ).also { session ->
            synchronized(sessionLock) {
                currentSession = session
            }
        }
    }

    private fun initFactory() {
        if (factory != null) return

        val audioModule = AudioDeviceModule().also { module ->
            val captureDevice = runCatching { MediaDevices.getDefaultAudioCaptureDevice() }.getOrNull()
            captureDevice?.let(module::setRecordingDevice)
            runCatching { MediaDevices.getDefaultAudioRenderDevice() }.getOrNull()?.let { device ->
                module.setPlayoutDevice(device)
                currentAudioOutputDescriptor = device.descriptor
            }
            module.initRecording()
            module.initPlayout()
            runCatching { module.setMicrophoneMute(false) }
            logInfo(
                "desktop media factory init: capture=${captureDevice?.name ?: "none"} playoutDevices=${runCatching { module.getPlayoutDevices().size }.getOrDefault(0)} deferredAudioStart=true",
            )
        }

        audioDeviceModule = audioModule
        factory = PeerConnectionFactory(audioModule)
        refreshDeviceState()
    }

    private fun createPeerConnection(peerIdB64: String): RTCPeerConnection {
        val config = RTCConfiguration().apply {
            iceServers.addAll(STUN_SERVERS.map { url ->
                RTCIceServer().apply { urls.add(url) }
            })
            iceServers.addAll(TURN_SERVERS.map { (url, username, password) ->
                RTCIceServer().apply {
                    urls.add(url)
                    this.username = username
                    this.password = password
                }
            })
        }

        return checkNotNull(factory).createPeerConnection(config, object : PeerConnectionObserver {
            override fun onIceCandidate(candidate: RTCIceCandidate) {
                val session = session(peerIdB64 = peerIdB64) ?: return
                val callId = session.callId
                if (callId.isNullOrBlank()) {
                    synchronized(sessionLock) {
                        currentSession?.takeIf { it.peerId == peerIdB64 }?.pendingLocalIceCandidates?.add(candidate)
                    }
                    logDebug("desktop local ICE buffered peer=${shortPeerId(peerIdB64)} mid=${candidate.sdpMid} mline=${candidate.sdpMLineIndex}")
                    return
                }

                logDebug("desktop local ICE send id=$callId peer=${shortPeerId(peerIdB64)} mid=${candidate.sdpMid} mline=${candidate.sdpMLineIndex}")
                scope.launch {
                    requireCoreOrNull()?.sendIceCandidate(
                        callIdHex = callId,
                        peerIdB64 = peerIdB64,
                        candidate = candidate.sdp,
                        sdpMid = candidate.sdpMid ?: "",
                        sdpMlineIndex = candidate.sdpMLineIndex,
                    )
                }
            }

            override fun onSignalingChange(state: RTCSignalingState) {
                logDebug("desktop signaling state peer=${shortPeerId(peerIdB64)} state=$state")
            }

            override fun onConnectionChange(state: RTCPeerConnectionState) {
                logInfo("desktop connection state peer=${shortPeerId(peerIdB64)} state=$state")
            }

            override fun onIceGatheringChange(state: RTCIceGatheringState) {
                logDebug("desktop ICE gathering state peer=${shortPeerId(peerIdB64)} state=$state")
            }

            override fun onIceConnectionChange(state: RTCIceConnectionState) {
                val session = session(peerIdB64 = peerIdB64) ?: return
                logInfo("desktop ICE connection state peer=${shortPeerId(peerIdB64)} state=$state")
                when (state) {
                    RTCIceConnectionState.FAILED -> {
                        logWarn("desktop ICE failed; restarting peer=${shortPeerId(peerIdB64)}")
                        session.peerConnection.restartIce()
                    }
                    RTCIceConnectionState.DISCONNECTED -> scope.launch {
                        delay(2_000L)
                        val liveSession = session(peerIdB64 = peerIdB64)
                        if (liveSession?.peerConnection?.iceConnectionState == RTCIceConnectionState.DISCONNECTED) {
                            logWarn("desktop ICE still disconnected; restarting peer=${shortPeerId(peerIdB64)}")
                            liveSession.peerConnection.restartIce()
                        }
                    }

                    else -> Unit
                }
            }

            override fun onAddStream(stream: MediaStream) {
                stream.audioTracks.forEach { track -> track.setEnabled(true) }
                logDebug("desktop remote stream added peer=${shortPeerId(peerIdB64)} audioTracks=${stream.audioTracks.size} videoTracks=${stream.videoTracks.size}")
            }

            override fun onRenegotiationNeeded() {
                val session = session(peerIdB64 = peerIdB64) ?: return
                if (!session.isOfferer) return
                val callId = session.callId ?: return
                scope.launch {
                    runCatching {
                        val offerSdp = createOffer(session, iceRestart = true)
                        requireCore().sendIceRestartOffer(callId, peerIdB64, offerSdp)
                    }.onFailure { error ->
                        logWarn("desktop renegotiation failed id=$callId peer=${shortPeerId(peerIdB64)}", error)
                    }
                }
            }

            override fun onTrack(transceiver: RTCRtpTransceiver) {
                val track = transceiver.receiver.track ?: return
                logInfo(
                    "desktop remote track peer=${shortPeerId(peerIdB64)} kind=${track.kind} mid=${transceiver.mid ?: "?"}",
                )
                if (track.kind == MediaStreamTrack.AUDIO_TRACK_KIND || track.kind == MediaStreamTrack.VIDEO_TRACK_KIND) {
                    track.setEnabled(true)
                }
                if (track.kind == MediaStreamTrack.VIDEO_TRACK_KIND) {
                    val videoTrack = track as? VideoTrack ?: return
                    synchronized(sessionLock) {
                        remoteVideoTrack?.removeSink(remoteVideoSink)
                        remoteVideoTrack = videoTrack
                        remoteVideoTrack?.addSink(remoteVideoSink)
                    }
                    updateVideoState {
                        copy(
                            remoteVideoAvailable = true,
                            remoteFrame = remoteFrame,
                        )
                    }
                }
            }
        })
    }

    private fun ensureLocalTracks(hasVideo: Boolean) {
        val activeFactory = checkNotNull(factory)

        if (localAudioTrack == null) {
            val audioOptions = AudioOptions().apply {
                echoCancellation = true
                autoGainControl = true
                noiseSuppression = true
            }
            val audioSource = activeFactory.createAudioSource(audioOptions)
            localAudioSource = audioSource
            localAudioTrack = activeFactory.createAudioTrack("audio0", audioSource).also { track ->
                track.setEnabled(true)
            }
            runCatching { audioDeviceModule?.setMicrophoneMute(false) }
            logInfo("desktop local audio track created and unmuted")
            updateVideoState {
                copy(localAudioEnabled = true)
            }
        }

        if (hasVideo && localVideoTrack == null) {
            if (!switchToCameraSource(currentVideoDeviceIndex, forceEnabled = true, activeFactory = activeFactory)) {
                logWarn("desktop video track creation failed: no camera available")
                throw IllegalStateException("На desktop не найдена камера для видеозвонка.")
            }
        }

        refreshDeviceState()
    }

    private fun attachLocalTracks(session: ActiveSession) {
        if (session.localTracksAttached) return
        session.audioSender = localAudioTrack?.let { session.peerConnection.addTrack(it, listOf("stream0")) }
        if (session.hasVideo) {
            session.videoSender = localVideoTrack?.let { session.peerConnection.addTrack(it, listOf("stream0")) }
        }
        session.localTracksAttached = true
        logInfo(
            "desktop local tracks attached peer=${shortPeerId(session.peerId)} audio=${session.audioSender != null} video=${session.videoSender != null}",
        )
    }

    private suspend fun createOffer(session: ActiveSession, iceRestart: Boolean): String =
        suspendCancellableCoroutine { continuation ->
            session.peerConnection.createOffer(
                RTCOfferOptions().apply { this.iceRestart = iceRestart },
                object : CreateSessionDescriptionObserver {
                    override fun onSuccess(description: RTCSessionDescription) {
                        scope.launch {
                            runCatching {
                                setLocalDescription(session.peerConnection, description)
                            }.onSuccess {
                                continuation.resume(description.sdp)
                            }.onFailure { error ->
                                continuation.resumeWithException(error)
                            }
                        }
                    }

                    override fun onFailure(error: String) {
                        continuation.resumeWithException(IllegalStateException(error))
                    }
                },
            )
        }

    private suspend fun createAnswer(session: ActiveSession): String =
        suspendCancellableCoroutine { continuation ->
            session.peerConnection.createAnswer(
                RTCAnswerOptions(),
                object : CreateSessionDescriptionObserver {
                    override fun onSuccess(description: RTCSessionDescription) {
                        scope.launch {
                            runCatching {
                                setLocalDescription(session.peerConnection, description)
                            }.onSuccess {
                                continuation.resume(description.sdp)
                            }.onFailure { error ->
                                continuation.resumeWithException(error)
                            }
                        }
                    }

                    override fun onFailure(error: String) {
                        continuation.resumeWithException(IllegalStateException(error))
                    }
                },
            )
        }

    private suspend fun setLocalDescription(
        peerConnection: RTCPeerConnection,
        description: RTCSessionDescription,
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        peerConnection.setLocalDescription(
            description,
            object : SetSessionDescriptionObserver {
                override fun onSuccess() {
                    continuation.resume(Unit)
                }

                override fun onFailure(error: String) {
                    continuation.resumeWithException(IllegalStateException(error))
                }
            },
        )
    }

    private suspend fun setRemoteDescription(
        peerConnection: RTCPeerConnection,
        description: RTCSessionDescription,
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        peerConnection.setRemoteDescription(
            description,
            object : SetSessionDescriptionObserver {
                override fun onSuccess() {
                    continuation.resume(Unit)
                }

                override fun onFailure(error: String) {
                    continuation.resumeWithException(IllegalStateException(error))
                }
            },
        )
    }

    private fun ensureWebRtcProxyRunning(callIdHex: String, peerIdB64: String, hasVideo: Boolean) {
        val proxyPort = requireCoreOrNull()?.startWebRtcProxy(peerIdB64) ?: 0
        if (proxyPort <= 0) {
            logWarn("desktop webrtc proxy failed peer=${shortPeerId(peerIdB64)} id=$callIdHex")
            return
        }

        val candidate = "candidate:9999 1 udp 2122260223 127.0.0.1 $proxyPort typ host generation 0"
        logInfo("desktop webrtc proxy started peer=${shortPeerId(peerIdB64)} id=$callIdHex port=$proxyPort video=$hasVideo")
        onRemoteIceCandidate(callIdHex, peerIdB64, candidate, "0", 0)
        if (hasVideo) {
            onRemoteIceCandidate(callIdHex, peerIdB64, candidate, "1", 1)
        }
    }

    private fun flushBufferedLocalIce(callIdHex: String, peerIdB64: String) {
        val candidates = synchronized(sessionLock) {
            currentSession?.takeIf { it.callId == callIdHex && it.peerId == peerIdB64 }
                ?.pendingLocalIceCandidates
                ?.toList()
                ?.also { currentSession?.pendingLocalIceCandidates?.clear() }
                .orEmpty()
        }
        if (candidates.isEmpty()) return

        logDebug("desktop flushing ${candidates.size} buffered ICE candidates id=$callIdHex peer=${shortPeerId(peerIdB64)}")

        scope.launch {
            val core = requireCoreOrNull() ?: return@launch
            candidates.forEach { candidate ->
                core.sendIceCandidate(
                    callIdHex = callIdHex,
                    peerIdB64 = peerIdB64,
                    candidate = candidate.sdp,
                    sdpMid = candidate.sdpMid ?: "",
                    sdpMlineIndex = candidate.sdpMLineIndex,
                )
            }
        }
    }

    private fun releaseSharedMedia() {
        runCatching {
            logInfo("desktop releasing shared media state")
            remoteVideoTrack?.removeSink(remoteVideoSink)
            remoteVideoTrack = null
            localVideoTrack?.removeSink(localVideoSink)
            localVideoTrack?.dispose()
            localVideoTrack = null
            localCameraSource?.stop()
            localCameraSource?.dispose()
            localCameraSource = null
            localDesktopSource?.stop()
            localDesktopSource?.dispose()
            localDesktopSource = null
            localAudioTrack?.dispose()
            localAudioTrack = null
            localAudioSource = null
            audioDeviceModule?.run {
                runCatching { stopRecording() }
                runCatching { stopPlayout() }
                dispose()
            }
            audioDeviceModule = null
            factory?.dispose()
            factory = null
            lastLocalFrameUpdateNs = 0L
            lastRemoteFrameUpdateNs = 0L
            currentAudioOutputDescriptor = null
            currentScreenSourceTitle = null
            currentVideoSourceKind = LocalVideoSourceKind.CAMERA
            currentVideoDeviceIndex = 0
            videoEnabledBeforeScreenShare = true
            availableVideoDevices = emptyList()
            loggedFirstLocalFrame = false
            loggedFirstRemoteFrame = false
            videoUiState = DesktopVideoCallUiState(updatedAtNanos = System.nanoTime())
            onVideoStateChanged(videoUiState)
        }.onFailure { error ->
            logWarn("desktop shared media cleanup failed", error)
        }
    }

    private fun publishVideoFrame(frame: VideoFrame, isRemote: Boolean) {
        val now = System.nanoTime()
        val intervalNs = if (isRemote) REMOTE_FRAME_INTERVAL_NS else LOCAL_FRAME_INTERVAL_NS
        val lastUpdate = if (isRemote) lastRemoteFrameUpdateNs else lastLocalFrameUpdateNs
        if (now - lastUpdate < intervalNs) {
            frame.release()
            return
        }

        var scaledBuffer: VideoFrameBuffer? = null
        var i420Buffer: I420Buffer? = null
        try {
            val sourceWidth = frame.buffer.width
            val sourceHeight = frame.buffer.height
            val targetWidth = if (isRemote) REMOTE_RENDER_WIDTH else LOCAL_RENDER_WIDTH
            val (scaledWidth, scaledHeight) = scaledDimensions(sourceWidth, sourceHeight, targetWidth)
            scaledBuffer = frame.buffer.cropAndScale(
                0,
                0,
                sourceWidth,
                sourceHeight,
                scaledWidth,
                scaledHeight,
            )
            i420Buffer = scaledBuffer.toI420()
            val bufferedImage = i420ToBufferedImage(i420Buffer, frame.rotation)
            if (isRemote) {
                if (!loggedFirstRemoteFrame) {
                    loggedFirstRemoteFrame = true
                    logInfo("desktop first remote frame received ${bufferedImage.width}x${bufferedImage.height} rotation=${frame.rotation}")
                }
                lastRemoteFrameUpdateNs = now
                updateVideoState {
                    copy(
                        remoteFrame = bufferedImage,
                        remoteVideoAvailable = true,
                    )
                }
            } else {
                if (!loggedFirstLocalFrame) {
                    loggedFirstLocalFrame = true
                    logInfo("desktop first local frame received ${bufferedImage.width}x${bufferedImage.height} rotation=${frame.rotation}")
                }
                lastLocalFrameUpdateNs = now
                updateVideoState {
                    copy(
                        localFrame = if (localVideoEnabled) bufferedImage else null,
                        localVideoAvailable = true,
                    )
                }
            }
        } catch (error: Throwable) {
            logWarn("desktop video frame conversion failed", error)
        } finally {
            if (i420Buffer != null && i420Buffer !== scaledBuffer) {
                i420Buffer.release()
            }
            scaledBuffer?.release()
            frame.release()
        }
    }

    private fun scaledDimensions(width: Int, height: Int, targetWidth: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) {
            return 2 to 2
        }
        val scaledWidth = minOf(width, targetWidth).coerceAtLeast(2).let { value ->
            if (value % 2 == 0) value else value - 1
        }
        val scaledHeight = ((height.toDouble() * scaledWidth.toDouble()) / width.toDouble())
            .roundToInt()
            .coerceAtLeast(2)
            .let { value -> if (value % 2 == 0) value else value - 1 }
        return scaledWidth to scaledHeight
    }

    private fun i420ToBufferedImage(buffer: I420Buffer, rotation: Int): BufferedImage {
        val width = buffer.width
        val height = buffer.height
        val yPlane = buffer.dataY
        val uPlane = buffer.dataU
        val vPlane = buffer.dataV
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val rowPixels = IntArray(width)

        for (y in 0 until height) {
            val yOffset = y * buffer.strideY
            val uvOffset = (y / 2) * buffer.strideU
            for (x in 0 until width) {
                val yValue = yPlane.get(yOffset + x).toInt() and 0xFF
                val uvIndex = uvOffset + (x / 2)
                val uValue = uPlane.get(uvIndex).toInt() and 0xFF
                val vValue = vPlane.get(uvIndex).toInt() and 0xFF
                rowPixels[x] = yuvToArgb(yValue, uValue, vValue)
            }
            image.setRGB(0, y, width, 1, rowPixels, 0, width)
        }

        return rotateIfNeeded(image, rotation)
    }

    private fun rotateIfNeeded(image: BufferedImage, rotation: Int): BufferedImage {
        val normalized = ((rotation % 360) + 360) % 360
        return when (normalized) {
            90 -> {
                val rotated = BufferedImage(image.height, image.width, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until image.height) {
                    for (x in 0 until image.width) {
                        rotated.setRGB(image.height - 1 - y, x, image.getRGB(x, y))
                    }
                }
                rotated
            }

            180 -> {
                val rotated = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until image.height) {
                    for (x in 0 until image.width) {
                        rotated.setRGB(image.width - 1 - x, image.height - 1 - y, image.getRGB(x, y))
                    }
                }
                rotated
            }

            270 -> {
                val rotated = BufferedImage(image.height, image.width, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until image.height) {
                    for (x in 0 until image.width) {
                        rotated.setRGB(y, image.width - 1 - x, image.getRGB(x, y))
                    }
                }
                rotated
            }

            else -> image
        }
    }

    private fun yuvToArgb(yValue: Int, uValue: Int, vValue: Int): Int {
        val y = (yValue - 16).coerceAtLeast(0)
        val u = uValue - 128
        val v = vValue - 128
        val r = clampColor((298 * y + 409 * v + 128) shr 8)
        val g = clampColor((298 * y - 100 * u - 208 * v + 128) shr 8)
        val b = clampColor((298 * y + 516 * u + 128) shr 8)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun clampColor(value: Int): Int = value.coerceIn(0, 255)

    private fun updateVideoState(transform: DesktopVideoCallUiState.() -> DesktopVideoCallUiState) {
        videoUiState = transform(videoUiState).copy(updatedAtNanos = System.nanoTime())
        onVideoStateChanged(videoUiState)
    }

    private fun cameraPreferenceRank(device: VideoDevice): Int {
        val name = device.name.lowercase()
        return when {
            name.contains("broadcast") ||
                name.contains("virtual") ||
                name.contains("obs") ||
                name.contains("snap") ||
                name.contains("manycam") ||
                name.contains("ndi") -> 2
            name.contains("integrated") || name.contains("webcam") -> 0
            else -> 1
        }
    }

    private fun sortVideoDevices(devices: List<VideoDevice>): List<VideoDevice> {
        return devices.sortedWith(compareBy<VideoDevice>({ cameraPreferenceRank(it) }, { it.name }))
    }

    private fun pickVideoCapability(device: VideoDevice): VideoCaptureCapability? {
        val capabilities = runCatching { MediaDevices.getVideoCaptureCapabilities(device).orEmpty() }
            .getOrElse { emptyList() }
        if (capabilities.isEmpty()) return null

        val safeCapabilities = capabilities.filter { capability ->
            capability.width <= 1280 && capability.height <= 720 && capability.frameRate <= 30
        }

        return (safeCapabilities.ifEmpty { capabilities.filter { it.frameRate <= 30 } }
            .ifEmpty { capabilities })
            .minWithOrNull(
                compareBy<VideoCaptureCapability> {
                    abs((it.width * it.height) - (1280 * 720))
                }.thenBy {
                    abs(it.frameRate - 24)
                },
            )
    }

    private fun loadVideoDevices(): List<VideoDevice> {
        availableVideoDevices = sortVideoDevices(
            runCatching { MediaDevices.getVideoCaptureDevices() }.getOrElse { emptyList() },
        )
        if (availableVideoDevices.isNotEmpty()) {
            currentVideoDeviceIndex = currentVideoDeviceIndex.coerceIn(0, availableVideoDevices.lastIndex)
        } else {
            currentVideoDeviceIndex = 0
        }
        logDebug("desktop video devices refreshed count=${availableVideoDevices.size}")
        return availableVideoDevices
    }

    private fun availableScreenSources(): List<DesktopSource> {
        val capturer = runCatching { ScreenCapturer() }.getOrNull() ?: return emptyList()
        return try {
            capturer.getDesktopSources().orEmpty()
        } catch (_: Throwable) {
            emptyList()
        } finally {
            runCatching { capturer.dispose() }
        }
    }

    private fun currentAudioOutputDevice(devices: List<AudioDevice>): AudioDevice? {
        return devices.firstOrNull { it.descriptor == currentAudioOutputDescriptor }
            ?: devices.firstOrNull()?.also { currentAudioOutputDescriptor = it.descriptor }
    }

    private fun refreshDeviceState() {
        val devices = loadVideoDevices()
        val screens = availableScreenSources()
        val playoutDevices = runCatching { audioDeviceModule?.getPlayoutDevices().orEmpty() }.getOrElse { emptyList() }
        val currentAudioDevice = currentAudioOutputDevice(playoutDevices)
        val activeVideoLabel = when (currentVideoSourceKind) {
            LocalVideoSourceKind.SCREEN -> currentScreenSourceTitle ?: screens.firstOrNull()?.title ?: "Экран"
            LocalVideoSourceKind.CAMERA -> devices.getOrNull(currentVideoDeviceIndex)?.name
        }

        updateVideoState {
            copy(
                canSwitchCamera = currentVideoSourceKind == LocalVideoSourceKind.CAMERA && devices.size > 1,
                canScreenShare = screens.isNotEmpty(),
                isScreenSharing = currentVideoSourceKind == LocalVideoSourceKind.SCREEN,
                activeVideoSourceLabel = activeVideoLabel,
                activeAudioOutputLabel = currentAudioDevice?.name,
                audioOutputCount = playoutDevices.size,
            )
        }
    }

    private fun replaceLocalVideoTrack(newTrack: VideoTrack) {
        val session = synchronized(sessionLock) { currentSession }
        val oldTrack = localVideoTrack
        oldTrack?.removeSink(localVideoSink)
        newTrack.addSink(localVideoSink)
        if (session?.localTracksAttached == true) {
            if (session.videoSender != null) {
                session.videoSender?.replaceTrack(newTrack)
            } else {
                session.videoSender = session.peerConnection.addTrack(newTrack, listOf("stream0"))
            }
        }
        localVideoTrack = newTrack
        oldTrack?.dispose()
    }

    private fun switchToCameraSource(
        deviceIndex: Int,
        forceEnabled: Boolean,
        activeFactory: PeerConnectionFactory = checkNotNull(factory),
    ): Boolean = runCatching {
        val devices = loadVideoDevices()
        val device = devices.getOrNull(deviceIndex) ?: return false
        val selectedCapability = pickVideoCapability(device)
        val cameraSource = VideoDeviceSource().apply {
            setVideoCaptureDevice(device)
            selectedCapability?.let(::setVideoCaptureCapability)
            start()
        }
        logInfo("desktop camera source started device=${device.name} capability=${selectedCapability ?: "default"}")
        val newTrack = activeFactory.createVideoTrack("video0", cameraSource).also { track ->
            track.setEnabled(forceEnabled)
        }
        val previousCameraSource = localCameraSource
        val previousDesktopSource = localDesktopSource
        replaceLocalVideoTrack(newTrack)
        localCameraSource = cameraSource
        localDesktopSource = null
        currentVideoDeviceIndex = deviceIndex
        currentVideoSourceKind = LocalVideoSourceKind.CAMERA
        currentScreenSourceTitle = null
        previousDesktopSource?.stop()
        previousDesktopSource?.dispose()
        if (previousCameraSource !== cameraSource) {
            previousCameraSource?.stop()
            previousCameraSource?.dispose()
        }
        updateVideoState {
            copy(
                localVideoAvailable = true,
                localVideoEnabled = forceEnabled,
                isScreenSharing = false,
                activeVideoSourceLabel = device.name,
                canSwitchCamera = devices.size > 1,
                canScreenShare = canScreenShare || availableScreenSources().isNotEmpty(),
                localFrame = if (forceEnabled) localFrame else null,
            )
        }
        refreshDeviceState()
        true
    }.getOrElse { error ->
        logWarn("desktop camera switch failed", error)
        onError(error.message ?: "Не удалось переключить desktop camera source.")
        false
    }

    private fun startScreenShare(): Boolean = runCatching {
        val activeFactory = factory ?: return false
        if (localVideoTrack == null) {
            onError("Screen share доступен только во время активного видеозвонка.")
            return false
        }
        val screenSource = availableScreenSources().firstOrNull() ?: return false
        val desktopSource = VideoDesktopSource().apply {
            setSourceId(screenSource.id, true)
            setFrameRate(SCREEN_SHARE_FRAME_RATE)
            setMaxFrameSize(SCREEN_SHARE_MAX_WIDTH, SCREEN_SHARE_MAX_HEIGHT)
            start()
        }
        logInfo("desktop screen share started source=${screenSource.title.ifBlank { "Экран" }}")
        val newTrack = activeFactory.createVideoTrack("screen0", desktopSource).also { track ->
            track.setEnabled(true)
        }
        val previousCameraSource = localCameraSource
        val previousDesktopSource = localDesktopSource
        videoEnabledBeforeScreenShare = videoUiState.localVideoEnabled
        replaceLocalVideoTrack(newTrack)
        localDesktopSource = desktopSource
        localCameraSource = null
        currentVideoSourceKind = LocalVideoSourceKind.SCREEN
        currentScreenSourceTitle = screenSource.title.ifBlank { "Экран" }
        previousCameraSource?.stop()
        previousCameraSource?.dispose()
        if (previousDesktopSource !== desktopSource) {
            previousDesktopSource?.stop()
            previousDesktopSource?.dispose()
        }
        updateVideoState {
            copy(
                localVideoAvailable = true,
                localVideoEnabled = true,
                isScreenSharing = true,
                activeVideoSourceLabel = currentScreenSourceTitle,
                canSwitchCamera = false,
                canScreenShare = true,
                localFrame = null,
            )
        }
        refreshDeviceState()
        true
    }.getOrElse { error ->
        logWarn("desktop screen share failed", error)
        onError(error.message ?: "Не удалось запустить desktop screen share.")
        false
    }

    private fun stopScreenShare(): Boolean {
        if (currentVideoSourceKind != LocalVideoSourceKind.SCREEN) {
            return false
        }
        if (availableVideoDevices.isNotEmpty() || loadVideoDevices().isNotEmpty()) {
            return switchToCameraSource(currentVideoDeviceIndex, videoEnabledBeforeScreenShare)
        }

        localDesktopSource?.stop()
        localDesktopSource?.dispose()
        localDesktopSource = null
        currentVideoSourceKind = LocalVideoSourceKind.CAMERA
        currentScreenSourceTitle = null
        updateVideoState {
            copy(
                isScreenSharing = false,
                localVideoEnabled = false,
                activeVideoSourceLabel = null,
                localFrame = null,
            )
        }
        refreshDeviceState()
        return true
    }

    private fun session(callIdHex: String? = null, peerIdB64: String? = null): ActiveSession? = synchronized(sessionLock) {
        currentSession?.takeIf {
            (callIdHex == null || it.callId == callIdHex) &&
                (peerIdB64 == null || it.peerId == peerIdB64)
        }
    }

    private fun requireCore(): DesktopAdaCore =
        requireNotNull(requireCoreOrNull()) { "Desktop runtime ещё не инициализирован." }

    private fun requireCoreOrNull(): DesktopAdaCore? = coreProvider()

    private fun shortPeerId(peerId: String): String =
        if (peerId.length <= 12) peerId else peerId.take(8) + "…" + peerId.takeLast(4)

    private fun logInfo(message: String) {
        logger.info(message)
        DesktopCallLog.info(message)
    }

    private fun logDebug(message: String) {
        logger.fine(message)
        DesktopCallLog.debug(message)
    }

    private fun logWarn(message: String, error: Throwable? = null) {
        if (error == null) {
            logger.warning(message)
        } else {
            logger.log(Level.WARNING, message, error)
        }
        DesktopCallLog.warn(message, error)
    }
}