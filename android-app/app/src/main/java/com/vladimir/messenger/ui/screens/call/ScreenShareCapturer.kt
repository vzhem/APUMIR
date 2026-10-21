package com.vladimir.messenger.ui.screens.call

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import com.vladimir.messenger.data.call.CallVideo
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Захват экрана для демонстрации в звонке (MediaProjection).
 *
 * Кадры ужимаются в тот же формат, что видео камеры — [CallVideo.pack] с JPEG
 * до [CallVideo.MAX_JPEG_BYTES]: собеседник получает экран по существующему
 * зашифрованному видео-пути звонка, протокол медиа не меняется.
 */
class ScreenShareCapturer(
    private val context: Context,
    private val onJpeg: (ByteArray) -> Unit,
) {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private val lastSent = AtomicLong(0L)

    @Volatile
    var running: Boolean = false
        private set

    fun start(resultCode: Int, data: Intent): Boolean {
        stop()
        val manager = context.getSystemService(MediaProjectionManager::class.java) ?: return false
        val proj = runCatching { manager.getMediaProjection(resultCode, data) }.getOrNull() ?: return false
        projection = proj
        val worker = HandlerThread("apu-screen-share").apply { start() }
        thread = worker
        val handler = Handler(worker.looper)
        runCatching { proj.registerCallback(object : MediaProjection.Callback() {}, handler) }

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        context.getSystemService(WindowManager::class.java)?.defaultDisplay?.getRealMetrics(metrics)
        val longSide = maxOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(2)
        val scale = (MAX_LONG_SIDE.toFloat() / longSide).coerceAtMost(1f)
        val width = even((metrics.widthPixels * scale).toInt())
        val height = even((metrics.heightPixels * scale).toInt())
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = imageReader
        imageReader.setOnImageAvailableListener({ source ->
            val now = System.currentTimeMillis()
            if (now - lastSent.get() < FRAME_INTERVAL_MS) {
                source.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val packed = imageToPackedJpeg(image) ?: return@setOnImageAvailableListener
                lastSent.set(now)
                onJpeg(packed)
            } catch (_: Exception) {
                // Один битый кадр не должен ронять демонстрацию.
            } finally {
                image.close()
            }
        }, handler)
        display = runCatching {
            proj.createVirtualDisplay(
                "apu-screen-share", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface, null, handler,
            )
        }.getOrNull()
        running = display != null
        if (!running) stop()
        return running
    }

    fun stop() {
        running = false
        runCatching { display?.release() }
        display = null
        runCatching { reader?.close() }
        reader = null
        runCatching { projection?.stop() }
        projection = null
        thread?.quitSafely()
        thread = null
    }

    private fun imageToPackedJpeg(image: android.media.Image): ByteArray? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0 || rowStride <= 0) return null
        val widthWithPadding = rowStride / pixelStride
        val raw = Bitmap.createBitmap(widthWithPadding, image.height, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(buffer)
        val cropped = if (widthWithPadding == image.width) raw
        else Bitmap.createBitmap(raw, 0, 0, image.width, image.height)
        var bitmap = cropped
        var quality = START_QUALITY
        repeat(4) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val jpeg = out.toByteArray()
            if (jpeg.size <= CallVideo.MAX_JPEG_BYTES) {
                if (bitmap !== cropped) bitmap.recycle()
                return CallVideo.pack(0, jpeg)
            }
            if (quality > MIN_QUALITY) {
                quality = (quality - 12).coerceAtLeast(MIN_QUALITY)
            } else {
                val smaller = Bitmap.createScaledBitmap(
                    bitmap, (bitmap.width * 0.7f).toInt().coerceAtLeast(2),
                    (bitmap.height * 0.7f).toInt().coerceAtLeast(2), true,
                )
                if (bitmap !== cropped) bitmap.recycle()
                bitmap = smaller
                quality = START_QUALITY
            }
        }
        if (bitmap !== cropped) bitmap.recycle()
        return null
    }

    private fun even(value: Int): Int = value.coerceAtLeast(2).let { if (it % 2 == 0) it else it - 1 }

    private companion object {
        /** Длинная сторона захвата: экран читаем, а кадр всё равно ограничен размером пакета звонка. */
        const val MAX_LONG_SIDE = 480
        const val FRAME_INTERVAL_MS = 150L
        const val START_QUALITY = 38
        const val MIN_QUALITY = 12
    }
}
