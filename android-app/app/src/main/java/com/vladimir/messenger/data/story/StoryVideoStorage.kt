package com.vladimir.messenger.data.story

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Private durable MP4s, separate from the JSON story store and disposable editor cache. */
object StoryVideoStorage {
    private const val MAX_LIBRARY_BYTES = 256L * 1024 * 1024
    private val lock = Any()
    private val failed = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun failure(story: StoryStore.Story): String? = failed[key(story.authorId, story.id)]
    fun noteFailure(story: StoryStore.Story) {
        if (failed.size < 100) failed[key(story.authorId, story.id)] = "Не удалось проверить или сохранить видео. Проверьте свободное место и перезапустите приложение для повтора."
    }
    private fun directory(context: Context) = File(context.noBackupFilesDir, "story-videos-v1").apply {
        check(isDirectory || mkdirs())
    }
    private fun key(author: String, id: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$author\n$id".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun file(context: Context, story: StoryStore.Story): File? {
        if (story.video == null || System.currentTimeMillis() - story.createdAtMs !in 0 until StoryStore.LIFETIME_MS) return null
        return File(directory(context), key(story.authorId, story.id) + ".mp4")
            .takeIf { it.isFile && it.length() == story.video.sizeBytes }
    }
    suspend fun install(context: Context, author: String, id: String, source: File, expected: StoryVideoMedia) = withContext(Dispatchers.IO) {
        require(StoryVideoFiles.media(source) == expected) { "Видео не совпадает с описанием истории" }
        synchronized(lock) {
            val root = directory(context)
            val target = File(root, key(author, id) + ".mp4")
            val used = root.listFiles().orEmpty().filter { it != target }.sumOf { it.length() }
            require(used + source.length() <= MAX_LIBRARY_BYTES) { "Недостаточно места в хранилище видеоисторий" }
            val temp = File.createTempFile("pending-", ".tmp", root)
            try {
                source.inputStream().use { input -> FileOutputStream(temp).use { output -> input.copyTo(output); output.fd.sync() } }
                check(temp.renameTo(target)) { "Не удалось сохранить видеоисторию" }
                failed.remove(key(author, id))
            } finally { temp.delete() }
        }
    }
    fun remove(context: Context, author: String, id: String) = synchronized(lock) {
        failed.remove(key(author, id))
        File(directory(context), key(author, id) + ".mp4").delete()
    }
    fun sweep(context: Context, active: List<StoryStore.Story>) = synchronized(lock) {
        val alive = active.map { key(it.authorId, it.id) }.toSet()
        failed.keys.removeAll { it !in alive }
        val keep = active.filter { it.video != null }.map { key(it.authorId, it.id) + ".mp4" }.toSet()
        directory(context).listFiles().orEmpty().forEach { if (it.name !in keep && System.currentTimeMillis() - it.lastModified() > 60_000) it.delete() }
    }
}
