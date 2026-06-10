package com.ada.messenger.ui.screens

import android.Manifest
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Rational
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.automirrored.filled.StopScreenShare
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ada.messenger.core.ActiveCallInfo
import com.ada.messenger.core.AdaCoreViewModel
import com.ada.messenger.core.CallState
import com.ada.messenger.core.IncomingCallInfo
import com.ada.messenger.core.WebRTCBridge
import com.ada.messenger.R
import kotlinx.coroutines.delay
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import com.ada.messenger.core.CallLogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.draw.blur
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.core.content.ContextCompat
import android.media.projection.MediaProjectionManager
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

// ─────────────────────────────────────────────────────────────────────────────
// CallScreen — shown when there is an incoming or active call
// Navigation destination: "call"
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun CallScreen(
    viewModel: AdaCoreViewModel,
    onFinish: () -> Unit,
) {
    val incomingCall by viewModel.incomingCall.collectAsState()
    val activeCall   by viewModel.activeCall.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity

    // ── Keep screen on for entire call (incoming + active) ────────────────
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ── Ringtone + vibration for incoming call ────────────────────────────
    DisposableEffect(incomingCall != null) {
        if (incomingCall == null) return@DisposableEffect onDispose {}
        // Use MediaPlayer instead of Ringtone so isLooping works on all API levels.
        // Ringtone.isLooping was only added in API 28; MediaPlayer.isLooping works from API 1.
        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val mp = try {
            android.media.MediaPlayer().apply {
                setDataSource(context, ringtoneUri)
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            android.util.Log.w("CallScreen", "Failed to start ringtone: ${e.message}", e)
            null
        }

        // Vibration pattern: 0ms delay, 500ms on, 250ms off, repeat from index 0
        val vibrationPattern = longArrayOf(0, 500, 250)
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(VibratorManager::class.java))?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(
                VibrationEffect.createWaveform(vibrationPattern, 0) // repeat from index 0
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(vibrationPattern, 0)
        }

        onDispose {
            mp?.stop()
            mp?.release()
            vibrator?.cancel()
        }
    }

    // ── Ringback tone for outgoing call (гудки) ──────────────────────────
    // Play while activeCall.state is Initiating or Ringing (not yet Active).
    val isRinging = activeCall?.state?.let { it == CallState.Initiating || it == CallState.Ringing } == true
    DisposableEffect(isRinging) {
        if (!isRinging) return@DisposableEffect onDispose {}
        val toneGen = try {
            ToneGenerator(AudioManager.STREAM_VOICE_CALL, ToneGenerator.MAX_VOLUME)
        } catch (e: Exception) { null }
        toneGen?.startTone(ToneGenerator.TONE_SUP_RINGTONE, -1)
        onDispose {
            toneGen?.stopTone()
            toneGen?.release()
        }
    }

    // Track whether a call was ever active so we don't auto-dismiss on cold entry
    var hadCallActivity by remember { mutableStateOf(incomingCall != null || activeCall != null) }
    LaunchedEffect(incomingCall, activeCall) {
        if (incomingCall != null || activeCall != null) hadCallActivity = true
    }

    // auto-dismiss only after a call has ended (not on cold open from nav bar)
    LaunchedEffect(incomingCall, activeCall, hadCallActivity) {
        if (hadCallActivity && incomingCall == null && activeCall == null) {
            onFinish()
        }
    }

    // If opened from nav bar with no call — show call history
    if (!hadCallActivity && incomingCall == null && activeCall == null) {
        val callHistory by viewModel.callHistory.collectAsState()
        LaunchedEffect(Unit) { viewModel.refreshCallHistory() }
        CallHistoryScreen(history = callHistory, onBack = onFinish)
        return
    }

    // Permission launcher for microphone — needed before accepting a call
    var pendingAnswerCallId by remember { mutableStateOf<String?>(null) }
    var pendingAnswerPeerId by remember { mutableStateOf<String?>(null) }
    var pendingAnswerHasVideo by remember { mutableStateOf(false) }

    // Launcher that requests both MIC and CAMERA together for video calls
    val multiPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val micOk = results[Manifest.permission.RECORD_AUDIO] == true
        val camOk = results[Manifest.permission.CAMERA] ?: true  // true if not requested
        if (micOk && camOk) {
            val cid = pendingAnswerCallId
            val pid = pendingAnswerPeerId
            if (cid != null && pid != null) viewModel.answerCall(cid, pid)
        }
        pendingAnswerCallId  = null
        pendingAnswerPeerId  = null
        pendingAnswerHasVideo = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        when {
            // Active call takes priority — once user taps Accept, _activeCall is set immediately
            // so we switch to ActiveCallView without waiting for SDP exchange to complete.
            activeCall != null -> ActiveCallView(
                info = activeCall!!,
                viewModel = viewModel,
                onHangup = { viewModel.hangup(activeCall!!.callIdHex, activeCall!!.peerIdB64) },
                onMuteChanged = { muted -> viewModel.setMuted(muted) },
                onSpeakerChanged = { speaker -> viewModel.setSpeaker(speaker) },
                onSwitchCamera = { viewModel.switchCamera() },
                onVideoEnabled = { enabled -> viewModel.setVideoEnabled(enabled) },
            )
            incomingCall != null -> IncomingCallView(
                info = incomingCall!!,
                onAnswer = {
                    val incoming = incomingCall ?: return@IncomingCallView
                    val neededPerms = buildList {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                            != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
                        if (incoming.hasVideo && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                            != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.CAMERA)
                    }
                    if (neededPerms.isEmpty()) {
                        viewModel.answerCall(incoming.callIdHex, incoming.peerIdB64)
                    } else {
                        pendingAnswerCallId   = incoming.callIdHex
                        pendingAnswerPeerId   = incoming.peerIdB64
                        pendingAnswerHasVideo = incoming.hasVideo
                        multiPermLauncher.launch(neededPerms.toTypedArray())
                    }
                },
                onDecline = { viewModel.declineCall() },
            )
            else -> CircularProgressIndicator()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Incoming call UI
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun IncomingCallView(
    info: IncomingCallInfo,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.padding(32.dp)
    ) {
        // V-13: Pulsating ripple rings around avatar
        val infiniteTransition = rememberInfiniteTransition(label = "ripple")
        val ring1 = infiniteTransition.animateFloat(
            initialValue = 0.4f, targetValue = 0f,
            animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Restart),
            label = "ring1",
        )
        val ring2 = infiniteTransition.animateFloat(
            initialValue = 0.3f, targetValue = 0f,
            animationSpec = infiniteRepeatable(tween(1500, delayMillis = 500), RepeatMode.Restart),
            label = "ring2",
        )
        val ringColor = MaterialTheme.colorScheme.primary

        Box(contentAlignment = Alignment.Center) {
            // Outer ring
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .border(
                        width = 3.dp,
                        color = ringColor.copy(alpha = ring2.value),
                        shape = CircleShape,
                    ),
            )
            // Inner ring
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .border(
                        width = 3.dp,
                        color = ringColor.copy(alpha = ring1.value),
                        shape = CircleShape,
                    ),
            )
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        Text(
            text = info.displayName.ifEmpty { info.peerIdB64.take(8) + "…" },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = if (info.hasVideo) stringResource(R.string.notification_incoming_video_call) else stringResource(R.string.call_screen_incoming_audio_call),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CallActionButton(
                icon = Icons.Default.CallEnd,
                tint = MaterialTheme.colorScheme.error,
                label = stringResource(R.string.call_action_reject),
                onClick = onDecline
            )
            CallActionButton(
                icon = if (info.hasVideo) Icons.Default.Videocam else Icons.Default.Call,
                tint = MaterialTheme.colorScheme.primary,
                label = stringResource(R.string.call_action_answer),
                onClick = onAnswer
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Active call UI
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Single shared EglBase for the lifetime of the process.
 * Sharing one EglBase context between both renderers avoids context-share conflicts.
 */
private val sharedEglBase: EglBase by lazy { WebRTCBridge.getOrCreateSharedEglBase() }

@Composable
private fun ActiveCallView(
    info: ActiveCallInfo,
    viewModel: AdaCoreViewModel,
    onHangup: () -> Unit,
    onMuteChanged: (Boolean) -> Unit,
    onSpeakerChanged: (Boolean) -> Unit,
    onSwitchCamera: () -> Unit,
    onVideoEnabled: (Boolean) -> Unit,
) {
    var isMuted        by remember { mutableStateOf(false) }
    var isSpeaker      by remember { mutableStateOf(info.hasVideo) }  // speaker on for video
    var isVideoEnabled by remember { mutableStateOf(true) }
    val isScreenSharing by viewModel.isScreenSharing.collectAsState()
    val myPeerId by viewModel.myPeerId.collectAsState()
    var elapsed        by remember { mutableStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    val groupCallLink = remember(info.groupIdHex, info.callSessionId, info.hasVideo) {
        if (!info.groupIdHex.isNullOrBlank() && !info.callSessionId.isNullOrBlank()) {
            viewModel.buildGroupCallDeepLink(info.groupIdHex, info.callSessionId, info.hasVideo)
        } else {
            null
        }
    }
    val remoteParticipants = remember(info.participants, myPeerId, info.peerIdB64) {
        buildList {
            info.participants
                .distinct()
                .filterNot { it == myPeerId }
                .forEach(::add)
            if (info.peerIdB64.isNotBlank() && info.peerIdB64 != myPeerId && info.peerIdB64 !in this) {
                add(info.peerIdB64)
            }
        }
    }
    var selectedRemotePeerId by remember(info.callSessionId, remoteParticipants) {
        mutableStateOf(remoteParticipants.firstOrNull())
    }
    LaunchedEffect(info.callSessionId, remoteParticipants) {
        if (selectedRemotePeerId !in remoteParticipants) {
            selectedRemotePeerId = remoteParticipants.firstOrNull()
        }
    }
    LaunchedEffect(info.callSessionId, selectedRemotePeerId, remoteParticipants) {
        viewModel.selectRemoteVideoPeer(selectedRemotePeerId ?: remoteParticipants.firstOrNull())
    }
    // Auto-hide controls after 4 s of inactivity; timer resets each time they become visible
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            delay(4_000L)
            controlsVisible = false
        }
    }

    val activity = LocalContext.current as? Activity
    val context = LocalContext.current

    // V-16: Auto-enter PiP when user swipes away during an active video call (Android 12+)
    if (info.hasVideo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        DisposableEffect(Unit) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .setAutoEnterEnabled(true)
                .build()
            activity?.setPictureInPictureParams(params)
            onDispose {
                // Disable auto-PiP when leaving call screen
                try {
                    val resetParams = PictureInPictureParams.Builder()
                        .setAutoEnterEnabled(false)
                        .build()
                    activity?.setPictureInPictureParams(resetParams)
                } catch (_: Exception) {}
            }
        }
    }

    // Screen share launcher — requests MediaProjection permission from the user
    val projectionManager = context.getSystemService(MediaProjectionManager::class.java)
    val screenShareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            viewModel.startScreenShare(data)
        }
    }

    val isActive = info.state == CallState.Active
    LaunchedEffect(info.state) {
        elapsed = 0L
        if (!isActive) return@LaunchedEffect
        while (true) { delay(1000L); elapsed++ }
    }

    if (info.hasVideo) {
        // ── Video call layout ─────────────────────────────────────────────
        // Remote video fills the screen; self-view is a small PiP in the corner.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { controlsVisible = !controlsVisible }
        ) {

            // Remote video — full screen
            // V-15: Blur background when overlay is visible
            val remoteRenderer = remember {
                mutableStateOf<SurfaceViewRenderer?>(null)
            }
            AndroidView(
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).also { r ->
                        r.layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        r.init(sharedEglBase.eglBaseContext, null)
                        r.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        remoteRenderer.value = r
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (controlsVisible && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                            Modifier.blur(8.dp)
                        else Modifier
                    )
            )
            // Connect / disconnect remote sink with call lifecycle
            DisposableEffect(Unit) {
                onDispose {
                    viewModel.setRemoteVideoSink(null)
                    remoteRenderer.value?.release()
                }
            }
            LaunchedEffect(remoteRenderer.value) {
                remoteRenderer.value?.let { viewModel.setRemoteVideoSink(it) }
            }

            // Self-view (local camera) — PiP bottom-end corner
            val localRenderer = remember { mutableStateOf<SurfaceViewRenderer?>(null) }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 120.dp)
                    .size(width = 100.dp, height = 140.dp)
                    .background(Color.Black, shape = MaterialTheme.shapes.medium)
            ) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).also { r ->
                            r.layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            r.init(sharedEglBase.eglBaseContext, null)
                            r.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                            r.setMirror(true)   // selfie mirror
                            localRenderer.value = r
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            DisposableEffect(Unit) {
                onDispose {
                    viewModel.setLocalVideoSink(null)
                    localRenderer.value?.release()
                }
            }
            LaunchedEffect(localRenderer.value) {
                localRenderer.value?.let { viewModel.setLocalVideoSink(it) }
            }

            // Status overlay (top)
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 48.dp)
                ) {
                    Text(
                        text = info.displayName.ifEmpty { info.peerIdB64.take(8) + "…" },
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = when (info.state) {
                            CallState.Initiating, CallState.Connecting -> stringResource(R.string.call_state_connecting)
                            CallState.Ringing -> stringResource(R.string.call_state_ringing)
                            else -> formatElapsed(elapsed)
                        },
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Light,
                    )
                    GroupCallParticipantStrip(
                        participants = remoteParticipants,
                        currentPeerId = info.peerIdB64,
                        currentDisplayName = info.displayName,
                        selectedPeerId = selectedRemotePeerId,
                        onSelected = { selectedRemotePeerId = it },
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }

            // Controls overlay (bottom) — two rows for video calls
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp, start = 8.dp, end = 8.dp),
            ) {
                // Row 1: utility controls
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Mute mic
                    SmallCallToggle(
                        icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        label = if (isMuted) stringResource(R.string.call_action_unmute_mic) else stringResource(R.string.call_action_mute_mic),
                        active = isMuted,
                        onClick = { isMuted = !isMuted; onMuteChanged(isMuted) }
                    )
                    // Camera mute
                    SmallCallToggle(
                        icon = if (isVideoEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                        label = if (isVideoEnabled) stringResource(R.string.call_action_camera_on) else stringResource(R.string.call_action_camera_off),
                        active = !isVideoEnabled,
                        onClick = { isVideoEnabled = !isVideoEnabled; onVideoEnabled(isVideoEnabled) }
                    )
                    // Switch camera
                    SmallCallToggle(
                        icon = Icons.Default.FlipCameraAndroid,
                        label = stringResource(R.string.call_action_flip),
                        active = false,
                        onClick = onSwitchCamera
                    )
                    // Screen share
                    SmallCallToggle(
                        icon = if (isScreenSharing) Icons.AutoMirrored.Filled.StopScreenShare else Icons.AutoMirrored.Filled.ScreenShare,
                        label = if (isScreenSharing) stringResource(R.string.call_action_stop_screen_share) else stringResource(R.string.call_action_start_screen_share),
                        active = isScreenSharing,
                        onClick = {
                        if (isScreenSharing) {
                                viewModel.stopScreenShare()
                        } else {
                                screenShareLauncher.launch(
                                    projectionManager.createScreenCaptureIntent()
                                )
                            }
                        }
                    )
                    groupCallLink?.let { callLink ->
                        SmallCallToggle(
                            icon = Icons.Default.Share,
                            label = stringResource(R.string.call_action_share_link),
                            active = false,
                            onClick = {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, callLink)
                                }
                                context.startActivity(
                                    Intent.createChooser(
                                        shareIntent,
                                        context.getString(R.string.call_share_link_chooser_title),
                                    )
                                )
                            }
                        )
                    }
                    // Minimize to PiP
                    SmallCallToggle(
                        icon = Icons.Default.FullscreenExit,
                        label = stringResource(R.string.call_action_minimize),
                        active = false,
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                activity?.enterPictureInPictureMode(
                                    PictureInPictureParams.Builder()
                                        .setAspectRatio(Rational(16, 9))
                                        .build()
                                )
                            }
                        }
                    )
                }
                // Row 2: end call
                CallActionButton(
                    icon = Icons.Default.CallEnd,
                    tint = MaterialTheme.colorScheme.error,
                    label = stringResource(R.string.call_action_hangup),
                    onClick = onHangup
                )
            } // end Column
            } // end AnimatedVisibility
        }
    } else {
        // ── Audio call layout (unchanged) ─────────────────────────────────
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = info.displayName.ifEmpty { info.peerIdB64.take(8) + "…" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = when (info.state) {
                    CallState.Initiating -> stringResource(R.string.call_state_initiating)
                    CallState.Ringing    -> stringResource(R.string.call_state_ringing_short)
                    else         -> formatElapsed(elapsed)
                },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmallCallToggle(
                    icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    label = if (isMuted) stringResource(R.string.call_action_unmute_short) else stringResource(R.string.call_action_mute_mic),
                    active = isMuted,
                    onClick = { isMuted = !isMuted; onMuteChanged(isMuted) }
                )
                CallActionButton(
                    icon = Icons.Default.CallEnd,
                    tint = MaterialTheme.colorScheme.error,
                    label = stringResource(R.string.call_action_hangup),
                    onClick = onHangup
                )
                SmallCallToggle(
                    icon = if (isSpeaker) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeDown,
                    label = if (isSpeaker) stringResource(R.string.call_action_speakerphone_on) else stringResource(R.string.call_action_speakerphone_off),
                    active = isSpeaker,
                    onClick = { isSpeaker = !isSpeaker; onSpeakerChanged(isSpeaker) }
                )
                groupCallLink?.let { callLink ->
                    SmallCallToggle(
                        icon = Icons.Default.Share,
                        label = stringResource(R.string.call_action_share_link),
                        active = false,
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, callLink)
                            }
                            context.startActivity(
                                Intent.createChooser(
                                    shareIntent,
                                    context.getString(R.string.call_share_link_chooser_title),
                                )
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupCallParticipantStrip(
    participants: List<String>,
    currentPeerId: String,
    currentDisplayName: String,
    selectedPeerId: String?,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (participants.size < 2) return

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(participants, key = { it }) { peerId ->
            FilterChip(
                selected = peerId == selectedPeerId,
                onClick = { onSelected(peerId) },
                label = {
                    Text(
                        text = when {
                            peerId == currentPeerId && currentDisplayName.isNotBlank() -> currentDisplayName
                            else -> peerId.take(8) + "…"
                        },
                        maxLines = 1,
                    )
                },
                leadingIcon = if (peerId == selectedPeerId) {
                    { Icon(Icons.Default.Person, contentDescription = null) }
                } else {
                    null
                },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Reusable components
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CallActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    label: String,
    onClick: () -> Unit,
) {
    // V-36: Haptic feedback on call actions
    val haptic = LocalHapticFeedback.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
            modifier = Modifier.size(72.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = tint)
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(36.dp), tint = Color.White)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SmallCallToggle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = Modifier.size(56.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = if (active) MaterialTheme.colorScheme.primaryContainer
                                 else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

private fun formatElapsed(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%02d:%02d".format(m, s)
}

// ─────────────────────────────────────────────────────────────────────────────
// Call history list
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CallHistoryScreen(
    history: List<CallLogEntry>,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
            Text(
                text = "Звонки",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        HorizontalDivider()

        if (history.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Call,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "История звонков пуста",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(history, key = { it.callId }) { entry ->
                    CallHistoryRow(entry)
                    HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
                }
            }
        }
    }
}

@Composable
private fun CallHistoryRow(entry: CallLogEntry) {
    val isOutgoing = entry.direction == "outgoing"
    val isMissed   = entry.reason == "missed" || (entry.reason == "rejected" && !isOutgoing)

    val arrowIcon = if (isOutgoing) Icons.AutoMirrored.Filled.CallMade else Icons.AutoMirrored.Filled.CallReceived
    val iconTint  = when {
        isMissed  -> MaterialTheme.colorScheme.error
        isOutgoing -> MaterialTheme.colorScheme.primary
        else       -> MaterialTheme.colorScheme.secondary
    }

    val dateLabel = remember(entry.endedAt) {
        val sdf = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
        sdf.format(Date(entry.endedAt * 1000L))
    }

    val durationLabel = if (entry.durationSecs > 0) formatElapsed(entry.durationSecs) else ""
    val typeLabel     = if (entry.hasVideo) "Видео" else "Аудио"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Direction icon
        Icon(
            arrowIcon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))

        // Name + subtitle
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.displayName.ifBlank { entry.peerIdB64.take(10) },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (isMissed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (durationLabel.isBlank()) typeLabel
                       else "$typeLabel · $durationLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Date
        Text(
            text = dateLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Embeddable call-history list for the Calls tab in MainScreen (no top bar). */
@Composable
internal fun CallsTabContent(
    history: List<CallLogEntry>,
    modifier: Modifier = Modifier,
) {
    if (history.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Call,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "История звонков пуста",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        LazyColumn(modifier = modifier.fillMaxSize()) {
            items(history, key = { it.callId }) { entry ->
                CallHistoryRow(entry)
                HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
            }
        }
    }
}
