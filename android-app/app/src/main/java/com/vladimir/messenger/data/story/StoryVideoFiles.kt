package com.vladimir.messenger.data.story

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Private, bounded on-disk video staging; never load an MP4 into a String or Bundle. */
internal object StoryVideoFiles {
    const val MAX_SOURCE_BYTES = 256L * 1024 * 1024

    data class Source(val file: File, val durationMs: Long, val width: Int, val height: Int, val hasAudio: Boolean)

    fun directory(context: Context): File = File(context.cacheDir, "story-video-drafts").apply {
        check(isDirectory || mkdirs()) { "Не удалось создать папку видеоисторий" }
    }

    suspend fun import(context: Context, uri: Uri): Source {
        var staged: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val destination = File.createTempFile("source-", ".video", directory(context)).also { staged = it }
                try {
                    var total = 0L
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        destination.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= MAX_SOURCE_BYTES) { "Исходное видео больше 256 МБ" }
                                require(destination.parentFile!!.usableSpace > count + 1024 * 1024) { "Недостаточно места для видео" }
                                output.write(buffer, 0, count)
                            }
                        }
                    } ?: error("Не удалось открыть видео")
                    require(total > 0) { "Видео пустое" }
                    inspect(destination)
                } catch (error: Throwable) {
                    destination.delete()
                    throw error
                }
            }
        } catch (error: Throwable) {
            // withContext can be cancelled while dispatching its successful result back to UI.
            staged?.delete()
            throw error
        }
    }

    /** Call on IO. Reading metadata also verifies that the file has a video track. */
    fun inspect(file: File): Source {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            fun number(key: Int) = retriever.extractMetadata(key)?.toLongOrNull() ?: 0L
            val duration = number(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val width = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
            val height = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
            require(duration >= StoryVideoDraft.MIN_DURATION_MS && width in 1..8192 && height in 1..8192) {
                "Видео слишком короткое, повреждено или имеет неподдерживаемый размер"
            }
            return Source(file, duration, width, height,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes")
        } finally { retriever.release() }
    }

    suspend fun media(file: File): StoryVideoMedia = withContext(Dispatchers.IO) {
        val source = inspect(file)
        require(file.length() in 1..StoryVideoDraft.MAX_EXPORT_BYTES) { "Готовое видео больше 24 МБ" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        StoryVideoMedia(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) },
            file.length(), source.durationMs, source.width, source.height, source.hasAudio)
    }
}
