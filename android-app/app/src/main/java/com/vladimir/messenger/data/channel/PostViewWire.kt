package com.vladimir.messenger.data.channel

/**
 * Конверт «я прочитал пост канала».
 *
 * Формат: `APUVIEW1|<тема поста>|<когда>`
 *
 * Кто именно посмотрел, в конверте НЕ передаётся: читатель известен транспорту
 * по отправителю. Иначе один узел прислал бы пачку просмотров от вымышленных
 * имён и накрутил счётчик.
 */
object PostViewWire {
    const val PREFIX = "APUVIEW1"

    private const val MAX_ENVELOPE_CHARS = 512

    fun isViewPacket(text: String?): Boolean =
        text != null && text.length <= MAX_ENVELOPE_CHARS && text.startsWith("$PREFIX|")

    fun build(topicId: String, atMs: Long): String? {
        if (topicId.isBlank() || topicId.contains('|')) return null
        return "$PREFIX|$topicId|$atMs"
    }

    data class Packet(val topicId: String, val atMs: Long)

    fun parse(text: String): Packet? {
        if (!isViewPacket(text)) return null
        val parts = text.split('|')
        if (parts.size != 3) return null
        val topicId = parts[1]
        val atMs = parts[2].toLongOrNull() ?: return null
        if (topicId.isBlank()) return null
        return Packet(topicId = topicId, atMs = atMs)
    }
}
