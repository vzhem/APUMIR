package com.vladimir.messenger.data.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.vladimir.messenger.data.group.GroupWallpaper
import com.vladimir.messenger.data.story.StoryPhoto
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Local overrides never enter group packets, backup of group metadata, or recipient fanout. */
object ConversationWallpaper {
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    private fun prefs(context: Context) = context.getSharedPreferences("conversation-wallpaper-v1", Context.MODE_PRIVATE)
    fun personal(context: Context, scope: String): String? = prefs(context).getString(scope, null)
    suspend fun setPersonal(context: Context, scope: String, value: String?) = withContext(Dispatchers.IO) {
        require(scope.isNotBlank() && scope.length <= 512)
        require(value == null || GroupWallpaper.valid(value))
        val editor = prefs(context).edit()
        if (value == null) editor.remove(scope) else editor.putString(scope, value)
        check(editor.commit()) { "Не удалось сохранить фон" }
        revision.update { it + 1 }
    }
    suspend fun import(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val source = StoryPhoto.loadForEdit(context, uri)
        try {
            var side = 1024
            while (side >= 128) {
                val scale = minOf(1f, side.toFloat() / maxOf(source.width, source.height))
                val bitmap = Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1),
                    (source.height * scale).toInt().coerceAtLeast(1), true)
                try {
                    for (quality in listOf(85, 70, 55, 40)) {
                        val out = ByteArrayOutputStream()
                        check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out))
                        if (out.size() <= GroupWallpaper.MAX_IMAGE_BYTES) {
                            return@withContext "image:" + Base64.getEncoder().encodeToString(out.toByteArray())
                        }
                    }
                } finally { if (bitmap !== source) bitmap.recycle() }
                side = side * 3 / 4
            }
            error("Не удалось подготовить изображение")
        } finally { source.recycle() }
    }
    fun decode(value: String): Bitmap? = runCatching {
        if (!value.startsWith("image:") || !GroupWallpaper.valid(value)) return null
        val bytes = Base64.getDecoder().decode(value.removePrefix("image:"))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..2048 || bounds.outHeight !in 1..2048) return null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}
