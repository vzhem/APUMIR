package com.vladimir.messenger.data.story

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/** Decode with sampling before allocating, orient, resize, strip EXIF and cap wire size. */
object StoryPhoto {
    const val MAX_B64 = 160_000

    fun prepare(context: Context, uri: Uri): String {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        var bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Cannot decode photo")
        try {
            val orientation = runCatching {
                resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }
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
    }
}
