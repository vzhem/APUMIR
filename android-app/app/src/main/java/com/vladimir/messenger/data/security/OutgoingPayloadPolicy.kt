package com.vladimir.messenger.data.security

import com.vladimir.messenger.data.file.FileTransferWire
import com.vladimir.messenger.data.file.FileUdpWire
import com.vladimir.messenger.data.call.CallWire

/**
 * Only signed bootstrap bindings and address-discovery signals may cross the durable transport
 * before the peer's encryption binding is known. A chat message (including one sent just after
 * adding a QR contact) must wait for HELLO rather than silently falling back to cleartext.
 * This is deliberately an allowlist: new payload types are private unless explicitly reviewed.
 */
object OutgoingPayloadPolicy {
    /** Null means DO NOT invoke the native send/store path; the owner can retry later. */
    fun prepare(recipientId: String, text: String, seal: () -> String?): String? {
        if (!recipientId.startsWith("pk_")) return null
        if (SealedWire.isSealed(text)) return text.takeIf { SealedWire.decode(it) != null }
        if (isPublicControl(text)) return text
        val result = seal() ?: return null
        return result.takeIf { SealedWire.decode(it) != null }
    }

    fun isPublicControl(text: String): Boolean = when {
        FileTransferWire.isHelloText(text) ->
            runCatching { FileTransferWire.decodeHelloBinding(text) }.isSuccess
        isLanSignal(text) -> true
        FileUdpWire.isUdpSignalText(text) -> FileUdpWire.parseUdpSignal(text) != null
        else -> false
    }

    private fun isLanSignal(text: String): Boolean {
        if (text == "APULAN1|req") return true
        if (text.length > 256) return false
        val fields = text.split('|')
        return fields.size == 4 && fields[0] == "APULAN1" &&
            fields[1] in listOf("req", "offer") &&
            CallWire.isValidIpLiteral(fields[2]) &&
            (fields[3].toIntOrNull()?.let { it in 1024..65535 } == true)
    }
}
