package com.vladimir.messenger.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

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
            val scaled = if (bmp.width != size || bmp.height != size) {
                Bitmap.createScaledBitmap(bmp, size, size, true)
            } else {
                bmp
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }
}
