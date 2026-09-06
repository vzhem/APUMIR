package com.vladimir.messenger.data.receipt

/**
 * Конверт «я прочитал ваши сообщения».
 *
 * Зачем: доставленное и прочитанное раньше выглядели одинаково — две серые
 * галочки. Отправитель не знал, увидел ли собеседник сообщение.
 *
 * Формат: `APUREAD1|<чат отправителя>|<msgId>[,<msgId>…]|<когда>`
 *
 * Идентификатор чата берётся ЧУЖОЙ — тот, под которым сообщения завёл
 * отправитель. У получателя чат называется иначе (личные чаты создаются на
 * каждом телефоне отдельно), а отметить надо именно у отправителя.
 *
 * Конверт едет тем же путём, что и текст, поэтому он так же шифруется:
 * запечатывание стоит в общей точке отправки.
 */
object ReadReceiptWire {
    const val PREFIX = "APUREAD1"

    /** Столько сообщений максимум в одном отчёте: дальше режем на части. */
    const val MAX_IDS = 50

    private const val MAX_ENVELOPE_CHARS = 4096

    fun isReadReceipt(text: String?): Boolean =
        text != null && text.length <= MAX_ENVELOPE_CHARS && text.startsWith("$PREFIX|")

    fun build(chatId: String, messageIds: List<String>, atMs: Long): String? {
        val ids = messageIds
            .asSequence()
            .map { it.trim() }
            // Разделители внутри идентификатора разорвали бы разбор.
            .filter { it.isNotEmpty() && !it.contains('|') && !it.contains(',') }
            .distinct()
            .take(MAX_IDS)
            .toList()
        if (chatId.isBlank() || chatId.contains('|') || ids.isEmpty()) return null
        val envelope = "$PREFIX|$chatId|${ids.joinToString(",")}|$atMs"
        return envelope.takeIf { it.length <= MAX_ENVELOPE_CHARS }
    }

    data class Packet(
        val chatId: String,
        val messageIds: List<String>,
        val atMs: Long,
    )

    fun parse(text: String): Packet? {
        if (!isReadReceipt(text)) return null
        val parts = text.split('|')
        if (parts.size != 4) return null
        val chatId = parts[1]
        val ids = parts[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val atMs = parts[3].toLongOrNull() ?: return null
        if (chatId.isBlank() || ids.isEmpty() || ids.size > MAX_IDS) return null
        return Packet(chatId = chatId, messageIds = ids, atMs = atMs)
    }
}
