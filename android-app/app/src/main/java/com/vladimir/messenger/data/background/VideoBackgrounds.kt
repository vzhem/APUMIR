package com.vladimir.messenger.data.background

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import com.vladimir.messenger.R

/**
 * Фоны для видеозвонка и видеосторис: человек вырезается сегментацией,
 * позади него ставится настоящая фотография «как из жизни».
 *
 * Фотографии лежат в assets/backgrounds (по одной на фон, вертикальные) и
 * декодируются один раз; под размер кадра подгоняются обрезкой по центру,
 * без растягивания. Размытие (BLUR) фотографии не требует.
 */
enum class VideoBackground(
    val labelRes: Int,
    private val assetName: String?,
    private val fallbackColor: Int,
) {
    NONE(R.string.call_bg_none, null, Color.BLACK),
    BLUR(R.string.call_bg_blur, null, Color.DKGRAY),
    OFFICE(R.string.call_bg_office, "office.jpg", Color.rgb(168, 180, 198)),
    BEACH(R.string.call_bg_beach, "beach.jpg", Color.rgb(94, 178, 228)),
    MOUNTAINS(R.string.call_bg_mountains, "mountains.jpg", Color.rgb(120, 168, 220)),
    FOREST(R.string.call_bg_forest, "forest.jpg", Color.rgb(52, 110, 56)),
    CITY(R.string.call_bg_city, "city.jpg", Color.rgb(86, 108, 166)),
    SUNSET(R.string.call_bg_sunset, "sunset.jpg", Color.rgb(252, 140, 84)),
    SPACE(R.string.call_bg_space, "space.jpg", Color.rgb(6, 8, 32)),
    CAFE(R.string.call_bg_cafe, "cafe.jpg", Color.rgb(166, 124, 92)),
    LIBRARY(R.string.call_bg_library, "library.jpg", Color.rgb(84, 56, 40)),
    AURORA(R.string.call_bg_aurora, "aurora.jpg", Color.rgb(8, 14, 44));

    val isActive: Boolean get() = this != NONE

    @Volatile
    private var decoded: Bitmap? = null

    /**
     * Фон точно под размер кадра [width]x[height]: фотография из assets,
     * обрезанная по центру под пропорции кадра. Для NONE и BLUR не вызывается.
     * Если фотографию почему-то не удалось прочитать — однотонная заливка,
     * фон всё равно не должен молча пропадать.
     */
    fun render(context: Context, width: Int, height: Int): Bitmap {
        val w = width.coerceAtLeast(2)
        val h = height.coerceAtLeast(2)
        val photo = decodedPhoto(context)
            ?: return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap ->
                Canvas(bitmap).drawColor(fallbackColor)
            }
        return centerCrop(photo, w, h)
    }

    private fun decodedPhoto(context: Context): Bitmap? {
        decoded?.let { return it }
        val name = assetName ?: return null
        val bitmap = runCatching {
            context.assets.open("backgrounds/$name").use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }.getOrNull() ?: return null
        decoded = bitmap
        return bitmap
    }

    /** Масштаб «заполнить кадр» + обрезка лишнего по центру, без искажений. */
    private fun centerCrop(source: Bitmap, width: Int, height: Int): Bitmap {
        val scale = maxOf(
            width.toFloat() / source.width,
            height.toFloat() / source.height,
        )
        val scaledWidth = (source.width * scale).toInt().coerceAtLeast(width)
        val scaledHeight = (source.height * scale).toInt().coerceAtLeast(height)
        val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
        val x = ((scaledWidth - width) / 2).coerceAtLeast(0)
        val y = ((scaledHeight - height) / 2).coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(scaled, x, y, width, height)
        if (cropped !== scaled && scaled !== source) scaled.recycle()
        return cropped
    }

    companion object {
        /** Все варианты для ленты выбора: без фона, размытие и десять фотографий. */
        val pickerEntries: List<VideoBackground> = entries
    }
}
