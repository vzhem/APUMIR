package com.vladimir.messenger.data.story

/** Bounded, sender-scoped reassembly; accepts reordered chunks, rejects conflicting parts. */
internal class StoryPhotoParts {
    companion object {
        const val CHUNK = 2400
        const val MAX_SIZE = 160_000
        const val MAX_PARTS = 67
    }
    private data class Pending(val total: Int, val started: Long, val chunks: MutableMap<Int, String> = mutableMapOf())
    private val pending = linkedMapOf<Pair<String, String>, Pending>()

    @Synchronized
    fun receive(author: String, id: String, index: Int, total: Int, data: String, now: Long): String? {
        pending.entries.removeAll { now - it.value.started > 300_000 }
        if (id.length !in 1..100 || total !in 1..MAX_PARTS || index !in 0 until total ||
            data.isEmpty() || data.length > CHUNK || !data.all { it.isLetterOrDigit() && it.code < 128 || it == '+' || it == '/' || it == '=' }) return null
        val key = author to id
        if (key !in pending && pending.size >= 16) pending.remove(pending.keys.first())
        val item = pending.getOrPut(key) { Pending(total, now) }
        if (item.total != total || item.chunks[index]?.let { it != data } == true) {
            pending.remove(key); return null
        }
        item.chunks[index] = data
        if (item.chunks.values.sumOf { it.length } > MAX_SIZE) { pending.remove(key); return null }
        if (item.chunks.size != total) return null
        pending.remove(key)
        return (0 until total).joinToString("") { item.chunks.getValue(it) }
    }
}
