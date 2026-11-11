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
 * successful [sync] only means that an attempt was started; it does not prove
 * that any message arrived or that a notification was shown.
 */
internal class PushWakeDrain(
    private val inbox: PushWakeInbox,
    private val nowMs: () -> Long,
    private val inspect: suspend (String?) -> Inspection,
    private val sync: suspend () -> Boolean,
    private val recordFailure: () -> Unit,
) {
    enum class Inspection { ALLOW, DROP, RETRY }

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
            val started = syncSafely()
            for (claim in accepted) {
                if (!completed(inbox.complete(claim, started, nowMs()))) storageUnavailable = true
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

    private suspend fun syncSafely(): Boolean = try {
        sync()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        recordFailure()
        false
    }

    companion object { const val MAX_BATCH = 16 }
}
