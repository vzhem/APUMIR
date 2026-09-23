package com.vladimir.messenger.data.sticker

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Свои стикеры (раунд 138): локальная библиотека картинок для панели
 * «Стикеры». Пока без роя - стикер уезжает в чат картинкой (файловой
 * машиной), а каталог стикеров живёт на телефоне:
 *
 *  - «Мои» - добавленные человеком (кнопка «+» в панели, из хранилища);
 *  - «Недавние» - что последним отправлял (до [MAX_RECENTS] штук).
 *
 * Формат - как у ApkUpdateStore: строка на запись, поля через `|`,
 * имя в base64url, атомарная перезапись, испорченная строка пропускается.
 */
@Singleton
class StickerLibrary @Inject constructor(
    @ApplicationContext context: Context,
) {
    /** Один стикер библиотеки: файл и метаданные для сетки панели. */
    data class StickerEntry(
        val sha256: String,
        val file: File,
        val name: String,
        val atMs: Long,
    )

    private val appContext = context.applicationContext
    private val root: File = File(appContext.noBackupFilesDir, DIR_NAME)

    // ── Мои стикеры ─────────────────────────────────────────────────────────

    @Synchronized
    fun all(): List<StickerEntry> {
        val file = File(root, INDEX_FILE)
        if (!file.isFile) return emptyList()
        val lines = runCatching { file.readLines(StandardCharsets.UTF_8) }.getOrElse { return emptyList() }
        if (lines.firstOrNull()?.trim() != INDEX_HEADER) return emptyList()
        return lines.asSequence().drop(1).mapNotNull { parse(it) }
            .sortedByDescending { it.atMs }
            .filter { it.file.isFile }
            .toList()
    }

    /**
     * Добавить стикер из хранилища (SAF/галерея). Читаем байты, считаем
     * sha256, кладём копию к себе (исходник может исчезнуть). Дубль по
     * sha256 не добавляется второй раз.
     */
    @Synchronized
    fun add(uri: android.net.Uri): StickerEntry? {
        val bytes = runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input -> input.readBytes() }
        }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val sha = sha256(bytes)
        check(root.mkdirs() || root.isDirectory) { "Cannot create sticker directory" }
        val file = File(root, "$sha.img")
        if (!file.isFile) {
            val temporary = File(root, "$sha.img.tmp")
            runCatching {
                temporary.writeBytes(bytes)
                try {
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }.onFailure {
                temporary.delete()
                return null
            }
        }
        if (all().any { it.sha256 == sha }) return entryOf(sha)
        val body = buildString {
            append(INDEX_HEADER).append('\n')
            for (entry in all()) append(format(entry)).append('\n')
            append(INDEX_HEADER).append('|').append(sha).append('|')
                .append(encode("sticker_${sha.take(8)}.png")).append('|')
                .append(System.currentTimeMillis()).append('\n')
        }
        return if (writeIndex(body)) entryOf(sha) else null
    }

    /** Стикер по sha (для «Недавних», чьи строки хранят только sha). */
    @Synchronized
    fun entryOf(sha256: String): StickerEntry? =
        all().firstOrNull { it.sha256 == sha256 }

    // ── Недавние ────────────────────────────────────────────────────────────

    /** Последние отправленные: sha + время, свежие вверху. */
    @Synchronized
    fun recents(): List<StickerEntry> {
        val file = File(root, RECENTS_FILE)
        if (!file.isFile) return emptyList()
        val lines = runCatching { file.readLines(StandardCharsets.UTF_8) }.getOrElse { return emptyList() }
        if (lines.firstOrNull()?.trim() != RECENTS_HEADER) return emptyList()
        return lines.asSequence().drop(1)
            .mapNotNull { row ->
                val sha = row.substringBefore('|')
                if (sha.isBlank()) null else sha
            }
            .mapNotNull { entryOf(it) }
            .take(MAX_RECENTS)
            .toList()
    }

    /** Запомнить отправленный стикер наверху «Недавних». */
    @Synchronized
    fun touch(sha256: String) {
        if (entryOf(sha256) == null) return
        val previous = runCatching {
            val file = File(root, RECENTS_FILE)
            if (!file.isFile) emptyList()
            else file.readLines(StandardCharsets.UTF_8)
                .drop(1)
                .map { it.substringBefore('|') }
                .filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
        val next = (listOf(sha256) + previous.filter { it != sha256 }).take(MAX_RECENTS)
        val body = buildString {
            append(RECENTS_HEADER).append('\n')
            for (sha in next) append(sha).append('|').append(System.currentTimeMillis()).append('\n')
        }
        write(RECENTS_FILE, body)
    }

    // ── Общее ───────────────────────────────────────────────────────────────

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun format(entry: StickerEntry): String =
        INDEX_HEADER + "|" + entry.sha256 + "|" + encode(entry.name) + "|" + entry.atMs

    private fun parse(line: String): StickerEntry? {
        val parts = line.split('|')
        if (parts.size != 4) return null
        val sha = parts[1]
        val name = decode(parts[2]) ?: return null
        val at = parts[3].toLongOrNull() ?: return null
        if (sha.length != 64 || sha.any { it !in '0'..'9' && it !in 'a'..'f' } ||
            name.isBlank() || at < 0L
        ) {
            return null
        }
        return StickerEntry(sha, File(root, "$sha.img"), name, at)
    }

    private fun writeIndex(body: String): Boolean = write(INDEX_FILE, body)

    private fun write(fileName: String, body: String): Boolean {
        check(root.mkdirs() || root.isDirectory) { "Cannot create sticker directory" }
        val file = File(root, fileName)
        val temporary = File(root, "$fileName.tmp")
        return try {
            temporary.writeText(body, StandardCharsets.UTF_8)
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } catch (error: Exception) {
            temporary.delete()
            false
        }
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String? = try {
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        private const val DIR_NAME = "stickers/v1"
        private const val INDEX_FILE = "index.v1"
        private const val INDEX_HEADER = "APUSTICK1"
        private const val RECENTS_FILE = "recents.v1"
        private const val RECENTS_HEADER = "APUSTICKREC1"

        /** Сколько «Недавних» помним. */
        const val MAX_RECENTS = 24
    }
}
