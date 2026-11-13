package com.vladimir.messenger.data.push

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * One count-bounded worker pass over the durable wake inbox. Both the one-off worker
 * and the independently scheduled periodic recovery worker must invoke this
 * same loop with the same process-local dispatcher. This code does not start
 * WorkManager, Firebase, network traffic or notifications.
 *
 * The supplied drain may report success only after its actual authenticated
 * receive window and durable message processing have completed. A scheduler
 * must map RETRY to durable retry and install periodic recovery before it
 * accepts provider callbacks; a queued task is not proof of delivery. A periodic
 * recovery worker must use [PushWakePeriodicRecovery] to ensure a generic marker
 * and run the drain directly, even if the in-memory gate believes another worker
 * is scheduled. At most one run-loop drain is active in this classloader,
 * normally the app process, including across different dispatchers. A
 * concurrent caller immediately returns RETRY without replacing the active
 * ticket; the scheduler must preserve that retry. This does not bound work in
 * other processes/classloaders, callbacks outside this loop, or I/O that
 * outlives its coroutine. A drain must not detach side effects; it must itself
 * be bounded and cancellable. Effects must remain idempotent if a lease expires.
 * There is no fairness or execution-time guarantee from this guard. The
 * production authenticated full-scan callback is still missing; the separate
 * recovery scan and checkpoint only coordinate hints and do not perform it.
 */
internal class PushWakeRunLoop(
    private val dispatcher: PushWakeDispatcher,
    private val drain: suspend () -> PushWakeDrain.Result,
    private val nowMs: () -> Long,
    private val recordFailure: () -> Unit,
) {
    enum class Result { SUCCESS, RETRY }

    suspend fun run(): Result {
        currentCoroutineContext().ensureActive()
        if (!ACTIVE.compareAndSet(false, true)) {
            currentCoroutineContext().ensureActive()
            return Result.RETRY
        }
        try {
            currentCoroutineContext().ensureActive()
            val ticket = dispatcher.workerStarted()
            return try {
                currentCoroutineContext().ensureActive()
                val result = drain()
                currentCoroutineContext().ensureActive()
                val failed = result is PushWakeDrain.Result.Unavailable
                val finished = dispatcher.workerFinished(ticket, failed, nowMs())
                currentCoroutineContext().ensureActive()
                when (finished) {
                    PushWakeScheduleGate.Finish.SUCCESS -> Result.SUCCESS
                    PushWakeScheduleGate.Finish.RETRY -> Result.RETRY
                }
            } catch (cancelled: CancellationException) {
                // A claimed route remains leased for recovery; allow a fresh
                // independent request instead of holding a dead ticket.
                dispatcher.workerStopped(ticket)
                throw cancelled
            } catch (_: Exception) {
                // Keep the ticket for the scheduler's retry; periodic recovery
                // remains independent if scheduling that retry fails. Never
                // report routes or exception text, or mask cancellation.
                try {
                    currentCoroutineContext().ensureActive()
                    try {
                        recordFailure()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        Logger.getLogger("PushWakeRunLoop").warning("Wake diagnostics unavailable")
                    }
                    currentCoroutineContext().ensureActive()
                    Result.RETRY
                } catch (cancelled: CancellationException) {
                    dispatcher.workerStopped(ticket)
                    throw cancelled
                }
            }
        } finally {
            ACTIVE.set(false)
        }
    }

    private companion object {
        // One owner for loops/dispatchers loaded by this classloader. This is
        // not a cross-process lock and is not held by arbitrary side effects.
        val ACTIVE = AtomicBoolean(false)
    }
}
