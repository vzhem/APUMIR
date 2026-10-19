package com.vladimir.messenger.data.background

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.util.concurrent.TimeUnit

/**
 * Вырезание человека из кадра (ML Kit Selfie Segmentation, на устройстве) и
 * наложение его на выбранный фон.
 *
 * Один сегментер на приложение: модель маленькая и живёт в сервисах Google
 * Play / ML Kit, кадры наружу не уходят. При недоступности сегментации
 * возвращаем исходный кадр — фон просто не применится, звонок не пострадает.
 */
object PersonSegmenter {

    private val segmenter by lazy {
        Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
                .build(),
        )
    }

    @Volatile
    var available: Boolean = true
        private set

    /**
     * Кадр с заменённым фоном. [background] = null означает «размыть настоящий
     * фон» (BLUR): позади человека кладём сильно уменьшенную копию кадра.
     * При любой неудаче возвращается копия исходного кадра.
     */
    fun composite(source: Bitmap, background: Bitmap?): Bitmap {
        if (!available) return source.copy(Bitmap.Config.ARGB_8888, false)
        val mask = try {
            val result = Tasks.await(
                segmenter.process(InputImage.fromBitmap(source, 0)),
                5, TimeUnit.SECONDS,
            )
            extractMask(result)
        } catch (e: Exception) {
            available = false
            return source.copy(Bitmap.Config.ARGB_8888, false)
        } ?: return source.copy(Bitmap.Config.ARGB_8888, false)
        return applyMask(source, background, mask)
    }

    /**
     * Маска из результата сегментации. В разных версиях ML Kit доступ к ней
     * назван по-разному (свойство, метод или поле), поэтому ищем по типу
     * возвращаемого значения, а не по имени: соберётся и заработает в обеих.
     */
    private fun extractMask(result: Any): SegmentationMask? {
        val type = SegmentationMask::class.java
        result.javaClass.methods
            .firstOrNull { it.parameterCount == 0 && type.isAssignableFrom(it.returnType) }
            ?.let { method -> return runCatching { method.invoke(result) as? SegmentationMask }.getOrNull() }
        result.javaClass.fields
            .firstOrNull { type.isAssignableFrom(it.type) }
            ?.let { field -> return runCatching { field.get(result) as? SegmentationMask }.getOrNull() }
        return null
    }

    /** Размытый вариант кадра для фона BLUR: уменьшение в 12 раз и обратно. */
    fun blurred(source: Bitmap): Bitmap {
        val small = Bitmap.createScaledBitmap(
            source, (source.width / 12).coerceAtLeast(2), (source.height / 12).coerceAtLeast(2), true,
        )
        val up = Bitmap.createScaledBitmap(small, source.width, source.height, true)
        if (up !== small) small.recycle()
        return up
    }

    private fun applyMask(source: Bitmap, background: Bitmap?, mask: SegmentationMask): Bitmap {
        val width = source.width
        val height = source.height
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val fg = IntArray(width * height)
        source.getPixels(fg, 0, width, 0, 0, width, height)
        val bgBitmap = when {
            background != null && background.width == width && background.height == height -> background
            background != null -> Bitmap.createScaledBitmap(background, width, height, true)
            else -> blurred(source)
        }
        val bg = IntArray(width * height)
        bgBitmap.getPixels(bg, 0, width, 0, 0, width, height)
        val buffer = mask.buffer
        buffer.rewind()
        val maskWidth = mask.width
        val maskHeight = mask.height
        val pixels = IntArray(width * height)
        // Маска может быть меньше кадра: тянем ближайшим соседом.
        for (y in 0 until height) {
            val maskY = (y * maskHeight / height).coerceAtMost(maskHeight - 1)
            for (x in 0 until width) {
                val maskX = (x * maskWidth / width).coerceAtMost(maskWidth - 1)
                val confidence = buffer.getFloat((maskY * maskWidth + maskX) * 4)
                val index = y * width + x
                val a = (confidence.coerceIn(0f, 1f) * 255).toInt()
                val f = fg[index]
                val b = bg[index]
                pixels[index] = (0xFF shl 24) or
                    (blend(f shr 16 and 0xFF, b shr 16 and 0xFF, a) shl 16) or
                    (blend(f shr 8 and 0xFF, b shr 8 and 0xFF, a) shl 8) or
                    blend(f and 0xFF, b and 0xFF, a)
            }
        }
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        if (bgBitmap !== background) bgBitmap.recycle()
        return out
    }

    private fun blend(fg: Int, bg: Int, alpha: Int): Int =
        (fg * alpha + bg * (255 - alpha)) / 255
}
