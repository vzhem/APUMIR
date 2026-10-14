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
     * Подготовить выбранное фото к отправке.
     *
     * Источник читается ровно один раз, во временную копию: часть галерей и
     * облачных провайдеров отдаёт поток только при первом открытии, а подготовке
     * нужны три чтения (размеры, кадр, EXIF). Раньше повторное открытие падало,
     * фото к истории не прикреплялось - и она уходила друзьям без картинки, как
     * «подпись без фото».
     */
    fun prepare(context: Context, uri: Uri): String {
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
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
            var bitmap = source.inputStream().use {
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
                if (oriented !== bitmap) { bitmap.recycle(); bitmap = oriented }
                for (side in listOf(1280, 1024, 800, 640, 480)) {
                    val ratio = minOf(1f, side.toFloat() / maxOf(bitmap.width, bitmap.height))
                    val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1),
                        (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
                    try {
                        for (quality in listOf(85, 70, 55, 40)) {
                            val bytes = ByteArrayOutputStream().use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, quality, out); out.toByteArray() }
                            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                            if (b64.length <= MAX_B64) return b64
                        }
                    } finally { if (scaled !== bitmap) scaled.recycle() }
                }
                error("Photo too large")
            } finally { bitmap.recycle() }
        } finally {
            // Копия источника нужна только на время подготовки.
            source.delete()
        }
    }
}
