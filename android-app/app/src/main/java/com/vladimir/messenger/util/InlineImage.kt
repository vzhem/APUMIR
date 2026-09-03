package com.vladimir.messenger.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Картинка прямо внутри текста поста или сообщения.
 *
 * Хранится отдельной последней строкой вида `APUIMG1:<jpeg в base64>`. Так
 * ничего не пришлось менять ни в базе, ни в доставке: пост остаётся обычным
 * текстом, а телефон постарше просто покажет непонятную строку вместо
 * картинки, но сам пост не потеряет.
 *
 * Размер жёстко ограничен: групповой конверт не длиннее 16 КБ, а текст в нём
 * ещё раз кодируется base64 (плюс треть). Поэтому картинку ужимаем, пока она
 * не влезет в [MAX_B64_CHARS], понижая сторону и качество.
 */
object InlineImage {

    const val MARKER = "APUIMG1:"

    /** Предел на саму строку base64 - с запасом под конверт группы. */
    const val MAX_B64_CHARS = 7000

    private val SIDES = intArrayOf(720, 560, 420, 320, 240)
    private val QUALITIES = intArrayOf(60, 50, 40, 30)

    /** Есть ли в тексте прикреплённая картинка. */
    fun hasImage(text: String): Boolean = text.contains("\n$MARKER") || text.startsWith(MARKER)

    /** Текст без служебной строки картинки. */
    fun stripImage(text: String): String =
        text.lineSequence().filterNot { it.startsWith(MARKER) }.joinToString("\n").trim()

    /** Строка base64 прикреплённой картинки или null. */
    fun extractB64(text: String): String? =
        text.lineSequence().firstOrNull { it.startsWith(MARKER) }
            ?.removePrefix(MARKER)
            ?.takeIf { it.isNotBlank() }

    /** Приклеить картинку к тексту отдельной строкой. */
    fun attach(text: String, dataB64: String): String {
        val body = stripImage(text)
        return if (body.isEmpty()) MARKER + dataB64 else body + "\n" + MARKER + dataB64
    }

    /**
     * Сжать выбранную из галереи картинку до строки base64.
     *
     * Вызывать только вне главного потока: чтение и перекодирование картинки -
     * работа с диском.
     */
    fun compressUri(context: Context, uri: Uri): String? = try {
        val source = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        }
        if (source == null) null else encodeWithinLimit(source)
    } catch (e: Exception) {
        null
    }

    private fun encodeWithinLimit(source: Bitmap): String? {
        for (side in SIDES) {
            val scaled = scaleToFit(source, side)
            for (quality in QUALITIES) {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                val encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                if (encoded.length <= MAX_B64_CHARS) return encoded
            }
        }
        return null
    }

    private fun scaleToFit(source: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxSide) return source
        val ratio = maxSide.toFloat() / longest.toFloat()
        val width = (source.width * ratio).toInt().coerceAtLeast(1)
        val height = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }
}
