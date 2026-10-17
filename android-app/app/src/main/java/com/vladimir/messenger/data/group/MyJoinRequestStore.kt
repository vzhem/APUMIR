package com.vladimir.messenger.data.group

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/**
 * История «Моих заявок» на телефоне абонента.
 *
 * Здесь лежит всё, что нужно для экрана заявки: куда подана, когда, что написано,
 * кому ушла, сколько участников было у группы, когда владелец получил заявку и
 * чем закончилось. Одна запись на группу. Хранится отдельно от очереди
 * ([GroupJoinOutbox]): очередь нужна только для повторов, а история живёт и после
 * ответа владельца.
 */
class MyJoinRequestStore(private val root: File) {
    data class Entry(
        val groupId: String,
        /** Название приходит от владельца; до ответа может быть пустым. */
        val title: String = "",
        val isChannel: Boolean = false,
        val memberCount: Int = 0,
        val ownerName: String = "",
        /** Когда группа создана (по данным владельца), 0 - неизвестно. */
        val groupCreatedAtMs: Long = 0L,
        val note: String = "",
        val requestedAtMs: Long,
        /** PENDING | APPROVED | REJECTED */
        val status: String = PENDING,
        val decidedAtMs: Long = 0L,
        /** Когда владелец подтвердил получение заявки карточкой группы, 0 - ещё нет. */
        val ownerReceivedAtMs: Long = 0L,
        /** Кому уходит заявка: владелец или администраторы. Карточку принимаем только от них. */
        val recipients: List<String> = emptyList(),
    )

    @Synchronized
    fun upsert(entry: Entry) {
        writeAll(all().filterNot { it.groupId == entry.groupId } + entry)
    }

    @Synchronized
    fun update(groupId: String, change: (Entry) -> Entry) {
        val current = all()
        val target = current.firstOrNull { it.groupId == groupId } ?: return
        writeAll(current.map { if (it.groupId == groupId) change(target) else it })
    }

    @Synchronized
    fun get(groupId: String): Entry? = all().firstOrNull { it.groupId == groupId }

    @Synchronized
    fun remove(groupId: String) {
        val current = all()
        val next = current.filterNot { it.groupId == groupId }
        if (next.size != current.size) writeAll(next)
    }

    @Synchronized
    fun all(): List<Entry> {
        val file = File(root, FILE_NAME)
        if (!file.isFile) return emptyList()
        val lines = runCatching { file.readLines(StandardCharsets.UTF_8) }.getOrElse { return emptyList() }
        if (lines.firstOrNull()?.trim() != HEADER) return emptyList()
        return lines.asSequence().drop(1).mapNotNull { parse(it) }.toList()
    }

    private fun writeAll(entries: List<Entry>) {
        check(root.mkdirs() || root.isDirectory) { "Cannot create join request store directory" }
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

    private fun format(e: Entry): String =
        listOf(
            encode(e.groupId),
            encode(e.title),
            if (e.isChannel) "1" else "0",
            e.memberCount.toString(),
            encode(e.ownerName),
            e.groupCreatedAtMs.toString(),
            encode(e.note),
            e.requestedAtMs.toString(),
            e.status,
            e.decidedAtMs.toString(),
            e.ownerReceivedAtMs.toString(),
            e.recipients.joinToString(";") { encode(it) },
        ).joinToString("|")

    private fun parse(line: String): Entry? {
        val p = line.split('|')
        if (p.size != 12) return null
        val groupId = decode(p[0]) ?: return null
        val title = decode(p[1]) ?: return null
        val channel = when (p[2]) { "1" -> true; "0" -> false; else -> return null }
        val count = p[3].toIntOrNull() ?: return null
        val ownerName = decode(p[4]) ?: return null
        val created = p[5].toLongOrNull() ?: return null
        val note = decode(p[6]) ?: return null
        val requested = p[7].toLongOrNull() ?: return null
        val status = p[8]
        if (status !in STATUSES) return null
        val decided = p[9].toLongOrNull() ?: return null
        val received = p[10].toLongOrNull() ?: return null
        val recipients = p[11].split(';').filter { it.isNotBlank() }.mapNotNull { decode(it) }
        if (groupId.isBlank() || count < 0 || requested < 0L) return null
        return Entry(groupId, title, channel, count, ownerName, created, note, requested, status, decided, received, recipients)
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String? = try {
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        const val PENDING = "PENDING"
        const val APPROVED = "APPROVED"
        const val REJECTED = "REJECTED"
        private val STATUSES = setOf(PENDING, APPROVED, REJECTED)
        private const val HEADER = "APU-MYJOIN1"
        private const val FILE_NAME = "my-requests.txt"
    }
}
