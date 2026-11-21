package com.vladimir.messenger.service

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure JVM policy for the bounded outgoing-activity piggyback window (~15 s).
 *
 * Kept free of `android.*`, `androidx.*`, and heavy runtime dependencies so
 * both `check-chat-history.sh` (K2JVMCompiler) and Gradle JVM tests exercise
 * the exact production state machine and diagnostics snapshot.
 */
class OutgoingPiggybackPolicy(
    private val windowMs: Long = WINDOW_MS,
    private val minFlushSpacingMs: Long = MIN_FLUSH_SPACING_MS,
) {
    private val windowUntilMs = AtomicLong(0L)
    private val lastFlushMs = AtomicLong(Long.MIN_VALUE)
    private val dirty = AtomicBoolean(true)
    private val windowsOpened = AtomicLong(0L)
    private val flushesScheduled = AtomicLong(0L)

    data class Stats(
        val active: Boolean,
        val remainingMs: Long,
        val windowsOpened: Long,
        val flushesScheduled: Long,
        val lastFlushAgoMs: Long,
        val dirty: Boolean,
    )

    /** Record a genuine user-initiated outgoing send and return true if a flush pass should run. */
    fun noteUserOutgoing(nowMs: Long): Boolean {
        val nextUntil = saturatingAdd(nowMs, windowMs)
        while (true) {
            val prev = windowUntilMs.get()
            if (nextUntil <= prev) break
            if (windowUntilMs.compareAndSet(prev, nextUntil)) {
                if (prev <= nowMs) windowsOpened.incrementAndGet()
                break
            }
        }
        val prevFlush = lastFlushMs.get()
        val spacingElapsed = prevFlush == Long.MIN_VALUE || nowMs - prevFlush >= minFlushSpacingMs
        if (!dirty.get() && !spacingElapsed) return false
        dirty.set(false)
        lastFlushMs.set(nowMs)
        flushesScheduled.incrementAndGet()
        return true
    }

    /** Mark auxiliary state as dirty so the next user outgoing send flushes it immediately. */
    fun markDirty() {
        dirty.set(true)
    }

    /** True while inside the bounded 15-second piggyback window opened by a user send. */
    fun isActive(nowMs: Long): Boolean = windowUntilMs.get() > nowMs

    /** Remaining milliseconds in the current piggyback window, or 0 if idle. */
    fun remainingMs(nowMs: Long): Long = (windowUntilMs.get() - nowMs).coerceAtLeast(0L)

    fun snapshot(nowMs: Long): Stats {
        val lastFlush = lastFlushMs.get()
        val flushAgo = if (lastFlush == Long.MIN_VALUE) -1L else (nowMs - lastFlush).coerceAtLeast(0L)
        return Stats(
            active = isActive(nowMs),
            remainingMs = remainingMs(nowMs),
            windowsOpened = windowsOpened.get(),
            flushesScheduled = flushesScheduled.get(),
            lastFlushAgoMs = flushAgo,
            dirty = dirty.get(),
        )
    }

    fun resetForTest() {
        windowUntilMs.set(0L)
        lastFlushMs.set(Long.MIN_VALUE)
        dirty.set(true)
        windowsOpened.set(0L)
        flushesScheduled.set(0L)
    }

    private fun saturatingAdd(a: Long, b: Long): Long {
        val sum = a + b
        return if ((a xor sum) < 0L && (a xor b) >= 0L) Long.MAX_VALUE else sum
    }

    companion object {
        const val WINDOW_MS: Long = 15_000L
        const val MIN_FLUSH_SPACING_MS: Long = 10_000L
        const val FLUSH_BUDGET_MS: Long = 12_000L
    }
}
