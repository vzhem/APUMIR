package com.vladimir.messenger.data.heart

/**
 * Конверт «сердечко профилю».
 *
 * Формат: `APUHEART1|<чей профиль>|<1 поставил / 0 снял>|<когда>`
 *
 * Кто поставил, в конверте НЕ передаётся: отправитель известен транспорту, и
 * доверять имени из тела нельзя - иначе один человек накрутил бы себе рейтинг,
 * прислав пачку голосов от вымышленных узлов.
 */
object HeartWire {
    const val PREFIX = "APUHEART1"

    private const val MAX_ENVELOPE_CHARS = 512

    fun isHeartPacket(text: String?): Boolean =
        text != null && text.length <= MAX_ENVELOPE_CHARS && text.startsWith("$PREFIX|")

    fun build(ownerId: String, added: Boolean, atMs: Long): String? {
        if (ownerId.isBlank() || ownerId.contains('|')) return null
        return "$PREFIX|$ownerId|${if (added) 1 else 0}|$atMs"
    }

    data class Packet(
        val ownerId: String,
        val added: Boolean,
        val atMs: Long,
    )

    fun parse(text: String): Packet? {
        if (!isHeartPacket(text)) return null
        val parts = text.split('|')
        if (parts.size != 4) return null
        val ownerId = parts[1]
        val added = when (parts[2]) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        val atMs = parts[3].toLongOrNull() ?: return null
        if (ownerId.isBlank()) return null
        return Packet(ownerId = ownerId, added = added, atMs = atMs)
    }
}
