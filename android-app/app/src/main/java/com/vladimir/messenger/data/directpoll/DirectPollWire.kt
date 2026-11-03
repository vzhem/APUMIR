package com.vladimir.messenger.data.directpoll

/**
 * Конверт опроса ЛИЧНОГО чата.
 *
 * Почему не групповой `APUGRP1|poll`: групповой разбор ищет участника по
 * `groupId` и без группы молча выбрасывает пакет. Личный провод обязан вести
 * себя как реакция или геолокация — служебной строкой, которую приём съедает
 * до сохранения в переписку.
 *
 * Три вида пакетов (id полей — base64url, чтобы внутри не было разделителя `|`):
 *
 *   APUPOLL1|open|pollId|messageId|chatId|anon|вопрос|варианты|atMs
 *   APUPOLL1|vote|pollId|messageId|chatId|номера|atMs
 *   APUPOLL1|close|pollId|messageId|chatId|atMs
 *
 * `open` нужен потому, что вопрос уходит обычным сообщением и о вариантах
 * собеседник иначе не узнает. Он повторяет определение опроса целиком, поэтому
 * потерянный конверт лечится повторной досылкой, а не расхождением итогов.
 *
 * `pollId` выводится из id сообщения ([pollIdOf]): опрос нельзя перепутать с
 * другим и не нужна вторая случайность.
 */
object DirectPollWire {

    const val PREFIX = "APUPOLL1"

    /** Один конверт не должен занимать всю очередь доставки. */
    const val MAX_ENVELOPE_CHARS = 4096

    const val KIND_OPEN = "open"
    const val KIND_VOTE = "vote"
    const val KIND_CLOSE = "close"

    /** Разделитель вариантов внутри одного поля: `,` занята номерами голоса. */
    private const val OPTION_SEP = "~"

    data class Open(
        val pollId: String,
        val messageId: String,
        val chatId: String,
        val question: String,
        val options: List<String>,
        val anonymous: Boolean,
        val atMs: Long,
    )

    data class Vote(
        val pollId: String,
        val messageId: String,
        val chatId: String,
        val choices: List<Int>,
        val atMs: Long,
    )

    data class Close(
        val pollId: String,
        val messageId: String,
        val chatId: String,
        val atMs: Long,
    )

    /** id опроса выводится из id сообщения: повторная отправка не плодит дубли. */
    fun pollIdOf(messageId: String): String = "dp:$messageId"

    fun isPollPacket(text: String?): Boolean =
        text != null && text.length <= MAX_ENVELOPE_CHARS && text.startsWith("$PREFIX|")

    fun buildOpen(
        pollId: String,
        messageId: String,
        chatId: String,
        question: String,
        options: List<String>,
        anonymous: Boolean,
        atMs: Long,
    ): String = listOf(
        PREFIX,
        KIND_OPEN,
        enc(pollId),
        enc(messageId),
        enc(chatId),
        if (anonymous) "1" else "0",
        enc(question),
        options.joinToString(OPTION_SEP) { enc(it) },
        atMs.toString(),
    ).joinToString("|")

    fun buildVote(
        pollId: String,
        messageId: String,
        chatId: String,
        choices: List<Int>,
        atMs: Long,
    ): String = listOf(
        PREFIX,
        KIND_VOTE,
        enc(pollId),
        enc(messageId),
        enc(chatId),
        choices.distinct().sorted().joinToString(","),
        atMs.toString(),
    ).joinToString("|")

    fun buildClose(pollId: String, messageId: String, chatId: String, atMs: Long): String =
        listOf(PREFIX, KIND_CLOSE, enc(pollId), enc(messageId), enc(chatId), atMs.toString())
            .joinToString("|")

    fun parseOpen(text: String?): Open? {
        val parts = parts(text, KIND_OPEN, 9) ?: return null
        val ids = idsOf(parts) ?: return null
        val atMs = parts[8].toLongOrNull() ?: return null
        val question = dec(parts[6])
        if (question.isNullOrBlank()) return null
        val options = parts[7].split(OPTION_SEP)
            .mapNotNull { dec(it)?.trim() }
            .filter { it.isNotBlank() }
        if (options.size < DirectPollTally.MIN_OPTIONS) return null
        return Open(ids[0], ids[1], ids[2], question, options, parts[5] == "1", atMs)
    }

    fun parseVote(text: String?): Vote? {
        val parts = parts(text, KIND_VOTE, 7) ?: return null
        val ids = idsOf(parts) ?: return null
        val atMs = parts[6].toLongOrNull() ?: return null
        val choices = parts[5].split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it >= 0 }
            .distinct()
            .sorted()
        return Vote(ids[0], ids[1], ids[2], choices, atMs)
    }

    fun parseClose(text: String?): Close? {
        val parts = parts(text, KIND_CLOSE, 6) ?: return null
        val ids = idsOf(parts) ?: return null
        val atMs = parts[5].toLongOrNull() ?: return null
        return Close(ids[0], ids[1], ids[2], atMs)
    }

    /** Разбор общего каркаса: префикс, вид и ожидаемое число полей. */
    private fun parts(text: String?, kind: String, size: Int): List<String>? {
        if (!isPollPacket(text)) return null
        val chunks = text!!.split('|')
        if (chunks.size != size || chunks[1] != kind) return null
        return chunks
    }

    private fun idsOf(parts: List<String>): List<String>? {
        val pollId = dec(parts[2])
        val messageId = dec(parts[3])
        val chatId = dec(parts[4])
        if (pollId.isNullOrBlank() || messageId.isNullOrBlank() || chatId.isNullOrBlank()) return null
        return listOf(pollId, messageId, chatId)
    }

    internal fun enc(value: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))

    internal fun dec(value: String): String? = try {
        String(java.util.Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }
}
