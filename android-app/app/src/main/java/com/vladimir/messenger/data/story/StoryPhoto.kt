package com.vladimir.messenger.data.story

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

/** Decode with sampling before allocating, orient, resize, strip EXIF and cap wire size. */
object StoryPhoto {
    const val MAX_B64 = 160_000

    /** Длинная сторона снимка, с которым работает редактор: больше не нужно. */
    const val SOURCE_MAX_SIDE = 2048

    /** A private, temporary destination for a CameraX story capture. */
    class CaptureTarget internal constructor(val uri: Uri, internal val file: File) {
        fun delete() {
            file.delete()
        }
    }

    fun createCaptureTarget(context: Context): CaptureTarget {
        val dir = File(context.cacheDir, "story-camera").apply {
            check(mkdirs() || isDirectory) { "Cannot create story camera directory" }
        }
        val file = File.createTempFile("shot-", ".jpg", dir)
        return try {
            CaptureTarget(
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file),
                file,
            )
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    /**
     * Открыть выбранное фото для редактора: один декод, уже повёрнутый по EXIF.
     *
     * Источник читается ровно один раз, во временную копию: часть галерей и
     * облачных провайдеров отдаёт поток только при первом открытии, а подготовке
     * нужны три чтения (размеры, кадр, EXIF). Раньше повторное открытие падало,
     * фото к истории не прикреплялось - и она уходила друзьям без картинки, как
     * «подпись без фото».
     *
     * Возвращается ориентированный снимок не крупнее [SOURCE_MAX_SIDE]: с него
     * считает и предпросмотр редактора, и готовый кадр. Декод ровно один: второй
     * дороги декодирования нет, иначе съёмка с камеры обходила бы редактор.
     */
    fun loadForEdit(context: Context, uri: Uri): Bitmap {
        val sourceDir = File(context.cacheDir, "story-source").apply {
            check(mkdirs() || isDirectory) { "Cannot create story source directory" }
        }
        val source = File.createTempFile("photo-", ".img", sourceDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                source.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalArgumentException("Cannot open photo")
            require(source.length() > 0L) { "Empty photo" }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            source.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > SOURCE_MAX_SIDE) sample *= 2
            val bitmap = source.inputStream().use {
                BitmapFactory.decodeStream(
                    it, null, BitmapFactory.Options().apply { inSampleSize = sample },
                )
            } ?: error("Cannot decode photo")
            try {
                val orientation = runCatching {
                    source.inputStream().use {
                        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
                    }
                }.getOrNull() ?: 1
                val matrix = Matrix().apply {
                    when (orientation) {
                        2 -> setScale(-1f, 1f)
                        3 -> setRotate(180f)
                        4 -> { setRotate(180f); postScale(-1f, 1f) }
                        5 -> { setRotate(90f); postScale(-1f, 1f) }
                        6 -> setRotate(90f)
                        7 -> { setRotate(-90f); postScale(-1f, 1f) }
                        8 -> setRotate(-90f)
                    }
                }
                val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (oriented !== bitmap) bitmap.recycle()
                return oriented
            } catch (error: Exception) {
                bitmap.recycle()
                throw error
            }
        } finally {
            // Копия источника нужна только на время подготовки.
            source.delete()
        }
    }

    /** Прежняя точка входа: фото к отправке без правок. */
    fun prepare(context: Context, uri: Uri): String {
        val loaded = loadForEdit(context, uri)
        return try {
            encode(loaded)
        } finally {
            // Снимок нужен был только на время подготовки.
            loaded.recycle()
        }
    }

    /**
     * Готовый кадр после редактора: обрезка, поворот и приближение, затем прежняя
     * лестница сжатия.
     *
     * Публикуется ровно то, что человек видел в предпросмотре: и предпросмотр
     * редактора, и эта функция рисуют одним [StoryCrop.draw].
     */
    fun publishEdited(source: Bitmap, state: StoryCrop.State): String {
        val size = StoryCrop.renderSize(state, source.width, source.height)
        val cropped = StoryCrop.render(source, state, size, StoryCrop.bitmapPaint())
        return try {
            encode(cropped)
        } finally {
            cropped.recycle()
        }
    }

    /**
     * Сжать и закодировать: стороны 1280→480, качество 85→40, пока строка влезает
     * в [MAX_B64]. Порядок важен - сначала уменьшаем сторону, потом качество:
     * так фото не мылится раньше времени.
     */
    fun encode(bitmap: Bitmap): String {
        for (side in listOf(1280, 1024, 800, 640, 480)) {
            val ratio = minOf(1f, side.toFloat() / maxOf(bitmap.width, bitmap.height))
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
            try {
                for (quality in listOf(85, 70, 55, 40)) {
                    val bytes = ByteArrayOutputStream().use { out ->
                        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                        out.toByteArray()
                    }
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    if (b64.length <= MAX_B64) return b64
                }
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        }
        error("Photo too large")
    }
}
