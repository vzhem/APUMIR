package com.vladimir.messenger.data.group

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/**
 * Очередь исходящих заявок на вступление.
 *
 * Заявка на вступление - маленький пакет, и без неё абонент никуда не попадёт.
 * Если владелец или администраторы сейчас офлайн, прямая отправка не даёт
 * гарантии: ядро держит пакет только до первой неудачи. Поэтому заявка
 * сначала записывается на телефоне, а помпа сервиса повторяет её, пока владелец
 * не ответит или не истечёт срок. Одна запись на группу: заявка в одной группе
 * не влияет на другие группы и каналы.
 *
 * Формат как у [com.vladimir.messenger.data.repository.DeletionOutbox]: строка
 * на запись, поля через `|`, атомарная перезапись файла.
 */
class GroupJoinOutbox(private val root: File) {
    data class Entry(
        val groupId: String,
        val displayName: String,
        val note: String,
        val slug: String,
        /** Кому слать заявку: владелец по ссылке либо администраторы. */
        val recipients: List<String>,
        val atMs: Long,
        var lastTryMs: Long,
        var attempts: Int,
    )

    @Synchronized
    fun put(entry: Entry) {
        writeAll(all().filterNot { it.groupId == entry.groupId } + entry)
    }

    @Synchronized
    fun all(): List<Entry> {
        val file = File(root, FILE_NAME)
        if (!file.isFile) return emptyList()
        val lines = runCatching { file.readLines(StandardCharsets.UTF_8) }.getOrElse { return emptyList() }
        if (lines.firstOrNull()?.trim() != HEADER) return emptyList()
        return lines.asSequence().drop(1).mapNotNull { parse(it) }.toList()
    }

    /** Записи, по которым пора повторить отправку; просроченные убираются. */
    @Synchronized
    fun due(now: Long, minIntervalMs: Long, ttlMs: Long): List<Entry> {
        val all = all()
        val fresh = all.filter { now - it.atMs <= ttlMs }
        if (fresh.size != all.size) writeAll(fresh)
        return fresh.filter { now - it.lastTryMs >= minIntervalMs }
    }

    @Synchronized
    fun markTried(groupId: String, now: Long) {
        val next = all().map { entry ->
            if (entry.groupId == groupId) entry.copy(lastTryMs = now, attempts = entry.attempts + 1) else entry
        }
        writeAll(next)
    }

    @Synchronized
    fun remove(groupId: String) {
        val all = all()
        val next = all.filterNot { it.groupId == groupId }
        if (next.size != all.size) writeAll(next)
    }

    private fun writeAll(entries: List<Entry>) {
        check(root.mkdirs() || root.isDirectory) { "Cannot create group join outbox directory" }
        val body = buildString {
            append(HEADER).append('\n')
            for (entry in entries) append(format(entry)).append('\n')
        }
        val file = File(root, FILE_NAME)
        val temporary = File(root, "$FILE_NAME.tmp")
        try {
            temporary.writeText(body, StandardCharsets.UTF_8)
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    private fun format(entry: Entry): String =
        listOf(
            encode(entry.groupId),
            encode(entry.displayName),
            encode(entry.note),
            encode(entry.slug),
            entry.recipients.joinToString(";") { encode(it) },
            entry.atMs.toString(),
            entry.lastTryMs.toString(),
            entry.attempts.toString(),
        ).joinToString("|")

    private fun parse(line: String): Entry? {
        val parts = line.split('|')
        if (parts.size != 8) return null
        val groupId = decode(parts[0]) ?: return null
        val displayName = decode(parts[1]) ?: return null
        val note = decode(parts[2]) ?: return null
        val slug = decode(parts[3]) ?: return null
        val recipients = parts[4].split(';').filter { it.isNotBlank() }.mapNotNull { decode(it) }
        val at = parts[5].toLongOrNull() ?: return null
        val lastTry = parts[6].toLongOrNull() ?: return null
        val attempts = parts[7].toIntOrNull() ?: return null
        if (groupId.isBlank() || recipients.isEmpty() || at < 0L || lastTry < 0L || attempts < 0) return null
        return Entry(groupId, displayName, note, slug, recipients, at, lastTry, attempts)
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String? = try {
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        private const val HEADER = "APU-JOIN1"
        private const val FILE_NAME = "outbox.txt"
    }
}
