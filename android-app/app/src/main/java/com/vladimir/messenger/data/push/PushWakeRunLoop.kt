package com.vladimir.messenger.data.push

import java.util.concurrent.CancellationException

/**
 * One bounded worker pass over the durable wake inbox. Both the one-off worker
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
 * is scheduled. The actual bounded authenticated full scan is still missing.
 */
internal class PushWakeRunLoop(
    private val dispatcher: PushWakeDispatcher,
    private val drain: suspend () -> PushWakeDrain.Result,
    private val nowMs: () -> Long,
    private val recordFailure: () -> Unit,
) {
    enum class Result { SUCCESS, RETRY }

    suspend fun run(): Result {
        val ticket = dispatcher.workerStarted()
        return try {
            val result = drain()
            val failed = result is PushWakeDrain.Result.Unavailable
            when (dispatcher.workerFinished(ticket, failed, nowMs())) {
                PushWakeScheduleGate.Finish.SUCCESS -> Result.SUCCESS
                PushWakeScheduleGate.Finish.RETRY -> Result.RETRY
            }
        } catch (cancelled: CancellationException) {
            // A claimed route remains leased for recovery; allow a fresh
            // independent request instead of holding a dead in-memory ticket.
            dispatcher.workerStopped(ticket)
            throw cancelled
        } catch (_: Exception) {
            // Keep the ticket for the scheduler's retry; periodic recovery is
            // independent if scheduling that retry subsequently fails. Report
            // only a generic count, never routes or exception text.
            recordFailure()
            Result.RETRY
        }
    }
}
