package com.ada.messenger.ui.components

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.min

private const val TAG = "MediaRecorderHelper"

/**
 * Thin wrapper around [MediaRecorder] for voice messages and 30-second video notes.
 *
 * Usage pattern (voice):
 * ```
 * val helper = MediaRecorderHelper(context)
 * helper.startVoiceRecording()
 * // … user holds button …
 * val file: File? = helper.stopRecordingToFile()
 * // send file.absolutePath via viewModel.sendAttachment(convId, file.name, "audio/ogg", file.absolutePath)
 * ```
 *
 * Usage pattern (video note):
 * ```
 * // Video notes are recorded by VideoNoteRecorder composable using CameraX VideoCapture.
 * // MediaRecorderHelper is voice-only.
 * ```
 */
class MediaRecorderHelper(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var tempFile: File? = null
    private var autoStopJob: Thread? = null

    /** True while a recording session is active. */
    val isRecording: Boolean get() = recorder != null

    // ── Voice recording ───────────────────────────────────────────────────

    /**
     * Start recording a voice message to a temp file.
     * Requires RECORD_AUDIO permission.
     * Max duration: 120 seconds (auto-stop).
     */
    fun startVoiceRecording(onAutoStop: (() -> Unit)? = null) {
        cancel() // ensure clean state without materializing old recording bytes
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.ogg")
        tempFile = file

        val mr = createRecorder()
        mr.setAudioSource(MediaRecorder.AudioSource.MIC)
        mr.setOutputFormat(MediaRecorder.OutputFormat.OGG)
        mr.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
        mr.setAudioSamplingRate(16_000)
        mr.setAudioEncodingBitRate(32_000)
        mr.setOutputFile(file.absolutePath)
        mr.setMaxDuration(120_000) // 2 minutes hard cap, UI has its own limit
        mr.setOnInfoListener { _, what, _ ->
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                Log.i(TAG, "Voice recording reached max duration")
                onAutoStop?.invoke()
            }
        }
        try {
            mr.prepare()
            mr.start()
            recorder = mr
            Log.i(TAG, "Voice recording started")
        } catch (e: Exception) {
            Log.e(TAG, "startVoiceRecording failed: ${e.message}")
            mr.release()
            secureDelete(file)
            tempFile = null
        }
    }

    // ── Stop ──────────────────────────────────────────────────────────────

    /**
     * Stop the current recording and keep the recorded temp file, or null on failure.
     */
    fun stopRecordingToFile(): File? {
        val mr = recorder ?: return null
        recorder = null
        val file = tempFile
        tempFile = null

        return try {
            mr.stop()
            mr.release()
            file?.takeIf { it.exists() }
        } catch (e: Exception) {
            Log.e(TAG, "stopRecording failed: ${e.message}")
            mr.runCatching { release() }
            if (file != null) secureDelete(file)
            null
        }
    }

    /**
     * Compatibility helper for callers that still need the bytes in memory.
     * The temp file is deleted after reading.
     */
    fun stopRecording(): ByteArray? {
        val file = stopRecordingToFile() ?: return null
        return try {
            file.readBytes()
        } catch (e: Exception) {
            Log.e(TAG, "stopRecording read failed: ${e.message}")
            null
        } finally {
            secureDelete(file)
        }
    }

    /** Release any ongoing recording without returning data. */
    fun cancel() {
        val mr = recorder ?: return
        recorder = null
        val file = tempFile
        tempFile = null
        mr.runCatching { stop(); release() }
        if (file != null) secureDelete(file)
        Log.i(TAG, "Recording cancelled")
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun createRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    companion object {
        /** Overwrite file contents with zeros before deleting to hinder forensic recovery. */
        fun secureDelete(file: File) {
            if (!file.isFile) return
            try {
                val len = file.length()
                if (len > 0) {
                    RandomAccessFile(file, "rw").use { f ->
                        val buf = ByteArray(min(len, 65536L).toInt())
                        var remaining = len
                        while (remaining > 0) {
                            val chunk = min(remaining, buf.size.toLong()).toInt()
                            f.write(buf, 0, chunk)
                            remaining -= chunk
                        }
                        f.fd.sync()
                    }
                }
            } catch (_: Exception) { /* best effort */ }
            file.delete()
        }
    }
}
