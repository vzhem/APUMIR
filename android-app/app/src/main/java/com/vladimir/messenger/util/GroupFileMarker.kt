package com.vladimir.messenger.util

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

/**
 * Файл, приложенный к сообщению группы (рой, этап 9).
 *
 * Сам файл по группе не рассылается: он идёт зашифрованными кусками
 * (`FileTransfer`) от того, у кого он есть, к тому, кто попросил. А вот
 * сообщение темы несёт только «визитку» файла - служебную строку
 * `APUFILE1:<sha256>:<байт>:<b64url(тип)>:<b64url(имя)>` последней строкой
 * текста. По ней участник понимает, что за файл обещан, и просит его у
 * автора и соседей (`fwant`), а получив - раздаёт дальше сам.
 *
 * Перед служебной строкой автор кладёт человеческую подпись
 * «📎 имя (размер)»: телефон старой версии служебных строк этого вида не
 * знает и показывает текст как есть - подпись делает такое сообщение
 * понятным и там. Новые версии подпись прячут ([stripCaption]) и рисуют
 * карточку файла.
 *
 * Разбор строгий: любое отклонение - «файла нет», строка остаётся текстом.
 */
object GroupFileMarker {

    const val PREFIX = "APUFILE1:"

    /** Имя файла в визитке - не длиннее, чем принимает манифест передачи. */
    const val MAX_NAME_BYTES = 255

    /** Тип файла (MIME) в визитке - как в манифесте передачи. */
    const val MAX_MEDIA_TYPE_BYTES = 127

    /** Больше этого размера визитка не описывает: терабайт - явно мусор. */
    const val MAX_SIZE_BYTES = 1L shl 40

    data class Info(
        val sha256: String,
        val sizeBytes: Long,
        val mediaType: String,
        val displayName: String,
    )

    /** Служебная строка визитки. */
    fun build(info: Info): String {
        require(isSha256(info.sha256)) { "Bad file sha256" }
        require(info.sizeBytes in 0..MAX_SIZE_BYTES) { "Bad file size" }
        require(info.displayName.isNotBlank()) { "Blank file name" }
        return PREFIX + info.sha256 + ":" + info.sizeBytes + ":" +
            encode(info.mediaType.ifBlank { "application/octet-stream" }) + ":" +
            encode(info.displayName)
    }

    /** Человеческая подпись, которую видят и старые версии. */
    fun caption(info: Info): String = "📎 " + info.displayName + " (" + formatSize(info.sizeBytes) + ")"

    /** Есть ли в тексте визитка файла. */
    fun has(text: String): Boolean = parse(text) != null

    /** Сама строка визитки из текста (как есть) или null, если её нет или она испорчена. */
    fun line(text: String): String? =
        text.lineSequence().firstOrNull { it.startsWith(PREFIX) }?.takeIf { parse(it) != null }

    /**
     * Текст сообщения с файлом: слова автора (если есть), затем подпись для
     * старых версий, затем визитка последней строкой.
     */
    fun compose(words: String, info: Info): String {
        val lines = ArrayList<String>(3)
        val body = words.trim()
        if (body.isNotEmpty()) lines.add(body)
        lines.add(caption(info))
        lines.add(build(info))
        return lines.joinToString("\n")
    }

    /** Разобрать визитку из текста сообщения; null - её нет или она испорчена. */
    fun parse(text: String): Info? {
        val line = text.lineSequence().firstOrNull { it.startsWith(PREFIX) } ?: return null
        val cells = line.substring(PREFIX.length).trimEnd().split(':')
        if (cells.size != 4) return null
        val sha = cells[0]
        if (!isSha256(sha)) return null
        val size = cells[1].toLongOrNull() ?: return null
        if (size < 0 || size > MAX_SIZE_BYTES) return null
        val mediaType = decode(cells[2])?.takeIf { it.isNotBlank() && it.toByteArray(StandardCharsets.UTF_8).size <= MAX_MEDIA_TYPE_BYTES }
            ?: return null
        val name = decode(cells[3])?.takeIf { it.isNotBlank() && it.toByteArray(StandardCharsets.UTF_8).size <= MAX_NAME_BYTES }
            ?: return null
        if (name.any { it < ' ' } || mediaType.any { it <= ' ' } || !mediaType.contains('/')) return null
        return Info(sha, size, mediaType, name)
    }

    /**
     * Текст без автоматической подписи «📎 имя (размер)»: новые версии
     * рисуют карточку и повторять подпись словами незачем. Подпись
     * пользователя (если он что-то дописал) остаётся.
     */
    fun stripCaption(bodyText: String, info: Info): String {
        val caption = caption(info)
        val lines = bodyText.split('\n')
        val index = lines.indexOfFirst { it.trim() == caption }
        if (index < 0) return bodyText
        return lines.filterIndexed { i, _ -> i != index }.joinToString("\n").trim()
    }

    /** Ключ файла в пределах группы: один и тот же файл в двух группах - два разных дела. */
    fun key(groupId: String, sha256: String): String = "$groupId:$sha256"

    fun isSha256(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * Размер для подписи. Всегда с точкой (Locale.ROOT): подпись пишет один
     * телефон, а прячет другой - с иным языком системы она иначе не совпала бы.
     */
    fun formatSize(totalBytes: Long): String = when {
        totalBytes >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f МБ", totalBytes / (1024.0 * 1024.0))
        totalBytes >= 1024 -> String.format(Locale.ROOT, "%.1f КБ", totalBytes / 1024.0)
        else -> "$totalBytes Б"
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String? = try {
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }
}
