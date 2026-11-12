package com.vladimir.messenger.data.mirror

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** A scan page, not an acknowledgement of any row in it. */
data class MirrorPendingBatch(val rows: List<MirrorRow>, val moreToScan: Boolean)

/** A live pairing route can carry pending frames even before WSS opens. */
internal fun mirrorPendingAvailable(
    canClaim: Boolean,
    closed: Boolean,
    wsOpen: Boolean,
    pairingAvailable: () -> Boolean,
): Boolean = canClaim && !closed && (wsOpen || pairingAvailable())

/** Do not wait out an idle sweep when a fresh partner becomes active. */
internal fun mirrorPendingPartnerWake(wasFresh: Boolean, wasActive: Boolean, isActive: Boolean): Boolean =
    !wasFresh || (!wasActive && isActive)

/**
 * Periodically offer one bounded page while a shadow can reach its active
 * partner. The Room scanner owns the rotating cursor; an accepted offer is not
 * an ACK, and a lost frame remains eligible when the next scan wraps. This gate
 * is process-local, not a network, tombstone or identity-change barrier.
 */
internal class MirrorPendingFlusher(
    private val scope: CoroutineScope,
    private val available: () -> Boolean,
    private val load: suspend (Int) -> MirrorPendingBatch,
    private val offer: (MirrorRow) -> Boolean,
    private val recordFailure: (Exception) -> Unit,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val gate = Any()
    private var inFlight = false
    private var lastStartedAt: Long? = null
    private var nextDelayMs = PAGE_DELAY_MS
    private var sweepDelayMs = PAGE_DELAY_MS
    private var workGeneration = 0L

    /** A new user send or reconnected partner should not wait for idle backoff. */
    fun noteNewWork() = synchronized(gate) {
        workGeneration++
        sweepDelayMs = PAGE_DELAY_MS
        nextDelayMs = minOf(nextDelayMs, PAGE_DELAY_MS)
    }

    fun wake() {
        noteNewWork()
        request()
    }

    fun request() {
        if (!available()) return
        val generation = synchronized(gate) {
            // Sample while holding the gate: an older concurrent trigger must
            // not be mistaken for a clock rollback after a newer page finishes.
            val now = nowMs()
            val last = lastStartedAt
            if (inFlight || (last != null && now >= last && now - last < nextDelayMs)) return
            inFlight = true
            lastStartedAt = now
            workGeneration
        }
        val job = scope.launch {
            var completed = false
            var moreToScan = false
            try {
                if (!available()) return@launch
                val batch = load(PAGE_SIZE)
                moreToScan = batch.moreToScan
                completed = true
                for (row in batch.rows) {
                    currentCoroutineContext().ensureActive()
                    if (!available()) { completed = false; break }
                    // False is not a delivery result. The cursor eventually
                    // wraps to this still-pending row on a later scan.
                    offer(row)
                }
            } catch (cancelled: CancellationException) {
                completed = false
                throw cancelled
            } catch (error: Exception) {
                completed = false
                recordFailure(error)
            } finally {
                synchronized(gate) {
                    if (workGeneration != generation || !completed || moreToScan) {
                        nextDelayMs = PAGE_DELAY_MS
                    } else {
                        // Repeatedly echoing a long-lived offline queue every
                        // 20 seconds wastes battery and traffic. Completion is
                        // only a scan boundary, not evidence of delivery.
                        nextDelayMs = sweepDelayMs
                        sweepDelayMs = (sweepDelayMs * 2).coerceAtMost(MAX_SWEEP_DELAY_MS)
                    }
                }
            }
        }
        // Also releases the gate when the parent scope was already cancelled
        // before the coroutine body had a chance to start.
        job.invokeOnCompletion { synchronized(gate) { inFlight = false } }
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val PAGE_DELAY_MS = 20_000L
        const val MAX_SWEEP_DELAY_MS = 10 * 60_000L
    }
}
