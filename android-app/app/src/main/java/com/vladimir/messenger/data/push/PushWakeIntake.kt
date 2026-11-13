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
 * received. If the index or route write is temporarily unavailable, a bounded
 * generic recovery marker is persisted instead of forgetting the callback. It
 * contains no untrusted route and is still not authorization: the eventual
 * recovery worker must revalidate opt-in, identity and each pin/registration.
 * If the recovery write also fails, only an independent periodic full scan can
 * repair a callback that the provider will not redeliver. The disconnected
 * [PushWakePeriodicRecovery] seam can ensure a marker on a later tick, but the
 * production FCM listener, worker and full mailbox scan do not exist yet.
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

    /** Malformed payloads and routes confirmed absent never enter the inbox or trigger work. */
    fun onData(data: Map<String, String>, nowMs: Long): Result {
        val payload = PushWakePayload.parse(data) ?: return Result.IGNORED
        // A provider may never redeliver a callback after a local read/write
        // failure. The fallback records no route; it requests generic recovery.
        val hint = try {
            lookup(payload.route)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            recordFailure()
            Hint.UNAVAILABLE
        }
        return when (hint) {
            Hint.MISSING -> Result.IGNORED
            Hint.UNAVAILABLE -> onDeletedMessages(nowMs)
            Hint.FOUND -> {
                val saved = try {
                    enqueueRoute(payload.route, nowMs)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    recordFailure()
                    PushWakeInbox.EnqueueResult.UNAVAILABLE
                }
                if (saved == PushWakeInbox.EnqueueResult.UNAVAILABLE) onDeletedMessages(nowMs) else queued(saved)
            }
        }
    }

    /** For a provider's deleted-messages signal or a transient local admission failure. */
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
