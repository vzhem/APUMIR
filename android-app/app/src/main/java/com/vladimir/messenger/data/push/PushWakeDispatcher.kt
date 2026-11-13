package com.vladimir.messenger.data.push

/**
 * Connects the disabled intake to its process-local scheduling gate. The same
 * instance must be used by every provider callback, startup recovery, and the
 * worker's final inbox check. No Firebase or WorkManager is started here.
 * The caller must check an explicit feature/identity gate before invoking it.
 */
internal class PushWakeDispatcher(
    private val gate: PushWakeScheduleGate,
    lookup: (String) -> PushWakeIntake.Hint,
    enqueueRoute: (String, Long) -> PushWakeInbox.EnqueueResult,
    enqueueRecovery: (Long) -> PushWakeInbox.EnqueueResult,
    private val pending: (Long) -> PushWakeInbox.PendingResult,
    recordFailure: () -> Unit,
) {
    private val intake = PushWakeIntake(
        lookup, enqueueRoute, enqueueRecovery, pending, gate::request, recordFailure,
    )

    fun onData(data: Map<String, String>, nowMs: Long): PushWakeIntake.Result =
        gate.atomic { intake.onData(data, nowMs) }

    fun onDeletedMessages(nowMs: Long): PushWakeIntake.Result =
        gate.atomic { intake.onDeletedMessages(nowMs) }

    fun resume(nowMs: Long): PushWakeIntake.Result = gate.atomic { intake.resume(nowMs) }

    fun workerStarted(): PushWakeScheduleGate.WorkerTicket = gate.workerStarted()

    /** Must run after the actual drain and immediately before returning its WorkManager result. */
    fun workerFinished(ticket: PushWakeScheduleGate.WorkerTicket, drainFailed: Boolean, nowMs: Long): PushWakeScheduleGate.Finish =
        gate.workerFinished(ticket, drainFailed) {
            when (pending(nowMs)) {
                PushWakeInbox.PendingResult.Empty -> PushWakeScheduleGate.Pending.EMPTY
                PushWakeInbox.PendingResult.Unavailable -> PushWakeScheduleGate.Pending.UNAVAILABLE
                is PushWakeInbox.PendingResult.Pending -> PushWakeScheduleGate.Pending.PENDING
            }
        }

    /** Cancellation does not consume a claim; startup/periodic recovery must redrive it. */
    fun workerStopped(ticket: PushWakeScheduleGate.WorkerTicket) = gate.workerStopped(ticket)
}
