package com.vladimir.messenger.data.group

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Копии файлов, которые я раздаю группе как автор (рой, этап 9).
 *
 * Выбранный из системного окна файл доступен приложению только пока жив
 * разовый доступ, а просьбы участников (`fwant`) приходят часами и днями.
 * Поэтому автор кладёт копию сюда: `<root>/<группа>/<sha256>/<имя>`, и по
 * паре «группа + хэш» её находит любая просьба. Полученные от других файлы
 * здесь не лежат - они в `ReceivedFileStore`, и раздаются оттуда.
 *
 * Копии живут [DEFAULT_TTL_MS] (столько же, сколько сама передача) и
 * убираются [sweep]. Никаких путей и имён наружу не утекает: имя файла
 * очищается так же, как у полученных.
 */
class GroupFileStore(private val root: File) {

    init {
        check(!root.exists() || root.isDirectory) { "Group file root is not a directory" }
    }

    /**
     * Записать копию из [source] (поток закрывает вызывающий). Пишется во
     * временный файл и переименовывается атомарно: полуфабрикат никогда не
     * виден как готовая копия. Повторный вызов для того же файла - без
     * работы, если копия уже есть.
     */
    fun put(groupId: String, sha256: String, displayName: String, source: InputStream): File {
        val directory = directoryFor(groupId, sha256, create = true)
        val target = checkedChild(directory, sanitize(displayName))
        if (target.isFile) return target
        val temporary = checkedChild(directory, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                source.copyTo(output, COPY_BUFFER_BYTES)
                output.flush()
                output.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
        target.setLastModified(System.currentTimeMillis())
        return target
    }

    /** Копия файла [sha256] для группы [groupId] или null. */
    fun file(groupId: String, sha256: String): File? {
        val directory = directoryFor(groupId, sha256, create = false)
        if (!directory.isDirectory) return null
        return directory.listFiles()
            ?.firstOrNull { it.isFile && !it.name.startsWith('.') }
    }

    fun delete(groupId: String, sha256: String): Boolean {
        val directory = directoryFor(groupId, sha256, create = false)
        if (!directory.exists()) return true
        return directory.deleteRecursively()
    }

    /** Убрать копии старше [ttlMs]; возвращает, сколько файлов удалено. */
    fun sweep(nowMs: Long, ttlMs: Long = DEFAULT_TTL_MS): Int {
        val groups = root.listFiles() ?: return 0
        var removed = 0
        for (group in groups) {
            if (!group.isDirectory) continue
            val files = group.listFiles() ?: continue
            for (entry in files) {
                if (!entry.isDirectory) continue
                val newest = entry.listFiles()?.maxOfOrNull { it.lastModified() } ?: 0L
                if (nowMs - newest >= ttlMs) {
                    if (entry.deleteRecursively()) removed++
                }
            }
            if (group.listFiles()?.isEmpty() == true) group.delete()
        }
        return removed
    }

    /** Сколько байт лежит в копиях (строка в настройках, диагностика). */
    fun totalBytes(): Long {
        if (!root.isDirectory) return 0L
        var total = 0L
        root.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }

    private fun directoryFor(groupId: String, sha256: String, create: Boolean): File {
        require(sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' }) { "Bad sha256" }
        if (create) check(root.mkdirs() || root.isDirectory) { "Cannot create group file root" }
        val group = checkedChild(root, groupKey(groupId))
        val directory = checkedChild(group, sha256)
        if (create) check(directory.mkdirs() || directory.isDirectory) { "Cannot create group file directory" }
        return directory
    }

    /** Папка группы: идентификатор без посторонних знаков (иначе - его хэш). */
    internal fun groupKey(groupId: String): String {
        val safe = groupId.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }
        if (safe && groupId.isNotEmpty() && groupId.length <= 80) return groupId
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(groupId.toByteArray(Charsets.UTF_8))
        return "g_" + digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(32)
    }

    private fun checkedChild(parent: File, name: String): File {
        val child = File(parent, name)
        check(child.canonicalFile.toPath().parent == parent.canonicalFile.toPath()) {
            "Group file path escaped its parent"
        }
        check(!Files.isSymbolicLink(child.toPath())) { "Symbolic group file path rejected" }
        return child
    }

    internal fun sanitize(displayName: String): String {
        val cleaned = displayName.map { character ->
            if (character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' ||
                character == '.' || character == '-' || character == '_'
            ) character else '_'
        }.joinToString("").trimEnd('.').take(MAX_NAME_CHARS)
        if (cleaned.isBlank() || cleaned == ".") return "group_file"
        if (cleaned.startsWith('.')) return "f$cleaned"
        return cleaned
    }

    companion object {
        const val MAX_NAME_CHARS = 120
        const val COPY_BUFFER_BYTES = 64 * 1024
        /** Столько же живёт и сама передача (OutgoingFilePreparationService.TRANSFER_TTL_MS). */
        const val DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
