package com.vladimir.messenger.ui.components

// =============================================================================
// STICKERANIMATED.KT - анимированный стикер любого вида (раунд 170)
// =============================================================================
// Gif и анимированный webp крутит Coil (декодеры приложения). Видео-стикер
// .webm (формат анимированных стикеров генераторов вроде vibesticker) рисуем
// покадрово: кадры один раз извлекает MediaMetadataRetriever в фоне, Compose
// перебирает их как зацикленную анимацию. Прозрачный фон webm восстанавливаем
// ключевым цветом - тёмная подложка кадра становится прозрачной.
// =============================================================================

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Максимальный размер кадра стикера: экономим память в сетках и лентах. */
private const val STICKER_FRAME_SIZE = 256

/** Кадры темнее этой яркости (0..255 по самому тёмному каналу) - фон кадра. */
private const val STICKER_KEY_THRESHOLD = 38

/** Пауза между кадрами стикера, мс. */
private const val STICKER_FRAME_DELAY_MS = 90L

/** Кэш извлечённых кадров: путь файла -> кадры (несколько последних стикеров). */
private val stickerFrameCache = ConcurrentHashMap<String, List<Bitmap>>()

/**
 * Анимированный стикер из файла: gif/webp - через Coil (анимация родная),
 * webm - покадрово. Файлы библиотеки хранятся без расширения - вид определяем
 * по подписи содержимого.
 */
@Composable
fun StickerAnimated(
    file: File?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    if (file == null || !file.isFile) return
    if (com.vladimir.messenger.data.sticker.StickerLibrary.isWebmFile(file)) {
        WebmStickerFrames(file = file, contentDescription = contentDescription, modifier = modifier, contentScale = contentScale)
    } else {
        AsyncImage(
            model = file,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier,
        )
    }
}

@Composable
private fun WebmStickerFrames(
    file: File,
    contentDescription: String?,
    modifier: Modifier,
    contentScale: ContentScale,
) {
    val path = file.absolutePath
    var frames by remember(path) { mutableStateOf(stickerFrameCache[path] ?: emptyList()) }
    LaunchedEffect(path) {
        if (frames.isEmpty()) {
            val cached = stickerFrameCache[path]
            if (cached != null) {
                frames = cached
            } else {
                val extracted = withContext(Dispatchers.IO) { extractWebmFrames(file) }
                if (extracted.isNotEmpty()) {
                    trimStickerFrameCache()
                    stickerFrameCache[path] = extracted
                }
                frames = extracted
            }
        }
    }
    if (frames.isEmpty()) return
    var index by remember(path) { mutableIntStateOf(0) }
    LaunchedEffect(path, frames.size) {
        while (true) {
            delay(STICKER_FRAME_DELAY_MS)
            index = (index + 1) % frames.size
        }
    }
    Image(
        bitmap = frames[index % frames.size].asImageBitmap(),
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
    )
}

/** Держим в кэше кадры нескольких последних стикеров, память не течёт. */
private fun trimStickerFrameCache() {
    while (stickerFrameCache.size > 6) {
        val oldest = stickerFrameCache.keys.firstOrNull() ?: break
        stickerFrameCache.remove(oldest)
    }
}

/** Извлечь кадры видео-стикера: равномерно по длине, с прозрачным фоном. */
private fun extractWebmFrames(src: File): List<Bitmap> {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(src.absolutePath)
        val durationMs = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: return emptyList()
        if (durationMs <= 0L) return emptyList()
        val count = (durationMs / STICKER_FRAME_DELAY_MS)
            .coerceIn(4L, 12L)
            .toInt()
        val frames = ArrayList<Bitmap>(count)
        for (i in 0 until count) {
            val atUs = durationMs * 1000L * i / count
            val frame = retriever.getFrameAtTime(
                atUs,
                MediaMetadataRetriever.OPTION_CLOSEST,
            ) ?: continue
            frames.add(prepareStickerFrame(frame))
        }
        frames
    } catch (error: Exception) {
        emptyList()
    } finally {
        runCatching { retriever.release() }
    }
}

/** Уменьшить кадр и снять тёмную подложку -> прозрачный фон стикера. */
private fun prepareStickerFrame(frame: Bitmap): Bitmap {
    val scaled = scaleDown(frame, STICKER_FRAME_SIZE)
    return chromaKey(scaled)
}

private fun scaleDown(bmp: Bitmap, maxSize: Int): Bitmap {
    val largest = maxOf(bmp.width, bmp.height)
    if (largest <= maxSize) return bmp
    val scale = largest.toFloat() / maxSize
    val width = (bmp.width / scale).toInt().coerceAtLeast(1)
    val height = (bmp.height / scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bmp, width, height, true)
}

/** Тёмная подложка видео-кадра -> прозрачность (стикер парит в чате). */
private fun chromaKey(bmp: Bitmap): Bitmap {
    val safe = if (bmp.config == Bitmap.Config.HARDWARE) {
        bmp.copy(Bitmap.Config.ARGB_8888, false)
    } else {
        bmp
    }
    val width = safe.width
    val height = safe.height
    val pixels = IntArray(width * height)
    safe.getPixels(pixels, 0, width, 0, 0, width, height)
    for (i in pixels.indices) {
        val color = pixels[i]
        val red = (color shr 16) and 0xFF
        val green = (color shr 8) and 0xFF
        val blue = color and 0xFF
        if (maxOf(red, green, blue) < STICKER_KEY_THRESHOLD) {
            pixels[i] = 0
        }
    }
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    out.setPixels(pixels, 0, width, 0, 0, width, height)
    return out
}
