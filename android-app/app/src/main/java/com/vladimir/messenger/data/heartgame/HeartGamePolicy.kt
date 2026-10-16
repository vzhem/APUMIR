package com.vladimir.messenger.data.heartgame

import kotlin.random.Random

/** Monotonic, one-use drop ticket. No wall-clock rewards or offline catch-up. */
class HeartGamePolicy {
    private var active: Long? = null
    private var expires = 0L
    private var lastStart: Long? = null
    private var sequence = 0L

    @Synchronized fun start(now: Long): Long? {
        if (lastStart?.let { now - it < MIN_INTERVAL_MS } == true) return null
        lastStart = now
        expires = now + FALL_MS
        return (++sequence).also { active = it }
    }
    @Synchronized fun claim(ticket: Long, now: Long): Boolean {
        if (active != ticket || now >= expires) return false
        active = null
        return true
    }
    @Synchronized fun cancel() { active = null }

    companion object {
        const val MIN_INTERVAL_MS = 45_000L
        const val MAX_INTERVAL_MS = 55_000L
        const val FALL_MS = 14_000L
        const val MAX_SCORE = 1_000_000L
        fun nextInterval(random: Random = Random.Default): Long =
            random.nextLong(MIN_INTERVAL_MS, MAX_INTERVAL_MS + 1)
    }
}
