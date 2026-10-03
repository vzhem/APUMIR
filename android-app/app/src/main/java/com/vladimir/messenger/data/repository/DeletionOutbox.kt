package com.vladimir.messenger.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Очередь «удалить у всех» (раунд 137).
 *
 * Просьба владельца: команду отправил - и пусть она выполняется через тех,
 * кто в сети; если никого нет - хранится на телефонах и доходит, как только
 * появится связь. Команда удаления маленькая и идемпотентная, поэтому её
 * можно хранить и досылать без подтверждений от каждого:
 *
 *  - «direct» - конверт APUDEL1 собеседнику личного чата; ретранслировать
 *    его третий телефон не может (конверт зашифрован для получателя), зато
 *    очередь досылает его сама, как только собеседник появился;
 *  - «group» - пакет msdel; его охотно ретранслирует каждый получатель
 *    (в GroupRepository), а очередь досылает тем, до кого эпидемия пока
 *    не добралась.
 *
 * Записи старше TTL удаляются: у телефонов, не получивших удаление за
 * неделю, оно всё равно уже выглядит как история, а бесконечные досылки
 * забивают сеть. Формат - как у ApkUpdateStore: строка на запись, поля
 * через `|`, атомарная перезапись.
 */
@Singleton
class DeletionOutbox @Inject constructor(
    @ApplicationContext context: Context,
) {
    data class Entry(
        /** Идентификатор УДАЛЯЕМОГО сообщения. */
        val targetId: String,
        /** "direct" либо "group". */
        val kind: String,
        /** Личный чат (id) или группа (id), где лежит сообщение. */
        val chatId: String,
        /** direct: nodeId собеседника; group: пусто. */
        val peerId: String,
        /** Кто удалял (автор или владелец) - для msdel-конвертов. */
        val deleterId: String,
        val atMs: Long,
        var lastTryMs: Long,
        var attempts: Int,
        /** Кому уже досылали (группа): повторно не дёргаем. */
        var tried: List<String>,
    )

    private val root = File(context.noBackupFilesDir, DIR_NAME)

    @Synchronized
    fun add(entry: Entry) {
        val next = all().filterNot { it.kind == entry.kind && it.chatId == entry.chatId && it.targetId == entry.targetId }
        writeAll(next + entry)
    }

    @Synchronized
    fun all(): List<Entry> {
        val file = File(root, FILE_NAME)
        if (!file.isFile) return emptyList()
        val lines = runCatching { file.readLines(StandardCharsets.UTF_8) }.getOrElse { return emptyList() }
        if (lines.firstOrNull()?.trim() != HEADER) return emptyList()
        return lines.asSequence().drop(1).mapNotNull { parse(it) }.toList()
    }

    /** Записи, по которым пора сделать попытку доставки. */
    @Synchronized
    fun due(now: Long, minIntervalMs: Long, ttlMs: Long): List<Entry> {
        val fresh = all().filter { now - it.atMs <= ttlMs }
        if (fresh.size != all().size) saveAll(fresh)
        return fresh.filter { now - it.lastTryMs >= minIntervalMs }
    }

    @Synchronized
    fun markTried(entry: Entry, now: Long, peer: String? = null) {
        val current = all().firstOrNull {
            it.kind == entry.kind && it.chatId == entry.chatId && it.targetId == entry.targetId
        } ?: return
        current.lastTryMs = now
        current.attempts += 1
        if (!peer.isNullOrBlank() && peer !in current.tried) {
            current.tried = current.tried + peer
        }
        saveAll(all())
    }

    @Synchronized
    fun remove(kind: String, chatId: String, targetId: String) {
        val next = all().filterNot { it.kind == kind && it.chatId == chatId && it.targetId == targetId }
        if (next.size != all().size) saveAll(next)
    }

    @Synchronized
    fun removeByTarget(kind: String, targetId: String) {
        val next = all().filterNot { it.kind == kind && it.targetId == targetId }
        if (next.size != all().size) saveAll(next)
    }

    @Synchronized
    private fun saveAll(entries: List<Entry>) = writeAll(entries)

    private fun writeAll(entries: List<Entry>) {
        check(root.mkdirs() || root.isDirectory) { "Cannot create deletion outbox directory" }
        val file = File(root, FILE_NAME)
        val body = buildString {
            append(HEADER).append('\n')
            for (entry in entries) append(format(entry)).append('\n')
        }
        val temporary = File(root, FILE_NAME + ".tmp")
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
        HEADER + "|" + entry.kind + "|" + encode(entry.chatId) + "|" + encode(entry.targetId) + "|" +
            encode(entry.peerId) + "|" + encode(entry.deleterId) + "|" + entry.atMs + "|" +
            entry.lastTryMs + "|" + entry.attempts + "|" +
            entry.tried.joinToString(";") { encode(it) }

    private fun parse(line: String): Entry? {
        val parts = line.split('|')
        if (parts.size != 10) return null
        val kind = parts[1]
        if (kind != KIND_DIRECT && kind != KIND_GROUP) return null
        val chatId = decode(parts[2]) ?: return null
        val targetId = decode(parts[3]) ?: return null
        val peerId = decode(parts[4]) ?: return null
        val deleterId = decode(parts[5]) ?: return null
        val at = parts[6].toLongOrNull() ?: return null
        val lastTry = parts[7].toLongOrNull() ?: return null
        val attempts = parts[8].toIntOrNull() ?: return null
        val tried = parts[9].split(';').filter { it.isNotBlank() }.mapNotNull { decode(it) }
        if (chatId.isBlank() || targetId.isBlank() || at < 0L || lastTry < 0L || attempts < 0) return null
        return Entry(targetId, kind, chatId, peerId, deleterId, at, lastTry, attempts, tried)
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String? = try {
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        private const val DIR_NAME = "deletion_outbox/v1"
        private const val FILE_NAME = "outbox.v1"
        const val HEADER = "APUDELQ1"
        const val KIND_DIRECT = "direct"
        const val KIND_GROUP = "group"
    }
}
