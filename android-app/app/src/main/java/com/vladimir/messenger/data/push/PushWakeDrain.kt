package com.vladimir.messenger.data.push

import java.util.concurrent.CancellationException

/**
 * Bounded drain of the experimental signal inbox. It deliberately has no
 * Firebase, WorkManager or network implementation. [inspect] must obtain the
 * current profile and independently pinned peer, check the ACTIVE journal and
 * freshly derive the route; a queue claim/index hit is not authentication.
 * A null route denotes the provider's deleted-messages recovery marker, for
 * which the caller must instead check local opt-in/identity before requesting a
 * bounded generic sync. No peer id or payload is passed to [sync].
 *
 * The scheduler must redrive a pending inbox at [Result.Pending.readyAtMs],
 * including after process death, and close the enqueue/worker-exit race. A
 * A service start, scheduled work, or provider acceptance is not a completed
 * sync. The caller may return [SyncOutcome.COMPLETED_WINDOW] only after a
 * bounded receive window actually ran, its events were consumed and any
 * accepted messages were durably processed. Failure, inability to start, or an
 * unfinished window must return RETRY. Even a completed window does not prove
 * that a message existed, arrived, or produced a notification. No production
 * implementation of that callback is connected yet.
 */
internal class PushWakeDrain(
    private val inbox: PushWakeQueue,
    private val nowMs: () -> Long,
    private val inspect: suspend (String?) -> Inspection,
    private val sync: suspend () -> SyncOutcome,
    private val recordFailure: () -> Unit,
) {
    enum class Inspection { ALLOW, DROP, RETRY }
    enum class SyncOutcome { COMPLETED_WINDOW, RETRY }

    sealed interface Result {
        object Empty : Result
        object Unavailable : Result
        class Pending(val readyAtMs: Long) : Result
    }

    suspend fun run(): Result {
        val accepted = ArrayList<PushWakeInbox.Claim>(MAX_BATCH)
        var storageUnavailable = false
        for (index in 0 until MAX_BATCH) {
            val claim = when (val result = inbox.claim(nowMs())) {
                PushWakeInbox.ClaimResult.Empty -> break
                PushWakeInbox.ClaimResult.Unavailable -> {
                    storageUnavailable = true
                    break
                }
                is PushWakeInbox.ClaimResult.Ready -> result.claim
            }
            when (inspectSafely(claim.route)) {
                Inspection.ALLOW -> accepted.add(claim)
                Inspection.DROP -> if (!completed(inbox.complete(claim, true, nowMs()))) storageUnavailable = true
                Inspection.RETRY -> if (!completed(inbox.complete(claim, false, nowMs()))) storageUnavailable = true
            }
        }
        if (accepted.isNotEmpty()) {
            val completedWindow = syncSafely() == SyncOutcome.COMPLETED_WINDOW
            for (claim in accepted) {
                if (!completed(inbox.complete(claim, completedWindow, nowMs()))) storageUnavailable = true
            }
        }
        if (storageUnavailable) return Result.Unavailable
        return when (val pending = inbox.pending(nowMs())) {
            PushWakeInbox.PendingResult.Empty -> Result.Empty
            PushWakeInbox.PendingResult.Unavailable -> Result.Unavailable
            is PushWakeInbox.PendingResult.Pending -> Result.Pending(pending.readyAtMs)
        }
    }

    private fun completed(result: PushWakeInbox.Completion): Boolean = when (result) {
        PushWakeInbox.Completion.COMPLETED, PushWakeInbox.Completion.NEWER_QUEUED,
        PushWakeInbox.Completion.RETRY_QUEUED, PushWakeInbox.Completion.STALE -> true
        PushWakeInbox.Completion.INVALID, PushWakeInbox.Completion.UNAVAILABLE -> false
    }

    private suspend fun inspectSafely(route: String?): Inspection = try {
        inspect(route)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Never pass route or exception text to diagnostics.
        recordFailure()
        Inspection.RETRY
    }

    private suspend fun syncSafely(): SyncOutcome = try {
        sync()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        recordFailure()
        SyncOutcome.RETRY
    }

    companion object { const val MAX_BATCH = 16 }
}
