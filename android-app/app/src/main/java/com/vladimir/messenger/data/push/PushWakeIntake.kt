package com.vladimir.messenger.data.push

import java.util.concurrent.CancellationException

/**
 * Ingest a provider-shaped wake into a durable inbox before requesting work.
 * This is deliberately independent of Firebase and WorkManager: neither is
 * started here. The caller must check an explicit feature gate and supply only
 * its own local registration index. An index hit is a hint, NOT authentication;
 * the worker must still revalidate the live pin, identity, active journal and
 * freshly derived route before doing anything beyond discarding the claim.
 *
 * [schedule] requests a generic bounded worker, never a peer-specific action.
 * It must arrange redrive if a wake arrives while a worker is already running,
 * respect lease/backoff deadlines, and recover persisted inboxes at startup.
 * Enqueueing and scheduling are separate transactions: a crash between them is
 * possible, so startup recovery is mandatory. A true return only means the
 * scheduler accepted a request; it does not mean a worker ran or a message was
 * received. The production FCM listener and scheduler do not exist yet.
 */
internal class PushWakeIntake(
    private val lookup: (String) -> Hint,
    private val enqueueRoute: (String, Long) -> PushWakeInbox.EnqueueResult,
    private val enqueueRecovery: (Long) -> PushWakeInbox.EnqueueResult,
    private val pending: (Long) -> PushWakeInbox.PendingResult,
    private val schedule: () -> Boolean,
    private val recordFailure: () -> Unit,
) {
    enum class Hint { FOUND, MISSING, UNAVAILABLE }
    enum class Result { IGNORED, QUEUED, QUEUED_WITHOUT_SCHEDULER, UNAVAILABLE }

    /** Malformed or unknown routes never enter the inbox or trigger work. */
    fun onData(data: Map<String, String>, nowMs: Long): Result {
        val payload = PushWakePayload.parse(data) ?: return Result.IGNORED
        return try {
            when (lookup(payload.route)) {
                Hint.MISSING -> Result.IGNORED
                Hint.UNAVAILABLE -> Result.UNAVAILABLE
                Hint.FOUND -> queued(enqueueRoute(payload.route, nowMs))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The callback must report only a generic count, not payload or exception text.
            recordFailure()
            Result.UNAVAILABLE
        }
    }

    /** For the provider's onDeletedMessages signal, which has no route. */
    fun onDeletedMessages(nowMs: Long): Result = try {
        queued(enqueueRecovery(nowMs))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        recordFailure()
        Result.UNAVAILABLE
    }

    /** Retry scheduler registration after a process restart or enqueue failure. */
    fun resume(nowMs: Long): Result = try {
        when (pending(nowMs)) {
            PushWakeInbox.PendingResult.Empty -> Result.IGNORED
            PushWakeInbox.PendingResult.Unavailable -> Result.UNAVAILABLE
            is PushWakeInbox.PendingResult.Pending -> schedulePersisted()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        recordFailure()
        Result.UNAVAILABLE
    }

    private fun queued(result: PushWakeInbox.EnqueueResult): Result = when (result) {
        PushWakeInbox.EnqueueResult.QUEUED, PushWakeInbox.EnqueueResult.RECOVERY_QUEUED -> schedulePersisted()
        PushWakeInbox.EnqueueResult.INVALID -> Result.IGNORED
        PushWakeInbox.EnqueueResult.UNAVAILABLE -> Result.UNAVAILABLE
    }

    private fun schedulePersisted(): Result = try {
        if (schedule()) Result.QUEUED else Result.QUEUED_WITHOUT_SCHEDULER
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        recordFailure()
        Result.QUEUED_WITHOUT_SCHEDULER
    }
}
