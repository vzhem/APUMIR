package com.vladimir.messenger.data.reaction

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Конверт реакции на сообщение.
 *
 * Формат один и тот же для личных чатов и для групп - получателю не нужно
 * знать заранее, откуда сообщение:
 *
 *   APUREACT1|<chatId>|<messageId>|<эмодзи в base64url>|<1 поставить, 0 снять>|<время>
 *
 * Значок кодируется base64url: в нём бывает символ `|`, а разбор идёт по нему.
 */
object ReactionWire {

    const val PREFIX = "APUREACT1"
    const val MAX_ENVELOPE_CHARS = 512

    data class Packet(
        val chatId: String,
        val messageId: String,
        val emoji: String,
        val added: Boolean,
        val atMs: Long,
    )

    fun isReactionPacket(text: String?): Boolean =
        text != null && text.length <= MAX_ENVELOPE_CHARS && text.startsWith("$PREFIX|")

    fun build(chatId: String, messageId: String, emoji: String, added: Boolean, atMs: Long): String =
        "$PREFIX|$chatId|$messageId|${encode(emoji)}|${if (added) 1 else 0}|$atMs"

    fun parse(text: String?): Packet? {
        if (!isReactionPacket(text)) return null
        val parts = text!!.split('|')
        if (parts.size != 6) return null
        val chatId = parts[1]
        val messageId = parts[2]
        if (chatId.isBlank() || messageId.isBlank()) return null
        val emoji = decode(parts[3])?.takeIf { it.isNotBlank() } ?: return null
        val added = when (parts[4]) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        val atMs = parts[5].toLongOrNull() ?: return null
        return Packet(chatId, messageId, emoji, added, atMs)
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String? = try {
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** Значки, которые предлагаем в окне выбора. */
object ReactionPalette {
    val EMOJI: List<String> = listOf("👍", "👎", "❤️", "🔥", "😂", "😮", "😢", "🙏")
}
