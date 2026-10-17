package com.vladimir.messenger.data.story

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Итог наложения фильтра: файл для дальнейшей работы и понятное сообщение, если фильтр не лёг. */
internal data class StoryFaceBakeResult(val file: File, val message: String?)

/**
 * Накладывает AR-фильтр на уже записанное видео и сохраняет его уже с фильтром.
 *
 * Шаг 1 — анализ: файл декодируется MediaCodec, на каждом замере (100 мс) ML Kit
 * находит лица. Шаг 2 — перекодирование Transformer с OverlayEffect, который
 * в каждом кадре рисует фильтр по таймлайну тем же кодом, что и предпросмотр.
 *
 * Исходный файл не трогаем: при успехе его удаляет вызывающий, при сбое возвращаем его же.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object StoryFaceBaker {
    suspend fun bake(
        context: Context,
        source: File,
        filter: StoryFaceFilter,
        onProgress: (Float) -> Unit,
    ): StoryFaceBakeResult {
        if (!filter.isActive) return StoryFaceBakeResult(source, null)
        val timeline = StoryFaceAnalysis.analyze(source) { onProgress(it * 0.5f) }
        if (timeline.isEmpty) {
            return StoryFaceBakeResult(source, "Лицо в кадре не нашлось, видео сохранено без фильтра")
        }
        val output = File.createTempFile("filtered-", ".mp4", StoryVideoFiles.directory(context))
        // Transformer требует путь, которого ещё нет.
        check(output.delete())
        return try {
            transform(context.applicationContext, source, output, filter, timeline) { onProgress(0.5f + it * 0.5f) }
            StoryFaceBakeResult(output, null)
        } catch (cancelled: CancellationException) {
            output.delete()
            throw cancelled
        } catch (failure: Exception) {
            output.delete()
            StoryFaceBakeResult(source, "Не удалось наложить фильтр, видео сохранено без него")
        }
    }

    private suspend fun transform(
        context: Context,
        source: File,
        output: File,
        filter: StoryFaceFilter,
        timeline: StoryFaceTimeline,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.Main.immediate) {
        coroutineScope {
            val item = MediaItem.fromUri(Uri.fromFile(source))
            val overlay = StoryFaceOverlaySource(context.applicationContext, filter, timeline)
            val edited = EditedMediaItem.Builder(item)
                .setEffects(Effects(emptyList(), listOf(OverlayEffect(listOf(overlay)))))
                .build()
            var transformer: Transformer? = null
            val progressJob = launch {
                val progress = ProgressHolder()
                while (true) {
                    val current = transformer
                    if (current != null && current.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(progress.progress / 100f)
                    }
                    delay(200)
                }
            }
            try {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val encoder = DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(8_000_000).build())
                        .build()
                    val active = Transformer.Builder(context)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setEncoderFactory(encoder)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (continuation.isActive) continuation.resume(Unit)
                            }

                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                if (continuation.isActive) continuation.resumeWithException(exportException)
                            }
                        })
                        .build()
                    transformer = active
                    continuation.invokeOnCancellation {
                        // Transformer работает только с главного потока.
                        android.os.Handler(android.os.Looper.getMainLooper()).post { active.cancel(); output.delete() }
                    }
                    try {
                        active.start(edited, output.absolutePath)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
                onProgress(1f)
            } finally {
                progressJob.cancel()
                transformer?.cancel()
            }
        }
    }
}

/**
 * Источник наложения для Transformer: в каждом кадре выдаёт прозрачную картинку с фильтром
 * для лиц, найденных ближе всего по времени. Новый Bitmap только когда меняется замер:
 * пересылка в GL делается по экземпляру Bitmap, и это экономит работу на каждом кадре.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class StoryFaceOverlaySource(
    private val appContext: Context,
    private val filter: StoryFaceFilter,
    private val timeline: StoryFaceTimeline,
) : BitmapOverlay() {
    private var lastIndex = Int.MIN_VALUE
    private var current: Bitmap? = null
    private var blank: Bitmap? = null

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val index = timeline.sampleIndexAt(presentationTimeUs / 1000L)
        if (index != lastIndex || current == null) {
            lastIndex = index
            val previous = current
            current = if (index < 0) blankBitmap() else renderBitmap(index)
            // Предыдущий кадр уже загружен в текстуру синхронно и больше не нужен; пустой кадр переиспользуем.
            if (previous != null && previous !== blank) previous.recycle()
        }
        return checkNotNull(current)
    }

    override fun release() {
        super.release()
        current?.takeIf { it !== blank }?.recycle()
        blank?.recycle()
        current = null
        blank = null
    }

    private fun renderBitmap(index: Int): Bitmap {
        val bitmap = newBitmap()
        drawStoryFaces(
            AndroidStoryFaceCanvas(Canvas(bitmap)) { StoryStickers.bitmap(appContext, it) },
            StoryFaceFrame(timeline.width, timeline.height, timeline.facesAt(index)),
            filter,
            StoryFaceTransform.identity(timeline.width),
        )
        return bitmap
    }

    private fun blankBitmap(): Bitmap = blank ?: newBitmap().also { blank = it }

    private fun newBitmap(): Bitmap = Bitmap.createBitmap(timeline.width, timeline.height, Bitmap.Config.ARGB_8888)
}

/** Анализ записанного видео: последовательное декодирование и распознавание лиц на каждом замере. */
internal object StoryFaceAnalysis {
    private const val TIMEOUT_US = 10_000L
    private const val TASK_TIMEOUT_S = 10L

    suspend fun analyze(file: File, onProgress: (Float) -> Unit): StoryFaceTimeline = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        val detector = newStoryFaceDetector()
        var decoder: MediaCodec? = null
        try {
            retriever.setDataSource(file.absolutePath)
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return@withContext StoryFaceTimeline.EMPTY
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext StoryFaceTimeline.EMPTY

            val codec = MediaCodec.createDecoderByType(mime)
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()

            val times = ArrayList<Long>()
            val samples = ArrayList<List<StoryFace>>()
            var width = 0
            var height = 0
            var nextSampleMs = 0L
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()

            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)
                        val size = if (buffer != null) extractor.readSampleData(buffer, 0) else -1
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, extractor.sampleFlags)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    val tMs = info.presentationTimeUs / 1000L
                    if (info.size > 0 && tMs >= nextSampleMs) {
                        val image: Image? = codec.getOutputImage(outIndex)
                        if (image != null) {
                            try {
                                val input = InputImage.fromMediaImage(image, rotation)
                                width = input.width
                                height = input.height
                                val faces = try {
                                    Tasks.await(detector.process(input), TASK_TIMEOUT_S, TimeUnit.SECONDS)
                                        .map { it.toStoryFace() }
                                } catch (failure: Exception) {
                                    // Один плохой кадр не должен срывать весь фильтр.
                                    emptyList()
                                }
                                times.add(tMs)
                                samples.add(faces)
                                nextSampleMs = tMs + StoryFaceTimeline.SAMPLE_STEP_MS
                            } finally {
                                image.close()
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (durationMs > 0) onProgress((tMs.toFloat() / durationMs).coerceIn(0f, 1f))
                }
            }
            onProgress(1f)
            if (width <= 0 || height <= 0) return@withContext StoryFaceTimeline.EMPTY
            StoryFaceTimeline(width, height, times.toLongArray(), samples)
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            extractor.release()
            retriever.release()
            detector.close()
        }
    }
}
