package com.vladimir.messenger.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Разбор присланных аватаров - вне главного потока и один раз на картинку.
 *
 * Аватар приходит по сети строкой base64. Раньше каждая строка списка сама
 * разворачивала свою строку в картинку прямо во время отрисовки, на главном
 * потоке. Одна картинка - доли секунды, но при полусотне видимых строк это
 * складывается в заметное замирание, а при первом показе списка все строки
 * появляются разом. Так и набегало «Приложение не отвечает».
 *
 * Теперь разбор идёт в фоне, а готовая картинка кладётся в общий кэш: второй
 * раз ту же строку никто не разворачивает - ни при прокрутке, ни при
 * пересборке экрана, ни в другом списке.
 */
object AvatarBitmaps {

    /**
     * Кэш «строка base64 -> готовая картинка».
     *
     * Ключ - сама строка, поэтому смена аватара сама даёт промах кэша и новая
     * картинка разбирается заново. Размер ограничен: аватары маленькие, но
     * при тысячах контактов держать все сразу незачем.
     */
    private const val MAX_CACHED = 256

    private val cache = object : LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > MAX_CACHED
    }

    /** Готовая картинка, если её уже разбирали. */
    @Synchronized
    private fun cached(key: String): Bitmap? = cache[key]

    @Synchronized
    private fun store(key: String, bitmap: Bitmap) {
        cache[key] = bitmap
    }

    /** Разбор строки в картинку. Вызывать только вне главного потока. */
    private fun decode(dataB64: String): Bitmap? = try {
        val bytes = Base64.decode(dataB64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) {
        null
    }

    /** Готовая картинка по адресу файла, если её уже читали. */
    fun cachedUri(uri: String?): Bitmap? =
        uri?.takeIf { it.isNotBlank() }?.let { cached("uri:" + it) }

    /**
     * Прочитать картинку по адресу файла вне главного потока.
     *
     * Своё изображение (аватар, обои) хранится не строкой, а ссылкой на файл.
     * Открытие файла - блокирующая работа, поэтому в отрисовке ей не место.
     */
    suspend fun loadUri(context: android.content.Context, uri: String?): Bitmap? {
        val clean = uri?.takeIf { it.isNotBlank() } ?: return null
        val key = "uri:" + clean
        cached(key)?.let { return it }
        val decoded = withContext(Dispatchers.IO) {
            try {
                context.contentResolver
                    .openInputStream(android.net.Uri.parse(clean))
                    ?.use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                null
            }
        }
        if (decoded != null) store(key, decoded)
        return decoded
    }

    /**
     * Прочитать картинку файла вне главного потока.
     *
     * @param sampleSize 2 - уменьшать вдвое при чтении (для превью).
     */
    suspend fun loadFile(path: String?, sampleSize: Int = 1): Bitmap? {
        val clean = path?.takeIf { it.isNotBlank() } ?: return null
        val key = "file:" + clean + "@" + sampleSize
        cached(key)?.let { return it }
        val decoded = withContext(Dispatchers.IO) {
            try {
                val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                BitmapFactory.decodeFile(clean, opts)
            } catch (e: Exception) {
                null
            }
        }
        if (decoded != null) store(key, decoded)
        return decoded
    }

    /** Готовая картинка файла, если её уже читали. */
    fun cachedFile(path: String?, sampleSize: Int = 1): Bitmap? =
        path?.takeIf { it.isNotBlank() }?.let { cached("file:" + it + "@" + sampleSize) }

    /**
     * Картинка аватара для строки списка.
     *
     * Если картинка уже в кэше - отдаётся сразу, без единого кадра задержки.
     * Если нет - строка сначала показывает инициалы, а картинка появляется,
     * как только разберётся в фоне.
     */
    @Composable
    fun rememberAvatar(dataB64: String?): Bitmap? {
        if (dataB64.isNullOrBlank()) return null

        // Готовое из кэша ставим сразу начальным значением: иначе уже
        // разобранный аватар мигал бы инициалами на каждой перерисовке.
        var bitmap by remember(dataB64) { mutableStateOf(cached(dataB64)) }

        LaunchedEffect(dataB64) {
            if (bitmap != null) return@LaunchedEffect
            val decoded = withContext(Dispatchers.Default) { decode(dataB64) }
            if (decoded != null) {
                store(dataB64, decoded)
                bitmap = decoded
            }
        }
        return bitmap
    }
}
