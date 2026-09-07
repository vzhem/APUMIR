package com.vladimir.messenger.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
// Встроенный ExifInterface: читает из потока начиная с API 24, а наш minSdk
// равен 26. Отдельная библиотека androidx для этого не нужна.
import android.media.ExifInterface
import android.graphics.Matrix
import java.io.File
import java.io.FileOutputStream

/**
 * Файлы аватара: снимок с камеры и результат обрезки.
 *
 * Аватар хранится как URI, а обрезка отдаёт Bitmap - его нужно куда-то
 * положить. Кладём в приватную папку приложения: снимки не засоряют галерею
 * телефона и уходят вместе с приложением при удалении.
 */
object AvatarFiles {

    private const val DIR = "avatar"
    private const val CAPTURE = "camera_shot.jpg"
    private const val CROPPED = "avatar_cropped.jpg"

    /** Куда камера положит снимок. Файл создаётся заранее - так требует Android. */
    fun captureTarget(context: Context): Uri {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val file = File(dir, CAPTURE)
        if (file.exists()) file.delete()
        file.createNewFile()
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }

    /**
     * Прочитать картинку по URI с поправкой на поворот.
     *
     * Снимок с камеры часто лежит боком: телефон пишет ориентацию отдельной
     * пометкой, а не поворачивает пиксели. Без этой поправки в обрезку попадал
     * бы повёрнутый кадр, и человек выбирал бы область вслепую.
     *
     * Картинка сразу уменьшается: полноразмерный снимок на 12 мегапикселей
     * занял бы под пятьдесят мегабайт памяти и мог уронить приложение.
     */
    fun readForCrop(context: Context, uri: Uri, maxSide: Int = 1080): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / sample > maxSide) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return@runCatching null

        val degrees = context.contentResolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f

        if (degrees == 0f) {
            bitmap
        } else {
            Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height,
                Matrix().apply { postRotate(degrees) },
                true,
            )
        }
    }.getOrNull()

    /** Сохранить обрезанный аватар и вернуть URI для AvatarHolder. */
    fun saveCropped(context: Context, bitmap: Bitmap): String? = runCatching {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        // Имя с меткой времени: одинаковое имя не обновляло бы картинку на
        // экране - система отдала бы прежнюю из кэша.
        val file = File(dir, "${System.currentTimeMillis()}_$CROPPED")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        // Прежние обрезки не копим.
        dir.listFiles()
            ?.filter { it.name.endsWith(CROPPED) && it.name != file.name }
            ?.forEach { it.delete() }

        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        ).toString()
    }.getOrNull()
}
