package com.vladimir.messenger.data.repost

/**
 * Конверт «сообщение переслали ещё раз» — сигнал счётчика репостов.
 *
 * Формат один для личных чатов, групп и каналов:
 *
 *   APURPOST1|<chatId>|<messageId>|<eventId>|<время>
 *
 * Кто переслал, в конверте НЕ передаётся: автор известен транспорту по
 * отправителю, иначе один узел накрутил бы счётчик чужими именами.
 * [eventId] — номер события пересылки: та же пересылка, доехавшая дважды,
 * считается один раз.
 */
object RepostWire {

    const val PREFIX = "APURPOST1"
    const val MAX_ENVELOPE_CHARS = 512

    data class Packet(
        val chatId: String,
        val messageId: String,
        val eventId: String,
        val atMs: Long,
    )

    fun isRepostPacket(text: String?): Boolean =
        text != null && text.length <= MAX_ENVELOPE_CHARS && text.startsWith("$PREFIX|")

    fun build(chatId: String, messageId: String, eventId: String, atMs: Long): String? {
        if (chatId.isBlank() || messageId.isBlank() || eventId.isBlank()) return null
        if ('|' in chatId || '|' in messageId || '|' in eventId) return null
        return "$PREFIX|$chatId|$messageId|$eventId|$atMs"
    }

    fun parse(text: String?): Packet? {
        if (!isRepostPacket(text)) return null
        val parts = text!!.split('|')
        if (parts.size != 5) return null
        val atMs = parts[4].toLongOrNull() ?: return null
        if (parts[1].isBlank() || parts[2].isBlank() || parts[3].isBlank()) return null
        return Packet(parts[1], parts[2], parts[3], atMs)
    }
}
