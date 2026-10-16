package com.vladimir.messenger.data.group

/** Countdown from the last local message, never a deadline renewed by a UI tick. */
object GroupSlowMode {
    fun remainingMs(seconds: Int, lastSentAtMs: Long?, nowMs: Long): Long {
        if (seconds <= 0 || lastSentAtMs == null || lastSentAtMs <= 0L) return 0L
        // A future timestamp from restored/synchronised history must not lock
        // the composer until that date. Such rows are also excluded by the DAO.
        if (lastSentAtMs > nowMs) return 0L
        val elapsed = nowMs - lastSentAtMs
        return (seconds.toLong() * 1000L - elapsed).coerceAtLeast(0L)
    }
}
