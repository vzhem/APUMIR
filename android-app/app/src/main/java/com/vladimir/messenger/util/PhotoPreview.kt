package com.vladimir.messenger.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Копия фотографии для показа у отправителя: те же пропорции, что у
 * оригинала, длинная сторона не больше [maxSide], поворот по EXIF учтён.
 *
 * Раньше превью исходящей картинки в личном чате делал `AvatarCompress` -
 * он режет центральный квадрат (так и надо для аватара). Получатель открывал
 * полный файл, а отправитель - квадрат: у портретного снимка «сверху и снизу
 * обрезано». Здесь ничего не режется, только уменьшается.
 */
object PhotoPreview {

    /** Длинная сторона превью: хватает на экран телефона, а места - в разы меньше оригинала. */
    const val MAX_SIDE = 1280

    /**
     * Прочитать картинку по URI, уменьшить до [maxSide] по длинной стороне и
     * записать JPEG в [target]. Возвращает false, если картинка не читается.
     */
    fun write(context: Context, source: Uri, target: File, maxSide: Int = MAX_SIDE, quality: Int = 85): Boolean {
        val bitmap = decodeScaled(context, source, maxSide) ?: return false
        return try {
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out) }
            true
        } catch (e: Exception) {
            target.delete()
            false
        }
    }

    /** Уменьшенная (не обрезанная) копия картинки с поправкой на поворот, или null. */
    fun decodeScaled(context: Context, source: Uri, maxSide: Int): Bitmap? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return@runCatching null
        // Степень двойки при чтении: полный снимок на 12 МП в память не кладём.
        var sample = 1
        while (longest / (sample * 2) >= maxSide) sample *= 2
        val decoded = resolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@runCatching null
        // Точное уменьшение до maxSide, если после inSampleSize осталось больше.
        val scale = maxSide.toFloat() / maxOf(decoded.width, decoded.height)
        val fitted = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            decoded
        }
        val degrees = resolver.openInputStream(source)?.use { stream ->
            when (
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (degrees == 0f) {
            fitted
        } else {
            Bitmap.createBitmap(fitted, 0, 0, fitted.width, fitted.height, Matrix().apply { postRotate(degrees) }, true)
        }
    }.getOrNull()
}
