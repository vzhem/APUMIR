package com.vladimir.messenger.data.chatclear

import java.util.Base64
import java.util.UUID

/** Experimental bounded wire format. It is not routed or sent until the delivery protocol is complete. */
internal object ChatClearWire {
    private const val PREFIX = "APUCLEAR1"
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
        return "$PREFIX|${batch.operation}|${encoded.joinToString(",")}".also {
            require(it.length <= MAX_PACKET_CHARS)
        }
    }

    fun decode(text: String): Batch? {
        if (text.length > MAX_PACKET_CHARS) return null
        val parts = text.split('|')
        if (parts.size != 3 || parts[0] != PREFIX) return null
        val operation = try { UUID.fromString(parts[1]) } catch (_: IllegalArgumentException) { return null }
        if (operation.toString() != parts[1]) return null
        val fields = parts[2].split(',')
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
}
