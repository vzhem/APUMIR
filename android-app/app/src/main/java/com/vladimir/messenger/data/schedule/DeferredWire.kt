package com.vladimir.messenger.data.schedule

import java.security.MessageDigest
import java.util.Base64

/** Unknown APUGRP1 kinds are swallowed by old clients, never rendered as text. */
object DeferredWire {
    const val DATA_PREFIX = "APUGRP1|defer1|"
    const val ACK_PREFIX = "APUGRP1|deferack1|"
    const val PROBE_PREFIX = "APUGRP1|deferprobe1|"
    const val READY_PREFIX = "APUGRP1|deferready1|"
    const val CANCEL_PREFIX = "APUGRP1|defercancel1|"
    const val CANCEL_ACK_PREFIX = "APUGRP1|defercancelack1|"
    const val MAX_TEXT_BYTES = 34_000
    // Text is the raw final field (split with a limit), avoiding a third base64 expansion.
    // Reserve room for sealing + the mesh relay's own base64 inside its 64 KiB frame.
    private const val MAX_WIRE_BYTES = 34_500
    private val idPattern = Regex("[a-zA-Z0-9_-]{1,100}")
    private val hashPattern = Regex("[0-9a-f]{64}")

    data class Letter(
        val id: String,
        val showAtMs: Long,
        val groupId: String,
        val topicId: String,
        val senderName: String,
        val text: String,
    )
    data class Receipt(val id: String, val digest: String)

    fun isPacket(text: String): Boolean = text.startsWith(DATA_PREFIX) || text.startsWith(ACK_PREFIX) ||
        text.startsWith(PROBE_PREFIX) || text.startsWith(READY_PREFIX) ||
        text.startsWith(CANCEL_PREFIX) || text.startsWith(CANCEL_ACK_PREFIX)

    fun encode(letter: Letter): String {
        require(valid(letter))
        val wire = DATA_PREFIX + listOf(
            letter.id, letter.showAtMs.toString(), b64(letter.groupId), b64(letter.topicId),
            b64(letter.senderName), letter.text,
        ).joinToString("|")
        require(wire.toByteArray(Charsets.UTF_8).size <= MAX_WIRE_BYTES)
        return wire
    }

    fun parse(text: String): Letter? {
        if (!text.startsWith(DATA_PREFIX) || text.length > MAX_WIRE_BYTES ||
            text.toByteArray(Charsets.UTF_8).size > MAX_WIRE_BYTES) return null
        return runCatching {
            val p = text.removePrefix(DATA_PREFIX).split('|', limit = 6)
            if (p.size != 6) return@runCatching null
            Letter(p[0], p[1].toLong(), unb64(p[2]), unb64(p[3]), unb64(p[4]), p[5])
                .takeIf { valid(it) && encode(it) == text }
        }.getOrNull()
    }

    fun receipt(letter: Letter): String = ACK_PREFIX + letter.id + "|" + digest(encode(letter))

    fun probe(letter: Letter): String = PROBE_PREFIX + letter.id + "|" + digest(encode(letter))
    fun ready(probe: Receipt): String = READY_PREFIX + probe.id + "|" + probe.digest
    fun cancel(letter: Letter): String = CANCEL_PREFIX + letter.id + "|" + digest(encode(letter))
    fun cancelAck(cancel: Receipt): String = CANCEL_ACK_PREFIX + cancel.id + "|" + cancel.digest
    fun parseCancel(text: String): Receipt? = parseControl(text, CANCEL_PREFIX)
    fun parseCancelAck(text: String): Receipt? = parseControl(text, CANCEL_ACK_PREFIX)

    /** Continuation rows are owned by the same cancellation as their head. */
    fun headId(messageId: String): String = messageId.replace(Regex("_part[1-8]$"), "")

    fun parseProbe(text: String): Receipt? = parseControl(text, PROBE_PREFIX)
    fun parseReady(text: String): Receipt? = parseControl(text, READY_PREFIX)
    fun parseReceipt(text: String): Receipt? = parseControl(text, ACK_PREFIX)

    private fun parseControl(text: String, prefix: String): Receipt? {
        if (!text.startsWith(prefix) || text.length > 220) return null
        val parts = text.removePrefix(prefix).split('|')
        return if (parts.size == 2 && idPattern.matches(parts[0]) && hashPattern.matches(parts[1])) {
            Receipt(parts[0], parts[1])
        } else null
    }

    /** Transport IDs differ for early/ordinary packets and for each recipient. */
    fun transportId(id: String, peer: String, kind: String): String =
        digest("deferred-v1|$id|$peer|$kind")

    fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun due(showAtMs: Long, nowMs: Long): Boolean = nowMs >= showAtMs

    private fun valid(letter: Letter): Boolean =
        idPattern.matches(letter.id) && letter.showAtMs in 1L..253402300799999L &&
            letter.groupId.length <= 160 && letter.topicId.length <= 160 &&
            !letter.groupId.contains('|') && !letter.topicId.contains('|') &&
            (letter.groupId.isNotBlank() || letter.topicId.isEmpty()) &&
            letter.senderName.length <= 160 && letter.text.isNotBlank() &&
            letter.text.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES

    private fun b64(text: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(text.toByteArray(Charsets.UTF_8))
    private fun unb64(text: String): String = Base64.getUrlDecoder().decode(text).toString(Charsets.UTF_8)
}
