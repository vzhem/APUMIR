package com.vladimir.messenger.data.chatclear

import java.security.MessageDigest
import java.util.UUID

/** Bounded non-destructive snapshot offers for a future negotiated clear. */
internal object ChatClearAgreementWire {
    private const val PREFIX = "APUDEL1|clearoffer3|"
    private const val MAX_PACKET_CHARS = 8192
    const val MAX_PAGES = ChatClearOutbox.MAX_ENTRIES
    private val digestPattern = Regex("[0-9a-f]{64}")

    data class Page(val operation: UUID, val index: Int, val total: Int, val digest: String, val ids: List<String>)

    fun pages(operation: UUID, ids: List<String>): List<Page> {
        require(ids.size <= MAX_PAGES * ChatClearWire.MAX_IDS)
        val sorted = ids.distinct().sorted()
        val digest = digest(sorted)
        val chunks = sorted.chunked(ChatClearWire.MAX_IDS).ifEmpty { listOf(emptyList()) }
        return chunks.mapIndexed { index, chunk -> Page(operation, index, chunks.size, digest, chunk).also { encode(it) } }
    }

    fun encode(page: Page): String {
        require(page.total in 1..MAX_PAGES && page.index in 0 until page.total && digestPattern.matches(page.digest))
        require(page.ids.size <= ChatClearWire.MAX_IDS)
        require(page.ids.size == ChatClearWire.MAX_IDS || page.index == page.total - 1)
        require(page.ids.isNotEmpty() || (page.total == 1 && page.index == 0))
        val encoded = if (page.ids.isEmpty()) "-" else ChatClearWire.encode(ChatClearWire.Batch(page.operation, page.ids)).substringAfterLast('.')
        return "$PREFIX${page.operation}.${page.index}.${page.total}.${page.digest}.$encoded".also { require(it.length <= MAX_PACKET_CHARS) }
    }

    fun decode(text: String): Page? {
        if (text.length > MAX_PACKET_CHARS || !text.startsWith(PREFIX)) return null
        val parts = text.removePrefix(PREFIX).split('.')
        if (parts.size != 5) return null
        val operation = ChatClearWire.decodeAck("APUDEL1|clearack2|${parts[0]}") ?: return null
        val index = parts[1].toIntOrNull() ?: return null
        val total = parts[2].toIntOrNull() ?: return null
        if (index.toString() != parts[1] || total.toString() != parts[2]) return null
        val ids = if (parts[4] == "-") emptyList() else
            ChatClearWire.decode("APUDEL1|clear2|$operation.${parts[4]}")?.ids ?: return null
        val page = Page(operation, index, total, parts[3], ids)
        return try { if (encode(page) == text) page else null } catch (_: IllegalArgumentException) { null }
    }

    /** Hash canonical UTF-8 IDs with explicit count and lengths; ordering is deterministic. */
    fun digest(ids: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        fun addInt(value: Int) { md.update(byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())) }
        addInt(ids.size)
        for (id in ids) {
            val bytes = id.toByteArray(Charsets.UTF_8)
            addInt(bytes.size)
            md.update(bytes)
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
