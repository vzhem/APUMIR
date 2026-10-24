package com.vladimir.messenger.data.pairing

import android.util.Base64

/**
 * Text form of pairing packets (P3 of docs/MULTI_DEVICE_IDENTITY.md) and of the QR payload.
 *
 * Packets look like `APUPAIR:<kind>:<base64url>`. The colon separator is used on purpose: the
 * direct transport wraps payloads as `sender|msgId|direct|payload`, so `|` must not appear inside.
 * The bytes themselves are authenticated by the pairing protocol in the Rust core.
 */
object DevicePairingWire {
    const val PACKET_PREFIX = "APUPAIR:"
    const val QR_PREFIX = "apupair:"
    const val KIND_REQUEST = "req"
    const val KIND_ANSWER = "ans"
    const val KIND_GRANT = "grant"
    const val KIND_SYNC = "sync"

    private const val FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    private const val MAX_PACKET_CHARS = 4096

    data class Packet(val kind: String, val bytes: ByteArray)

    fun isPacket(text: String): Boolean = text.startsWith(PACKET_PREFIX)

    fun encode(kind: String, bytes: ByteArray): String =
        PACKET_PREFIX + kind + ":" + Base64.encodeToString(bytes, FLAGS)

    fun decode(text: String): Packet? {
        if (!isPacket(text) || text.length > MAX_PACKET_CHARS) return null
        val parts = text.split(':', limit = 3)
        if (parts.size != 3) return null
        val kind = parts[1]
        if (kind != KIND_REQUEST && kind != KIND_ANSWER && kind != KIND_GRANT && kind != KIND_SYNC) return null
        return try {
            Packet(kind, Base64.decode(parts[2], FLAGS))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun qrPayload(offer: ByteArray): String = QR_PREFIX + Base64.encodeToString(offer, FLAGS)

    /**
     * Offer bytes from a scanned or pasted QR text, or null if it is not a pairing code.
     * Clipboard/OCR paths sometimes insert line breaks into the long payload, so whitespace
     * is ignored here. The six-digit comparison code is deliberately not accepted: it is
     * derived from the secret in this offer and cannot start a pairing session by itself.
     */
    fun parseQr(text: String): ByteArray? {
        val compact = text.filterNot { it.isWhitespace() }
        if (!compact.startsWith(QR_PREFIX) || compact.length > MAX_PACKET_CHARS) return null
        val encoded = compact.removePrefix(QR_PREFIX)
        if (encoded.isEmpty()) return null
        return try {
            Base64.decode(encoded, FLAGS).takeIf { it.isNotEmpty() }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** True for the human comparison code shown as either `649114` or `649 114`. */
    fun looksLikeComparisonCode(text: String): Boolean =
        text.filterNot { it.isWhitespace() }.matches(Regex("\\d{6}"))

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
