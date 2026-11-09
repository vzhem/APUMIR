package com.vladimir.messenger.data.chatclear

import java.util.Base64
import java.util.UUID

/** Experimental bounded wire format. It is not routed or sent until the delivery protocol is complete. */
internal object ChatClearWire {
    private const val PREFIX = "APUDEL1|clear2|"
    private const val ACK_PREFIX = "APUDEL1|clearack2|"
    private const val MIRROR_ACK_PREFIX = "APUDEL1|mirrorclearack2|"
    const val MAX_IDS = 32
    private const val MAX_ID_BYTES = 128
    private const val MAX_PACKET_CHARS = 8192

    data class Batch(val operation: UUID, val ids: List<String>)

    fun encode(batch: Batch): String {
        require(batch.ids.size in 1..MAX_IDS && batch.ids.toSet().size == batch.ids.size)
        val encoded = batch.ids.map { id ->
            val bytes = id.toByteArray(Charsets.UTF_8)
            require(id.isNotBlank() && bytes.size <= MAX_ID_BYTES)
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
        return "$PREFIX${batch.operation}.${encoded.joinToString(",")}".also {
            require(it.length <= MAX_PACKET_CHARS)
        }
    }

    fun decode(text: String): Batch? {
        if (text.length > MAX_PACKET_CHARS) return null
        if (!text.startsWith(PREFIX)) return null
        val parts = text.removePrefix(PREFIX).split('.')
        if (parts.size != 2) return null
        val operation = parseUuid(parts[0]) ?: return null
        val fields = parts[1].split(',')
        if (fields.size !in 1..MAX_IDS) return null
        val ids = fields.map { field ->
            if (field.isEmpty() || field.length > 171 || !field.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }) return null
            val bytes = try { Base64.getUrlDecoder().decode(field) } catch (_: IllegalArgumentException) { return null }
            if (bytes.size !in 1..MAX_ID_BYTES || Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) != field) return null
            val id = bytes.toString(Charsets.UTF_8)
            if (id.isBlank() || !id.toByteArray(Charsets.UTF_8).contentEquals(bytes)) return null
            id
        }
        if (ids.toSet().size != ids.size) return null
        return Batch(operation, ids)
    }

    fun encodeAck(operation: UUID): String = "$ACK_PREFIX$operation"

    fun decodeAck(text: String): UUID? =
        if (text.startsWith(ACK_PREFIX)) parseUuid(text.removePrefix(ACK_PREFIX)) else null

    fun encodeMirrorAck(operation: UUID): String = "$MIRROR_ACK_PREFIX$operation"

    fun decodeMirrorAck(text: String): UUID? =
        if (text.startsWith(MIRROR_ACK_PREFIX)) parseUuid(text.removePrefix(MIRROR_ACK_PREFIX)) else null

    fun isControl(text: String): Boolean =
        text.startsWith(PREFIX) || text.startsWith(ACK_PREFIX) || text.startsWith(MIRROR_ACK_PREFIX)

    private fun parseUuid(raw: String): UUID? {
        val value = try { UUID.fromString(raw) } catch (_: IllegalArgumentException) { return null }
        return value.takeIf { it.toString() == raw }
    }
}
