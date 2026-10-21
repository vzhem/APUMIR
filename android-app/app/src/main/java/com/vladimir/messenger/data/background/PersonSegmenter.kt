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

    private val streamSegmenter by lazy {
        Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
                .build(),
        )
    }

    private val singleImageSegmenter by lazy {
        Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .build(),
        )
    }

    /**
     * Последняя причина, почему фон не лёг (класс ошибки или статистика маски).
     * Показывается пользователю, чтобы сбой не был «тихим»: без этого на телефоне
     * невозможно отличить «модель не скачалась» от «маска пустая».
     */
    @Volatile
    var lastFailure: String? = null
        private set

    /**
     * Кадр с заменённым фоном или null, если сегментация сейчас недоступна.
     * [background] = null означает «размыть настоящий фон» (BLUR).
     *
     * Неудача НЕ запоминается навсегда: модель ML Kit при первом использовании
     * может ещё скачиваться, и следующий кадр обязан попробовать снова —
     * иначе фон молча не работал бы весь сеанс из-за одной ранней ошибки.
     */
    fun compositeOrNull(source: Bitmap, background: Bitmap?, singleImage: Boolean = false): Bitmap? {
        val mask = try {
            val segmenter = if (singleImage) singleImageSegmenter else streamSegmenter
            val result = Tasks.await(
                segmenter.process(InputImage.fromBitmap(source, 0)),
                8, TimeUnit.SECONDS,
            )
            extractMask(result)
        } catch (e: Exception) {
            lastFailure = "сегментация: ${e.javaClass.simpleName}: ${e.message?.take(110) ?: "без текста"}"
            return null
        } ?: run {
            lastFailure = "сегментация: маска не получена"
            return null
        }
        // Маску читаем последовательно, как в официальной документации ML Kit,
        // и сразу считаем долю «человека»: полностью пустая или полностью полная
        // маска означает, что фон лёг неправильно, — это ошибка, а не успех.
        val maskWidth = mask.width
        val maskHeight = mask.height
        if (maskWidth <= 0 || maskHeight <= 0) {
            lastFailure = "сегментация: пустой размер маски"
            return null
        }
        val confidences = FloatArray(maskWidth * maskHeight)
        val buffer = mask.buffer
        buffer.rewind()
        var foreground = 0
        for (i in confidences.indices) {
            if (!buffer.hasRemaining()) break
            val value = buffer.getFloat()
            confidences[i] = value
            if (value > 0.5f) foreground++
        }
        val foregroundFraction = foreground.toFloat() / confidences.size
        if (foregroundFraction < 0.001f || foregroundFraction > 0.995f) {
            lastFailure = "сегментация: человек не найден (маска ${(foregroundFraction * 100).toInt()}%, ${maskWidth}x$maskHeight)"
            return null
        }
        lastFailure = null
        return applyMask(source, background, confidences, maskWidth, maskHeight)
    }

    /** Как [compositeOrNull], но при неудаче возвращает копию исходного кадра. */
    fun composite(source: Bitmap, background: Bitmap?): Bitmap =
        compositeOrNull(source, background) ?: source.copy(Bitmap.Config.ARGB_8888, false)

    /**
     * Маска из результата сегментации. Selfie-сегментация возвращает саму маску,
     * а не обёртку: без первой проверки маска никогда не находилась и фон молча
     * не применялся. Остальное — запасной поиск по типу на случай другой версии.
     */
    private fun extractMask(result: Any): SegmentationMask? {
        if (result is SegmentationMask) return result
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

    private fun applyMask(
        source: Bitmap,
        background: Bitmap?,
        confidences: FloatArray,
        maskWidth: Int,
        maskHeight: Int,
    ): Bitmap {
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
        val pixels = IntArray(width * height)
        // Маска может отличаться от кадра по размеру: тянем ближайшим соседом.
        for (y in 0 until height) {
            val maskY = (y * maskHeight / height).coerceAtMost(maskHeight - 1)
            for (x in 0 until width) {
                val maskX = (x * maskWidth / width).coerceAtMost(maskWidth - 1)
                val confidence = confidences[maskY * maskWidth + maskX]
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
