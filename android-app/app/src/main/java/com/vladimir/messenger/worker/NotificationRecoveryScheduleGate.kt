package com.vladimir.messenger.worker

import java.util.UUID
import java.util.concurrent.CancellationException

/**
 * Process-local coalescing for independent one-off requests. An arrival while
 * the tracked worker is queued/running marks it dirty; before that worker
 * returns, an independent successor is enqueued. A successor never inherits
 * the older worker's later failure or cancellation. A late callback/worker
 * cannot clear another ticket. Process death and enqueue failure still require
 * an independent durable periodic recovery job.
 */
internal class NotificationRecoveryScheduleGate(
    private val enqueue: (UUID, onFailure: () -> Unit) -> Boolean,
    private val recordFailure: () -> Unit,
    private val newId: () -> UUID = UUID::randomUUID,
) {
    private val lock = Any()
    private var scheduled: UUID? = null
    private var started = false
    private var dirty = false

    fun request(): Boolean = synchronized(lock) {
        if (scheduled != null) {
            dirty = true
            true
        } else startLocked()
    }

    fun workerStarted(id: UUID) = synchronized(lock) {
        if (scheduled == id) started = true
    }

    fun workerFinished(id: UUID, retrying: Boolean) = synchronized(lock) {
        if (scheduled != id) return@synchronized
        if (dirty) {
            scheduled = null
            dirty = false
            startLocked() // independent even when the older work will retry/fail
        } else if (!retrying) {
            scheduled = null
            started = false
        }
    }

    fun workerStopped(id: UUID) = synchronized(lock) {
        if (scheduled != id) return@synchronized
        val successor = dirty
        scheduled = null
        started = false
        dirty = false
        if (successor) startLocked()
    }

    private fun startLocked(): Boolean {
        val id = newId()
        scheduled = id
        started = false
        return try {
            val accepted = enqueue(id) { enqueueFailed(id) }
            if (!accepted) enqueueFailed(id)
            accepted && scheduled == id
        } catch (cancelled: CancellationException) {
            if (scheduled == id) { scheduled = null; dirty = false; started = false }
            throw cancelled
        } catch (_: Exception) {
            enqueueFailed(id)
            false
        }
    }

    private fun enqueueFailed(id: UUID) = synchronized(lock) {
        // A stale callback or a request that has actually started cannot evict
        // a newer ticket. The periodic job repairs failed scheduling.
        if (scheduled == id && !started) {
            scheduled = null
            dirty = false
            try { recordFailure() } catch (_: Exception) { /* diagnostics cannot reserve a dead ticket */ }
        }
    }
}
