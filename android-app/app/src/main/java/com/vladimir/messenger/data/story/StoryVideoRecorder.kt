package com.vladimir.messenger.data.story

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Main-thread camera session. Owner must close on dismissal/background, never keep recording invisibly. */
internal class StoryVideoRecorder(private val context: Context) {
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var capture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var pendingFile: File? = null
    private var cancelled = false
    private var closed = false

    suspend fun bind(owner: LifecycleOwner, view: PreviewView, front: Boolean) = withContext(Dispatchers.Main.immediate) {
        check(!closed && pendingFile == null) { "Сначала завершите запись" }
        require(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        val camera = provider ?: suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (continuation.isActive) {
                    try { continuation.resume(future.get()) }
                    catch (error: Exception) { continuation.resumeWithException(error) }
                }
            }, ContextCompat.getMainExecutor(context))
        }
        check(!closed)
        provider = camera
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        require(camera.hasCamera(selector)) { "Выбранная камера недоступна" }
        preview?.let { camera.unbind(it) }
        capture?.let { camera.unbind(it) }
        val newPreview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
        val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD,
            FallbackStrategy.lowerQualityOrHigherThan(Quality.HD))).build()
        val newCapture = VideoCapture.withOutput(recorder)
        newCapture.targetRotation = view.display?.rotation ?: android.view.Surface.ROTATION_0
        camera.bindToLifecycle(owner, selector, newPreview, newCapture)
        preview = newPreview
        capture = newCapture
    }

    /** Permissions are requested by the screen; denial of audio permits an explicit silent recording. */
    @android.annotation.SuppressLint("MissingPermission")
    fun start(withAudio: Boolean, onElapsed: (Long) -> Unit, onFinished: (Result<File>) -> Unit) {
        check(!closed && pendingFile == null)
        require(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        if (withAudio) require(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        val active = checkNotNull(capture) { "Камера ещё не готова" }
        val file = File.createTempFile("recording-", ".mp4", StoryVideoFiles.directory(context))
        pendingFile = file
        cancelled = false
        try {
            val options = FileOutputOptions.Builder(file).setDurationLimitMillis(StoryVideoDraft.MAX_DURATION_MS)
                .setFileSizeLimit(StoryVideoFiles.MAX_SOURCE_BYTES).build()
            val pending = active.output.prepareRecording(context, options)
            recording = (if (withAudio) pending.withAudioEnabled() else pending)
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    when (event) {
                        is VideoRecordEvent.Status -> if (!closed) onElapsed(event.recordingStats.recordedDurationNanos / 1_000_000L)
                        is VideoRecordEvent.Finalize -> {
                            recording = null
                            pendingFile = null
                            if (cancelled || closed) {
                                file.delete()
                            } else if (event.error == VideoRecordEvent.Finalize.ERROR_NONE ||
                                event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED) {
                                onFinished(Result.success(file))
                            } else {
                                file.delete()
                                onFinished(Result.failure(IllegalStateException("Запись видео не завершена: ${event.error}")))
                            }
                        }
                    }
                }
        } catch (error: Exception) {
            pendingFile = null
            file.delete()
            onFinished(Result.failure(error))
        }
    }

    fun pause() { recording?.pause() }
    fun resume() { recording?.resume() }
    fun stop() { recording?.stop() }
    fun discard() { cancelled = true; recording?.stop() }

    fun close() {
        closed = true
        discard()
        preview?.let { provider?.unbind(it) }
        capture?.let { provider?.unbind(it) }
        preview = null
        capture = null
    }
}
