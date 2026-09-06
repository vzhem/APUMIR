package com.vladimir.messenger.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Сжатие аватара для передачи по сети: любой URI (галерея или стандартный
 * набор) -> маленький JPEG 96x96 в base64 без переносов.
 */
object AvatarCompress {

    fun compressUri(context: Context, uri: String, size: Int = 96, quality: Int = 70): String? {
        return try {
            val bmp = context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                BitmapFactory.decodeStream(it)
            } ?: return null

            val square = cropToSquare(bmp)
            val scaled = if (square.width != size || square.height != size) {
                Bitmap.createScaledBitmap(square, size, size, true)
            } else {
                square
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Вырезать центральный квадрат.
     *
     * Раньше картинка любых пропорций ВТИСКИВАЛАСЬ в квадрат 96x96
     * (`createScaledBitmap` тянет по обеим сторонам независимо). Широкий снимок
     * сплющивался по горизонтали и вытягивался по вертикали, а в круглой рамке
     * это выглядело как чёрные полосы сверху и снизу и сжатое лицо.
     *
     * Берём наибольший квадрат из середины: пропорции сохраняются, обрезаются
     * только края - именно так ведут себя аватары в других мессенджерах.
     */
    private fun cropToSquare(source: Bitmap): Bitmap {
        val side = min(source.width, source.height)
        if (side <= 0 || (source.width == source.height)) return source
        // Центрируем: у портрета срезаем верх и низ поровну, у панорамы - бока.
        val left = max(0, (source.width - side) / 2)
        val top = max(0, (source.height - side) / 2)
        return Bitmap.createBitmap(source, left, top, side, side)
    }
}
