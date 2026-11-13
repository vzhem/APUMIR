package com.vladimir.messenger.data.push

import java.util.concurrent.CancellationException
import java.util.logging.Logger

/**
 * Maps an already validated provider signal onto the durable wake inbox.
 *
 * This is the piece that was missing between [PushRuntimeCoordinator] (which
 * decides *whether* a signal may schedule work) and [PushWakeInbox] (which
 * stores a bounded, leased hint). It deliberately knows nothing about Firebase,
 * WorkManager, peers or message content:
 *
 *  - a supported `{v:"1"}` signal carries no route at all, so it is stored as a
 *    *generic recovery marker*; the eventual worker must enumerate the encrypted
 *    registration index and re-derive every route against the live pin before
 *    acting, because a marker is a request to look, not evidence of a message;
 *  - provider message deletion is the same marker: the provider may have dropped
 *    signals while the device was unreachable.
 *
 * A `Queued`/`AlreadyQueued` result only means the local journal now has a
 * durable request for a bounded check. It is not a received message, not a
 * delivery receipt and not authorization to touch any chat.
 */
internal class PushSignalInbox(
    /** Durable marker write; production wiring is [of]. */
    private val enqueueRecovery: (Long) -> PushWakeInbox.EnqueueResult,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val recordFailure: () -> Unit = {},
) {
    sealed interface Result {
        /** Durable marker written. */
        object Queued : Result
        /** Coalesced with an existing marker or lease. */
        object AlreadyQueued : Result
        /** Storage refused or is corrupted; the signal must not be forgotten. */
        object Unavailable : Result
        /** Local clock or payload is unusable; nothing is written. */
        object Rejected : Result
        override fun toString(): String = "PushSignalInboxResult"
    }

    fun onSignal(data: Map<String, String>?, hasNotificationBlock: Boolean): Result =
        when (PushSignalPolicy.inspect(data, hasNotificationBlock)) {
            PushSignalPolicy.Decision.LookInMailbox,
            PushSignalPolicy.Decision.CheckMailboxAfterGap -> enqueue(nowMs())
            PushSignalPolicy.Decision.Ignore -> Result.Rejected
            PushSignalPolicy.Decision.Reject -> Result.Rejected
        }

    fun onDeletedMessages(): Result = enqueue(nowMs())

    private fun enqueue(now: Long): Result {
        if (now !in 1..(Long.MAX_VALUE - TTL_GUARD_MS)) return Result.Rejected
        return try {
            when (enqueueRecovery(now)) {
                PushWakeInbox.EnqueueResult.RECOVERY_QUEUED -> Result.Queued
                // A route marker cannot appear for a route-less signal, but treat
                // it as queued rather than as an error: a durable request exists.
                PushWakeInbox.EnqueueResult.QUEUED -> Result.AlreadyQueued
                PushWakeInbox.EnqueueResult.INVALID -> Result.Rejected
                PushWakeInbox.EnqueueResult.UNAVAILABLE -> Result.Unavailable
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Never log the route, nonce or exception text; count it and let the
            // caller retry the same signal.
            diag()
            Result.Unavailable
        }
    }

    private fun diag() {
        try {
            recordFailure()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Logger.getLogger("PushSignalInbox").warning("Signal diagnostics unavailable")
        }
    }

    companion object {
        /** Leaves headroom before the inbox's own horizon arithmetic. */
        private const val TTL_GUARD_MS = 2L * 24 * 60 * 60 * 1000

        /** Production wiring against the encrypted, durable wake inbox. */
        fun of(
            inbox: PushWakeInbox,
            nowMs: () -> Long = System::currentTimeMillis,
            recordFailure: () -> Unit = {},
        ): PushSignalInbox = PushSignalInbox(inbox::enqueueRecovery, nowMs, recordFailure)
    }
}
