package com.vladimir.messenger.data.story

/** Private local metadata, never exported by diagnostics. Body remains in StoryStore. */
internal data class StoryDeliveryEntry(val storyId: String, val owner: String, val peer: String,
    val createdAtMs: Long, val revoke: Boolean = false, val nextPart: Int = 0, val nextAttemptAtMs: Long = 0)

internal class StoryDeliveryQueue(entries: List<StoryDeliveryEntry> = emptyList()) {
    private val rows = entries.filter { valid(it) }.distinctBy { Triple(it.owner, it.storyId, it.peer) }.take(MAX_ENTRIES).toMutableList()
    fun snapshot(): List<StoryDeliveryEntry> = rows.toList()
    fun enqueue(storyId: String, owner: String, peers: List<String>, createdAt: Long, revoke: Boolean): Boolean {
        val desired = peers.filter { it.length in 1..256 }.distinct().map { StoryDeliveryEntry(storyId, owner, it, createdAt, revoke) }
        if (desired.any { !valid(it) }) return false
        val kept = rows.filterNot { it.owner == owner && it.storyId == storyId && (revoke || !it.revoke) }
        if (kept.size + desired.size > MAX_ENTRIES) {
            if (revoke) { rows.clear(); rows.addAll(kept); rows.addAll(desired.take(MAX_ENTRIES - kept.size)) }
            return false
        }
        // Do not resurrect an older publish after a revoke for the same story/peer.
        if (!revoke && rows.any { it.owner == owner && it.storyId == storyId && it.revoke }) return false
        rows.clear(); rows.addAll(kept); rows.addAll(desired); return true
    }
    fun purge(now: Long): Int {
        val old = rows.size
        rows.removeAll { now - it.createdAtMs >= TTL_MS || it.createdAtMs > now + 300_000 }
        return old - rows.size
    }
    fun due(now: Long): List<StoryDeliveryEntry> = rows.filter { it.nextAttemptAtMs <= now }.take(16)
    fun contains(entry: StoryDeliveryEntry): Boolean = entry in rows
    fun result(entry: StoryDeliveryEntry, accepted: Boolean, packetCount: Int, now: Long) {
        val index = rows.indexOf(entry)
        if (index < 0) return // A revoke replaced this generation while send was in flight.
        rows.removeAt(index)
        if (accepted && entry.nextPart + 1 >= packetCount) return
        rows.add(if (accepted) entry.copy(nextPart = entry.nextPart + 1, nextAttemptAtMs = now)
            else entry.copy(nextAttemptAtMs = now + 60_000))
    }
    fun remove(entry: StoryDeliveryEntry) { rows.remove(entry) }
    companion object {
        const val MAX_ENTRIES = 256
        const val TTL_MS = 24L * 60 * 60 * 1000
        fun valid(entry: StoryDeliveryEntry) = entry.storyId.length in 1..100 && entry.owner.length in 1..256 &&
            entry.peer.length in 1..256 && entry.createdAtMs > 0 && entry.nextPart in 0..119 && entry.nextAttemptAtMs >= 0
    }
}
