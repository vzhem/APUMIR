package com.vladimir.messenger.data.story

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import com.vladimir.messenger.data.background.PersonSegmenter
import com.vladimir.messenger.data.background.VideoBackground
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Итог замены фона: файл и понятное сообщение, если фон не лёг. */
internal data class StoryBackgroundBakeResult(val file: File, val message: String?)

/**
 * Замена фона в уже записанной видеоистории.
 *
 * Видео разбирается покадрово: человек вырезается сегментацией (ML Kit, на
 * устройстве) и кладётся на выбранный фон, звук копируется как есть. Как и
 * фильтр лица, при любой неудаче возвращаем исходный файл с пояснением —
 * история не должна пропадать из-за фона.
 */
internal object StoryBackgroundBaker {

    suspend fun bake(
        context: android.content.Context,
        source: File,
        background: VideoBackground,
        onProgress: (Float) -> Unit,
    ): StoryBackgroundBakeResult {
        if (!background.isActive) return StoryBackgroundBakeResult(source, null)
        val output = File.createTempFile("bgstory-", ".mp4", StoryVideoFiles.directory(context))
        return try {
            withContext(Dispatchers.IO) { transcode(source, output, background, onProgress) }
            StoryBackgroundBakeResult(output, null)
        } catch (cancelled: CancellationException) {
            output.delete()
            throw cancelled
        } catch (failure: Exception) {
            output.delete()
            StoryBackgroundBakeResult(source, "Не удалось поставить фон, видео сохранено без него")
        }
    }

    private fun transcode(source: File, output: File, background: VideoBackground, onProgress: (Float) -> Unit) {
        val retriever = MediaMetadataRetriever()
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            retriever.setDataSource(source.absolutePath)
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            videoExtractor.setDataSource(source.absolutePath)
            val videoTrack = (0 until videoExtractor.trackCount).firstOrNull { index ->
                videoExtractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: throw IllegalStateException("no video track")
            videoExtractor.selectTrack(videoTrack)
            val inputFormat = videoExtractor.getTrackFormat(videoTrack)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: throw IllegalStateException("no mime")
            val srcWidth = inputFormat.getInteger(MediaFormat.KEY_WIDTH)
            val srcHeight = inputFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val scale = (MAX_LONG_SIDE.toFloat() / maxOf(srcWidth, srcHeight)).coerceAtMost(1f)
            val width = even((srcWidth * scale).toInt())
            val height = even((srcHeight * scale).toInt())

            audioExtractor.setDataSource(source.absolutePath)
            val audioTrack = (0 until audioExtractor.trackCount).firstOrNull { index ->
                audioExtractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: -1
            val audioFormat = if (audioTrack >= 0) {
                audioExtractor.selectTrack(audioTrack)
                audioExtractor.getTrackFormat(audioTrack)
            } else null

            decoder = MediaCodec.createDecoderByType(mime).also { codec ->
                codec.configure(inputFormat, null, null, 0)
                codec.start()
            }
            val encoderFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfoCompat.YUV420_FLEXIBLE)
                setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { codec ->
                codec.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
            }
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(rotation)

            val backgroundBitmap = if (background == VideoBackground.BLUR) null
            else background.render(width, height)

            val codecInfo = MediaCodec.BufferInfo()
            val audioInfo = MediaCodec.BufferInfo()
            val audioBuffer = ByteArray(256 * 1024)
            var videoTrackOut = -1
            var audioTrackOut = -1
            var muxerStarted = false
            var inputDone = false
            var outputDone = false
            var lastVideoPtsUs = 0L

            fun drainEncoder(endOfStream: Boolean) {
                val enc = encoder ?: return
                while (true) {
                    val index = enc.dequeueOutputBuffer(codecInfo, if (endOfStream) 100_000 else 0)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        videoTrackOut = muxer!!.addTrack(enc.outputFormat)
                        audioTrackOut = if (audioFormat != null) muxer!!.addTrack(audioFormat) else -1
                        muxer!!.start()
                        muxerStarted = true
                        continue
                    }
                    if (index < 0) return
                    val buffer = enc.getOutputBuffer(index) ?: continue
                    if (codecInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) codecInfo.size = 0
                    if (codecInfo.size > 0 && muxerStarted) {
                        buffer.position(codecInfo.offset)
                        buffer.limit(codecInfo.offset + codecInfo.size)
                        muxer!!.writeSampleData(videoTrackOut, buffer, codecInfo)
                    }
                    enc.releaseOutputBuffer(index, false)
                    if (codecInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                        return
                    }
                }
            }

            fun pumpAudio(upToPtsUs: Long) {
                if (!muxerStarted || audioTrackOut < 0) return
                while (true) {
                    val size = audioExtractor.readSampleData(
                        java.nio.ByteBuffer.wrap(audioBuffer), 0,
                    )
                    if (size < 0) return
                    val pts = audioExtractor.sampleTime
                    if (pts > upToPtsUs && upToPtsUs >= 0) return
                    audioInfo.set(0, size, pts, audioExtractor.sampleFlags)
                    muxer!!.writeSampleData(
                        audioTrackOut, java.nio.ByteBuffer.wrap(audioBuffer, 0, size), audioInfo,
                    )
                    if (!audioExtractor.advance()) return
                }
            }

            val info = MediaCodec.BufferInfo()
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex)
                        val size = if (buffer != null) videoExtractor.readSampleData(buffer, 0) else -1
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(
                                inIndex, 0, size, videoExtractor.sampleTime, videoExtractor.sampleFlags,
                            )
                            videoExtractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex >= 0) {
                    val ptsUs = info.presentationTimeUs
                    val isEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (info.size > 0) {
                        val image = decoder.getOutputImage(outIndex)
                        if (image != null) {
                            try {
                                val frame = imageToBitmap(image)
                                if (frame != null) {
                                    val scaled = if (frame.width == width && frame.height == height) frame
                                    else Bitmap.createScaledBitmap(frame, width, height, true)
                                    val composited = PersonSegmenter.composite(
                                        scaled,
                                        if (background == VideoBackground.BLUR) null else backgroundBitmap,
                                    )
                                    feedEncoder(encoder, composited, ptsUs)
                                    lastVideoPtsUs = ptsUs
                                    if (composited !== scaled) composited.recycle()
                                    if (scaled !== frame) scaled.recycle()
                                    frame.recycle()
                                }
                            } finally {
                                image.close()
                            }
                        }
                        if (durationMs > 0) {
                            onProgress((ptsUs / 1000f / durationMs).coerceIn(0f, 1f))
                        }
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    drainEncoder(false)
                    pumpAudio(lastVideoPtsUs)
                    if (isEos) {
                        val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                        if (inIndex >= 0) {
                            encoder.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                        }
                        drainEncoder(true)
                    }
                }
            }
            pumpAudio(-1)
            onProgress(1f)
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            videoExtractor.release()
            audioExtractor.release()
            retriever.release()
        }
    }

    /** Закодировать один готовый кадр с его меткой времени. */
    private fun feedEncoder(encoder: MediaCodec, bitmap: Bitmap, ptsUs: Long) {
        val index = encoder.dequeueInputBuffer(100_000)
        if (index < 0) return
        val image = encoder.getInputImage(index) ?: return
        fillYuv420(image, bitmap)
        encoder.queueInputBuffer(index, 0, 0, ptsUs, 0)
    }

    /** Bitmap -> плоскости YUV420 входного образа кодека (BT.601, с учётом stride/pixelStride). */
    private fun fillYuv420(image: android.media.Image, bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = pixels[y * width + x]
                val r = color shr 16 and 0xFF
                val g = color shr 8 and 0xFF
                val b = color and 0xFF
                val yValue = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                putPixel(yPlane, x, y, yValue)
                if (x % 2 == 0 && y % 2 == 0) {
                    val uValue = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val vValue = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    putPixel(uPlane, x / 2, y / 2, uValue)
                    putPixel(vPlane, x / 2, y / 2, vValue)
                }
            }
        }
    }

    private fun putPixel(plane: android.media.Image.Plane, x: Int, y: Int, value: Int) {
        val buffer = plane.buffer
        val position = y * plane.rowStride + x * plane.pixelStride
        if (position < buffer.limit()) buffer.put(position, value.coerceIn(0, 255).toByte())
    }

    /** Кадр декодера (YUV_420_888) -> Bitmap через NV21 и JPEG: медленно, но надёжно по цветам. */
    private fun imageToBitmap(image: android.media.Image): Bitmap? {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val nv21 = ByteArray(ySize + ySize / 2)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        var offset = 0
        for (row in 0 until height) {
            val rowStart = row * yPlane.rowStride
            for (col in 0 until width) {
                nv21[offset++] = yPlane.buffer.get(rowStart + col * yPlane.pixelStride)
            }
        }
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                nv21[offset++] = vPlane.buffer.get(row * vPlane.rowStride + col * vPlane.pixelStride)
                nv21[offset++] = uPlane.buffer.get(row * uPlane.rowStride + col * uPlane.pixelStride)
            }
        }
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        if (!yuv.compressToJpeg(Rect(0, 0, width, height), 88, out)) return null
        val bytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun even(value: Int): Int = value.coerceAtLeast(2).let { if (it % 2 == 0) it else it - 1 }

    private object MediaCodecInfoCompat {
        // Значение COLOR_FormatYUV420Flexible без импорта MediaCodecInfo ради одной константы.
        const val YUV420_FLEXIBLE = 2135033992
    }

    private const val TIMEOUT_US = 10_000L
    private const val MAX_LONG_SIDE = 854
}
