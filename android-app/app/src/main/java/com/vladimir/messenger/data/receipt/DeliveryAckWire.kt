package com.vladimir.messenger.data.receipt

/**
 * Delivery ACK used by the direct, MQTT, Cloudflare-relay, and mirror paths.
 *
 * In v11.74.140 the ACK gained a direct path but reused the ordinary message
 * transport. A receiver without the v140+ filter therefore saw this control
 * payload as a chat message. `ack|<message-id>` is never chat text. Keep the loose
 * message-id parser compatible with IDs already emitted by the app; the
 * persisted-history matcher is deliberately narrower so cleanup only removes
 * the UUID-shaped ACK rows produced by the released clients.
 */
object DeliveryAckWire {
    const val PREFIX = "ack|"

    private const val MAX_PACKET_LENGTH = 80
    private const val HEX_GLOB = "[0-9A-Fa-f]"
    private val CANONICAL_UUID = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-" +
            "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )

    /** A reserved ACK prefix, including malformed packets, must never be saved as chat text. */
    fun isPacket(raw: String?): Boolean = raw?.trim()?.startsWith(PREFIX) == true

    /** Return the acknowledged message id, or null for malformed/non-ACK text. */
    fun messageId(raw: String?): String? {
        val packet = raw?.trim() ?: return null
        if (!packet.startsWith(PREFIX) || packet.length !in (PREFIX.length + 1)..MAX_PACKET_LENGTH) {
            return null
        }
        return packet.substring(PREFIX.length).trim().takeIf { it.isNotEmpty() }
    }

    /** Used only for one-time cleanup of old ACK rows in the local chat database. */
    fun isPersistedUuidAck(raw: String?): Boolean =
        messageId(raw)?.let { CANONICAL_UUID.matches(it) } == true

    /** SQLite GLOB for canonical UUID ACKs, with surrounding whitespace trimmed by the query. */
    val persistedUuidAckGlobPattern: String = buildString {
        append(PREFIX)
        append(HEX_GLOB.repeat(8))
        append('-')
        append(HEX_GLOB.repeat(4))
        append('-')
        append(HEX_GLOB.repeat(4))
        append('-')
        append(HEX_GLOB.repeat(4))
        append('-')
        append(HEX_GLOB.repeat(12))
    }
}
