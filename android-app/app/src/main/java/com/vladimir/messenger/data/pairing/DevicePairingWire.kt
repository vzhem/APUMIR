package com.vladimir.messenger.data.pairing

import android.util.Base64
import java.security.MessageDigest

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
    /** Payload published by the primary on the short-code rendezvous topic. */
    const val RENDEZVOUS_WIRE_PREFIX = "APUPAIRCODE1"
    /** Event text emitted by the Rust core after a retained rendezvous offer arrives. */
    const val RENDEZVOUS_EVENT_PREFIX = "APUPAIRCODE1"
    const val KIND_REQUEST = "req"
    const val KIND_ANSWER = "ans"
    const val KIND_GRANT = "grant"
    const val KIND_SYNC = "sync"

    private const val FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    private const val MAX_PACKET_CHARS = 4096

    data class Packet(val kind: String, val bytes: ByteArray)

    data class RendezvousOffer(val code: String, val qrText: String)

    fun isPacket(text: String): Boolean = text.startsWith(PACKET_PREFIX)

    fun isRendezvousPacket(text: String): Boolean =
        text.startsWith("$RENDEZVOUS_EVENT_PREFIX:")

    fun isPairingText(text: String): Boolean = isPacket(text) || isRendezvousPacket(text)

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

    /**
     * A short rendezvous code is derived from the full offer only to find that offer on the
     * broker. It is not the SAS and never replaces the Rust MAC/SAS confirmation step.
     */
    fun pairingCode(qrText: String): String? {
        val offer = parseQr(qrText) ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(offer)
        val value = ((digest[0].toLong() and 0xffL) shl 24) or
            ((digest[1].toLong() and 0xffL) shl 16) or
            ((digest[2].toLong() and 0xffL) shl 8) or
            (digest[3].toLong() and 0xffL)
        return (value % 1_000_000L).toString().padStart(6, '0')
    }

    fun normalizePairingCode(text: String): String? {
        val compact = text.filterNot { it.isWhitespace() }
        return compact.takeIf { it.length == 6 && it.all { char -> char in '0'..'9' } }
    }

    fun formatPairingCode(code: String): String? = normalizePairingCode(code)?.let {
        it.substring(0, 3) + " " + it.substring(3)
    }

    /** Body sent to `p2pm2/pair/<code>`; the core validates the sender and code topic. */
    fun rendezvousPayload(senderNodeId: String, code: String, qrText: String): String? {
        val normalized = normalizePairingCode(code) ?: return null
        if (!senderNodeId.startsWith("pk_") || parseQr(qrText) == null) return null
        return "$RENDEZVOUS_WIRE_PREFIX|$senderNodeId|$normalized|$qrText"
    }

    /** Event text reconstructed by the Rust core from a retained rendezvous topic. */
    fun parseRendezvous(text: String): RendezvousOffer? {
        val parts = text.split(':', limit = 3)
        if (parts.size != 3 || parts[0] != RENDEZVOUS_EVENT_PREFIX) return null
        val code = normalizePairingCode(parts[1]) ?: return null
        if (parseQr(parts[2]) == null) return null
        return RendezvousOffer(code, parts[2])
    }

    /** True for the human comparison code shown as either `649114` or `649 114`. */
    fun looksLikeComparisonCode(text: String): Boolean =
        normalizePairingCode(text) != null

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
