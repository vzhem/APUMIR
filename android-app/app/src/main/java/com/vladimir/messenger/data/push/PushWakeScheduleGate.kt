package com.vladimir.messenger.data.push

import java.util.concurrent.CancellationException

/**
 * Process-local coordination between durable inbox writes and a future worker.
 * All intake/resume calls and the worker's final pending check must use the SAME
 * instance and [atomic]. This is not a scheduler or a cross-process lock.
 *
 * A backend must durably enqueue a successor even if the prior worker is still
 * returning, without inheriting its later failure or cancellation. KEEP loses
 * such arrivals; appending behind a still-running parent can also lose a child
 * if that parent subsequently fails. A crash between SQLite and scheduling is
 * still possible: startup and an independently persisted recovery job must
 * rescan the inbox. Accepted scheduling never means a message was received.
 */
internal class PushWakeScheduleGate(
    private val enqueue: (onFailure: () -> Unit) -> Boolean,
    private val recordFailure: () -> Unit,
) {
    enum class Pending { EMPTY, PENDING, UNAVAILABLE }
    enum class Finish { SUCCESS, RETRY }
    class WorkerTicket internal constructor()

    private val lock = Any()
    private var scheduled: Any? = null

    /** The body must include the durable enqueue AND its request() call. */
    fun <T> atomic(block: () -> T): T = synchronized(lock) { block() }

    /** Coalesce requests while a worker is queued, running, or retrying. */
    fun request(): Boolean = synchronized(lock) {
        if (scheduled != null) return@synchronized true
        val token = Any()
        scheduled = token
        try {
            val accepted = enqueue { scheduleFailed(token) }
            if (!accepted) scheduleFailed(token)
            // An enqueue backend may invoke its failure callback synchronously.
            accepted && scheduled != null
        } catch (cancelled: CancellationException) {
            if (scheduled === token) scheduled = null
            throw cancelled
        } catch (_: Exception) {
            if (scheduled === token) scheduled = null
            recordFailure()
            false
        }
    }

    /** Invalidates an older enqueue callback, which may arrive after work began. */
    fun workerStarted(): WorkerTicket = synchronized(lock) {
        WorkerTicket().also { scheduled = it }
    }

    /**
     * Call immediately before returning from the worker. The pending check is
     * inside the same gate as intake: a concurrent new signal is either visible
     * here and retried, or schedules a successor after this method clears the
     * ticket. A failure/unreadable inbox never produces success.
     */
    fun workerFinished(ticket: WorkerTicket, failed: Boolean, pending: () -> Pending): Finish = synchronized(lock) {
        val state = if (failed) Pending.UNAVAILABLE else try {
            pending()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            recordFailure()
            Pending.UNAVAILABLE
        }
        if (state == Pending.EMPTY) {
            if (scheduled === ticket) scheduled = null
            Finish.SUCCESS
        } else {
            Finish.RETRY
        }
    }

    /** Call when cancelled/stopped; a late old worker cannot clear a new ticket. */
    fun workerStopped(ticket: WorkerTicket) = synchronized(lock) {
        if (scheduled === ticket) scheduled = null
    }

    private fun scheduleFailed(token: Any) = synchronized(lock) {
        if (scheduled === token) {
            scheduled = null
            recordFailure() // generic count only; do not persist payload or provider details
        }
    }
}
