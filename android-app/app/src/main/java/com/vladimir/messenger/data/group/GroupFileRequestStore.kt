package com.vladimir.messenger.data.group

import com.vladimir.messenger.util.GroupFileMarker
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/**
 * Мои просьбы о файлах групп на диске (рой, этап 10).
 *
 * До v11.70.20 просьба (`fwant`) жила только в памяти: после перезапуска
 * телефон забывал, что просил файл, и карточка снова предлагала «Скачать»,
 * хотя сид, возможно, уже готовил передачу. Теперь [GroupFileSwarm] пишет
 * сюда каждую просьбу с тем, у кого спрашивал и сколько раз, а при старте
 * читает обратно и продолжает круг сидов с того же места.
 *
 * Формат - строка на просьбу, поля через `|`, свободный текст в base64url
 * (без `=`), первой строкой заголовок [HEADER]. Файл маленький (сотни байт
 * на просьбу, не больше [MAX_RECORDS] просьб), пишется целиком во временный
 * файл и переименовывается атомарно. Испорченная строка пропускается, а не
 * роняет остальные. Никакого Android здесь нет - JVM-тест читает и пишет
 * его напрямую.
 */
class GroupFileRequestStore(private val file: File) {

    /** Одна просьба: что, где, у кого спрашивали и кого ещё знаем. */
    data class Record(
        val groupId: String,
        val info: GroupFileMarker.Info,
        val messageId: String,
        val startedAtMs: Long,
        val manual: Boolean,
        val attempts: Int,
        /** У кого спрашивали последним; пусто - ещё ни у кого. */
        val askedSeed: String,
        val askedAtMs: Long,
        /** Известные сиды файла, автор первым. */
        val seeds: List<String>,
    )

    @Synchronized
    fun load(): List<Record> {
        if (!file.isFile) return emptyList()
        val lines = runCatching { file.readLines(StandardCharsets.UTF_8) }.getOrElse { return emptyList() }
        if (lines.firstOrNull()?.trim() != HEADER) return emptyList()
        return lines.asSequence().drop(1).mapNotNull { parse(it) }.take(MAX_RECORDS).toList()
    }

    /** Переписать файл целиком; пустой список - файл убирается. */
    @Synchronized
    fun save(records: Collection<Record>) {
        if (records.isEmpty()) {
            file.delete()
            return
        }
        val parent = file.absoluteFile.parentFile
        if (parent != null) check(parent.mkdirs() || parent.isDirectory) { "Cannot create request store directory" }
        val body = buildString {
            append(HEADER).append('\n')
            for (record in records.take(MAX_RECORDS)) append(format(record)).append('\n')
        }
        val temporary = File(parent, file.name + ".tmp")
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

    internal fun format(record: Record): String = listOf(
        encode(record.groupId),
        record.info.sha256,
        record.info.sizeBytes.toString(),
        encode(record.info.mediaType),
        encode(record.info.displayName),
        encode(record.messageId),
        record.startedAtMs.toString(),
        if (record.manual) "1" else "0",
        record.attempts.toString(),
        encode(record.askedSeed),
        record.askedAtMs.toString(),
        record.seeds.filter { it.isNotBlank() && !it.contains(',') && !it.contains('|') }
            .take(MAX_SEEDS).joinToString(","),
    ).joinToString("|")

    internal fun parse(line: String): Record? {
        val cells = line.trimEnd().split('|')
        if (cells.size != 12) return null
        val groupId = decode(cells[0])?.takeIf { it.isNotBlank() } ?: return null
        val sha = cells[1]
        if (!GroupFileMarker.isSha256(sha)) return null
        val size = cells[2].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val mediaType = decode(cells[3])?.takeIf { it.isNotBlank() } ?: return null
        val name = decode(cells[4])?.takeIf { it.isNotBlank() } ?: return null
        val messageId = decode(cells[5])?.takeIf { it.isNotBlank() } ?: return null
        val startedAt = cells[6].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val manual = when (cells[7]) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        val attempts = cells[8].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val askedSeed = decode(cells[9]) ?: return null
        val askedAt = cells[10].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val seeds = cells[11].split(',').filter { it.isNotBlank() }.distinct().take(MAX_SEEDS)
        return Record(
            groupId = groupId,
            info = GroupFileMarker.Info(sha, size, mediaType, name),
            messageId = messageId,
            startedAtMs = startedAt,
            manual = manual,
            attempts = attempts,
            askedSeed = askedSeed,
            askedAtMs = askedAt,
            seeds = seeds,
        )
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    /** Пустая ячейка - пустая строка (пустой base64 законен). */
    private fun decode(value: String): String? = try {
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        const val HEADER = "apu-group-file-requests-v1"
        const val MAX_RECORDS = 200
        const val MAX_SEEDS = 16
    }
}
