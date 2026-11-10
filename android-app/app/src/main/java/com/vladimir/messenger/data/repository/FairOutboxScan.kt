package com.vladimir.messenger.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A stable keyset cursor: duplicate timestamps never hide a queued row. */
internal data class OutboxPosition(val timestamp: Long, val id: String) {
    /** Match SQLite's default BINARY collation (UTF-8 bytes), not UTF-16 order. */
    fun atOrBefore(other: OutboxPosition): Boolean {
        if (timestamp != other.timestamp) return timestamp < other.timestamp
        val left = id.toByteArray(Charsets.UTF_8)
        val right = other.id.toByteArray(Charsets.UTF_8)
        for (index in 0 until minOf(left.size, right.size)) {
            val difference = (left[index].toInt() and 0xff) - (right[index].toInt() and 0xff)
            if (difference != 0) return difference < 0
        }
        return left.size <= right.size
    }
}

internal data class OutboxCandidate<T>(
    val position: OutboxPosition,
    /** A blocked chat is tried at most once per complete scan. */
    val destination: String,
    val value: T,
)

internal data class OutboxProgress(val sent: Int, val attempted: Int, val moreToScan: Boolean)

/**
 * Cycle through the entire queue rather than repeatedly starting at its oldest
 * failing row. A page and a handful of network attempts bound each cycle. When
 * a chat has multiple queued rows, rotate past its failed row on the next scan
 * so a permanently bad message cannot hide all later messages in that chat.
 * The number of rows observed at the start caps visits during that scan, so a
 * continuously growing queue cannot suppress retries forever. This is a visit
 * budget, not a database snapshot: concurrent inserts/deletes can change which
 * rows are visited, and anything missed will be considered on the next scan.
 * The cursor need not survive a process restart: the messages themselves do.
 */
internal class FairOutboxScan<T>(private val maxAttemptsPerCycle: Int = 4) {
    private val lock = Mutex()
    private var position: OutboxPosition? = null
    private var remaining: Int? = null
    private var resumeAfter = emptyMap<String, OutboxPosition>()
    private val failed = mutableMapOf<String, OutboxPosition>()
    private val hasFollowing = mutableSetOf<String>()

    suspend fun run(
        pageSize: Int,
        countAtStart: suspend () -> Int,
        load: suspend (OutboxPosition?, Int) -> List<OutboxCandidate<T>>,
        attempt: suspend (T) -> Boolean,
    ): OutboxProgress = lock.withLock {
        require(maxAttemptsPerCycle > 0)
        if (remaining == null) remaining = countAtStart().coerceAtLeast(0)
        val size = minOf(pageSize.coerceIn(1, 128), remaining!!)
        if (size == 0) {
            finishScan()
            return@withLock OutboxProgress(0, 0, false)
        }
        val page = load(position, size)
        if (page.isEmpty()) {
            finishScan()
            return@withLock OutboxProgress(0, 0, false)
        }
        var processed = 0
        var attempted = 0
        var sent = 0
        for (candidate in page) {
            if (candidate.destination in failed) {
                // The next scan can rotate only when there really is a later
                // queued row; a chat with one row retries normally.
                hasFollowing.add(candidate.destination)
            } else if (resumeAfter[candidate.destination]?.let { candidate.position.atOrBefore(it) } == true) {
                // This row already failed in the previous scan. Wrap to it on
                // a later scan if all later rows disappear or also fail.
            } else {
                if (attempted >= maxAttemptsPerCycle) break
                attempted++
                if (attempt(candidate.value)) {
                    sent++
                } else {
                    failed[candidate.destination] = candidate.position
                }
            }
            position = candidate.position
            remaining = remaining!! - 1
            processed++
        }
        val more = remaining!! > 0 && (processed < page.size || page.size == size)
        if (!more) finishScan()
        OutboxProgress(sent, attempted, more)
    }

    private fun finishScan() {
        resumeAfter = failed.filterKeys { it in hasFollowing }
        position = null
        remaining = null
        failed.clear()
        hasFollowing.clear()
    }
}
