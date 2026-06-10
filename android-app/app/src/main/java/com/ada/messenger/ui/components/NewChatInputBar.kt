
package com.ada.messenger.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ada.messenger.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

enum class MediaMode { VOICE, VIDEO }
enum class RecordState { IDLE, RECORDING, LOCKED }

@Composable
fun NewChatInputBar(
    modifier: Modifier = Modifier,
    text: String,
    onTextChanged: (String) -> Unit,
    onSendText: (Int?) -> Unit,
    onAttach: () -> Unit,
    onSendMedia: (File, MediaMode) -> Unit,
    recorderHelper: MediaRecorderHelper,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mediaMode by remember { mutableStateOf(MediaMode.VOICE) }
    var recordState by remember { mutableStateOf(RecordState.IDLE) }
    var recordingSeconds by remember { mutableStateOf(0) }
    var ttlSecs by remember { mutableStateOf<Int?>(null) }

    // Video note flags — keep composable in tree until CameraX delivers the file
    var showVideoRecorder by remember { mutableStateOf(false) }
    var videoNoteCancelled by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* user taps again after granting */ }

    // ── Timer ──────────────────────────────────────────────────────────
    LaunchedEffect(recordState) {
        if (recordState != RecordState.IDLE) {
            recordingSeconds = 0
            while (recordState != RecordState.IDLE) {
                delay(1000L)
                recordingSeconds++
                val limit = if (mediaMode == MediaMode.VIDEO) 30 else 120
                if (recordingSeconds >= limit) {
                    if (mediaMode == MediaMode.VOICE) {
                        val file = recorderHelper.stopRecordingToFile()
                        recordState = RecordState.IDLE
                        if (file != null && file.length() > 0) onSendMedia(file, mediaMode)
                        else file?.let { MediaRecorderHelper.secureDelete(it) }
                    } else {
                        // VIDEO: flip state → VideoNoteRecorder handles stop + onFileReady
                        recordState = RecordState.IDLE
                    }
                }
            }
        }
    }

    // ── Actions ────────────────────────────────────────────────────────
    val startRecording = {
        val hasAudio = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        val hasCamera = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED

        if (mediaMode == MediaMode.VOICE && hasAudio) {
            recordState = RecordState.RECORDING
            recorderHelper.startVoiceRecording {
                scope.launch { recordState = RecordState.IDLE }
            }
        } else if (mediaMode == MediaMode.VIDEO && hasAudio && hasCamera) {
            videoNoteCancelled = false
            showVideoRecorder = true
            recordState = RecordState.RECORDING
            // CameraX recording starts in VideoNoteRecorder composable
        } else {
            val needed = mutableListOf<String>()
            if (!hasAudio) needed += Manifest.permission.RECORD_AUDIO
            if (mediaMode == MediaMode.VIDEO && !hasCamera) needed += Manifest.permission.CAMERA
            permLauncher.launch(needed.toTypedArray())
        }
    }

    val stopAndSend = {
        val stateWas = recordState
        recordState = RecordState.IDLE
        if (stateWas != RecordState.IDLE && mediaMode == MediaMode.VOICE) {
            val file = recorderHelper.stopRecordingToFile()
            if (file != null && file.length() > 0) onSendMedia(file, mediaMode)
            else file?.let { MediaRecorderHelper.secureDelete(it) }
        }
        // VIDEO: VideoNoteRecorder.onFileReady handles sending
    }

    val cancelRecording = {
        if (mediaMode == MediaMode.VIDEO) videoNoteCancelled = true
        recordState = RecordState.IDLE
        if (mediaMode == MediaMode.VOICE) recorderHelper.cancel()
    }

    // ── Layout ─────────────────────────────────────────────────────────
    Column(modifier = modifier) {
        // Circular CameraX preview + recorder overlay for video notes
        if (showVideoRecorder) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                VideoNoteRecorder(
                    isRecording = recordState != RecordState.IDLE,
                    recordingSeconds = recordingSeconds,
                    onFileReady = { file ->
                        showVideoRecorder = false
                        if (file != null && !videoNoteCancelled && file.length() > 0) {
                            onSendMedia(file, MediaMode.VIDEO)
                        } else {
                            file?.let { MediaRecorderHelper.secureDelete(it) }
                        }
                    },
                )
            }
        }

        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (recordState == RecordState.IDLE) {
                IconButton(onClick = onAttach, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.AttachFile, contentDescription = "Attach", modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = {
                    ttlSecs = when (ttlSecs) {
                        null -> 30        // 30s
                        30 -> 3600        // 1h
                        3600 -> 86400     // 24h
                        else -> null      // Off
                    }
                }, modifier = Modifier.size(36.dp)) {
                    val icon = if (ttlSecs != null) Icons.Default.Timer else Icons.Default.TimerOff
                    val color = if (ttlSecs != null) MaterialTheme.colorScheme.primary else LocalContentColor.current.copy(alpha = 0.5f)
                    Icon(icon, contentDescription = "TTL", tint = color, modifier = Modifier.size(20.dp))
                }
                TextField(
                    value = text,
                    onValueChange = onTextChanged,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(max = 120.dp),
                    placeholder = {
                        val hint = if (ttlSecs != null) {
                            val label = when (ttlSecs) {
                                30 -> "30s"
                                3600 -> "1h"
                                86400 -> "24h"
                                else -> ""
                            }
                            stringResource(R.string.chat_input_hint) + " (ttl: $label)"
                        } else stringResource(R.string.chat_input_hint)
                        Text(hint)
                    },
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    )
                )
                Spacer(Modifier.width(4.dp))
            } else {
                // Recording indicator bar
                val mins = recordingSeconds / 60
                val secs = recordingSeconds % 60
                val timeStr = "%d:%02d".format(mins, secs)

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(24.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val icon = if (mediaMode == MediaMode.VOICE) Icons.Default.Mic else Icons.Default.Videocam
                    Icon(icon, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(timeStr, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))

                    if (recordState == RecordState.LOCKED) {
                        IconButton(onClick = cancelRecording) {
                            Icon(Icons.Default.Cancel, null, tint = MaterialTheme.colorScheme.error)
                        }
                        IconButton(onClick = stopAndSend) {
                            Icon(Icons.AutoMirrored.Filled.Send, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        Text(
                            "< Swipe cancel | ^ Lock",
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
            }

            if (text.isNotBlank() && recordState == RecordState.IDLE) {
                FilledIconButton(onClick = { onSendText(ttlSecs) }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_action_send))
                }
            } else if (recordState != RecordState.LOCKED) {
                val vcConfig = LocalViewConfiguration.current
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (recordState == RecordState.IDLE) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.error,
                        )
                        .pointerInput(mediaMode) {
                            awaitEachGesture {
                                awaitFirstDown()
                                var isLongPress = false
                                try {
                                    withTimeout(vcConfig.longPressTimeoutMillis) {
                                        val up = waitForUpOrCancellation()
                                        if (up != null) {
                                            up.consume()
                                            mediaMode = if (mediaMode == MediaMode.VOICE) MediaMode.VIDEO else MediaMode.VOICE
                                        }
                                    }
                                } catch (_: PointerEventTimeoutCancellationException) {
                                    isLongPress = true
                                    startRecording()
                                }

                                if (isLongPress) {
                                    var dragX = 0f
                                    var dragY = 0f
                                    var lockedOrCanceled = false

                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: break

                                        if (!change.pressed) {
                                            change.consume()
                                            if (!lockedOrCanceled) stopAndSend()
                                            break
                                        }

                                        dragX += change.positionChange().x
                                        dragY += change.positionChange().y

                                        if (!lockedOrCanceled) {
                                            if (dragX < -100f) {
                                                lockedOrCanceled = true
                                                cancelRecording()
                                            } else if (dragY < -100f) {
                                                lockedOrCanceled = true
                                                recordState = RecordState.LOCKED
                                            }
                                        }
                                        change.consume()
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val icon = if (mediaMode == MediaMode.VOICE) Icons.Default.Mic else Icons.Default.Videocam
                    Icon(
                        icon,
                        contentDescription = "Voice/Video Message",
                        tint = if (recordState == RecordState.IDLE) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onError,
                    )
                }
            }
        }
    }
}

