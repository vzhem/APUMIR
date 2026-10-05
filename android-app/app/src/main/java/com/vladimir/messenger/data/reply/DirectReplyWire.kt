package com.vladimir.messenger.data.reply

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

/** Wire format for reply metadata in one-to-one chats. */
object DirectReplyWire {
    const val PREFIX = "APUDMR1"
    const val MAX_PREVIEW_CHARS = 120
    private const val MAX_PACKET_CHARS = 4096

    /** Target selected in the composer. */
    data class Target(
        val messageId: String,
        val author: String,
        val text: String,
    )

    /** Metadata attached to a sent message. */
    data class Packet(
        val messageId: String,
        val replyToId: String,
        val replyAuthor: String,
        val replyText: String,
    )

    fun isPacket(raw: String?): Boolean =
        raw?.trim()?.startsWith("$PREFIX|") == true

    /**
     * Encodes the quote as a small standalone service packet so older clients
     * continue to display the actual message body rather than a wire marker.
     */
    fun build(
        messageId: String,
        replyToId: String,
        replyAuthor: String,
        replyText: String,
    ): String {
        require(messageId.isNotBlank()) { "reply message id is blank" }
        require(replyToId.isNotBlank()) { "reply target id is blank" }
        return listOf(messageId, replyToId, replyAuthor, preview(replyText))
            .joinToString(separator = "|", prefix = "$PREFIX|") { encode(it) }
    }

    fun parse(raw: String?): Packet? {
        val text = raw?.trim() ?: return null
        if (!isPacket(text) || text.length > MAX_PACKET_CHARS) return null
        val fields = text.split('|')
        if (fields.size != 5 || fields[0] != PREFIX) return null
        val decoded = fields.drop(1).map { decode(it) ?: return null }
        val messageId = decoded[0].takeIf { it.isNotBlank() } ?: return null
        val replyToId = decoded[1].takeIf { it.isNotBlank() } ?: return null
        return Packet(
            messageId = messageId,
            replyToId = replyToId,
            replyAuthor = decoded[2],
            replyText = preview(decoded[3]),
        )
    }

    /** Short quote text used both locally and in the wire packet. */
    fun preview(text: String): String {
        val oneLine = text.replace(Regex("\\s+"), " ").trim()
        return when {
            oneLine.isBlank() -> "Вложение"
            oneLine.length <= MAX_PREVIEW_CHARS -> oneLine
            else -> oneLine.take(MAX_PREVIEW_CHARS - 1) + "…"
        }
    }

    /** Stable, UUID-shaped transport id so retries are deduplicated by the core. */
    fun transportMessageId(replyMessageId: String): String =
        UUID.nameUUIDFromBytes("$PREFIX:$replyMessageId".toByteArray(StandardCharsets.UTF_8)).toString()

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String? = runCatching {
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    }.getOrNull()
}
