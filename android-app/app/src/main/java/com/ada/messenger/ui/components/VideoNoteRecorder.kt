package com.ada.messenger.ui.components

import android.annotation.SuppressLint
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.File

private const val TAG = "VideoNoteRecorder"
private const val MAX_VIDEO_NOTE_SECONDS = 30

/**
 * Circular camera preview + recorder for video notes (видеокружочки).
 *
 * Uses CameraX [Preview] + [VideoCapture] bound to the front camera.
 * Recording is controlled by the [isRecording] flag — when it flips to
 * `true` the composable starts writing to a temp `vidnote_*.mp4`; when it
 * flips back to `false` (or the 30-second hard limit fires) recording is
 * stopped and [onFileReady] is called with the finished file.
 *
 * @param isRecording    controlled externally (long-press / lock in input bar)
 * @param recordingSeconds elapsed seconds (tracked by the parent timer)
 * @param useFrontCamera   true for selfie camera (default for video notes)
 * @param onFileReady      called when recording finishes with the output file, or null on error
 */
@SuppressLint("MissingPermission") // caller verifies CAMERA + RECORD_AUDIO
@Composable
fun VideoNoteRecorder(
    modifier: Modifier = Modifier,
    isRecording: Boolean,
    recordingSeconds: Int,
    useFrontCamera: Boolean = true,
    onFileReady: (File?) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { ContextCompat.getMainExecutor(context) }

    // ── CameraX use cases ──────────────────────────────────────────────
    val previewUseCase = remember { Preview.Builder().build() }
    val recorder = remember {
        Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.SD))
            .build()
    }
    val videoCapture = remember { VideoCapture.withOutput(recorder) }

    // Mutable refs for active recording / provider
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var outputFile by remember { mutableStateOf<File?>(null) }

    // Callback ref so lambda capture stays fresh
    val onFileReadyRef = rememberUpdatedState(onFileReady)

    // ── Bind camera once ───────────────────────────────────────────────
    var cameraBound by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            activeRecording?.stop()
            cameraProvider?.unbindAll()
        }
    }

    // ── Start / stop recording on flag change ──────────────────────────
    LaunchedEffect(isRecording, cameraBound) {
        if (isRecording && activeRecording == null && cameraBound) {
            val file = File(context.cacheDir, "vidnote_${System.currentTimeMillis()}.mp4")
            outputFile = file
            val opts = FileOutputOptions.Builder(file).build()
            activeRecording = recorder
                .prepareRecording(context, opts)
                .withAudioEnabled()
                .start(executor) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        Log.i(TAG, "Recording finalized, error=${event.hasError()}")
                        val result = if (!event.hasError() && file.exists() && file.length() > 0) file else null
                        onFileReadyRef.value(result)
                    }
                }
            Log.i(TAG, "CameraX recording started → ${file.name}")
        } else if (!isRecording && activeRecording != null) {
            activeRecording?.stop()
            activeRecording = null
            Log.i(TAG, "CameraX recording stopped")
        }
    }

    // ── Pulsing border ─────────────────────────────────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val borderAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "borderAlpha",
    )
    val progress = recordingSeconds.toFloat() / MAX_VIDEO_NOTE_SECONDS

    // ── UI ─────────────────────────────────────────────────────────────
    Box(
        modifier = modifier.size(200.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Circular preview
        Box(
            modifier = Modifier
                .size(192.dp)
                .clip(CircleShape)
                .border(3.dp, Color.Red.copy(alpha = borderAlpha), CircleShape)
                .background(Color.Black, CircleShape),
        ) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) { previewView ->
                if (cameraBound) return@AndroidView
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    try {
                        val provider = future.get()
                        cameraProvider = provider
                        provider.unbindAll()

                        previewUseCase.setSurfaceProvider(previewView.surfaceProvider)

                        val selector = if (useFrontCamera)
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        else
                            CameraSelector.DEFAULT_BACK_CAMERA

                        provider.bindToLifecycle(
                            lifecycleOwner, selector, previewUseCase, videoCapture,
                        )
                        cameraBound = true
                        Log.i(TAG, "CameraX preview+video bound (front=$useFrontCamera)")
                    } catch (e: Exception) {
                        Log.e(TAG, "CameraX bind failed: ${e.message}")
                    }
                }, executor)
            }
        }

        // Countdown timer
        val remaining = MAX_VIDEO_NOTE_SECONDS - recordingSeconds
        Text(
            text = "%d:%02d".format(remaining / 60, remaining % 60),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = (-8).dp)
                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )

        // Progress arc
        Canvas(modifier = Modifier.size(200.dp)) {
            drawArc(
                color = Color.Red.copy(alpha = 0.7f),
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                style = Stroke(width = 4.dp.toPx()),
            )
        }
    }
}

