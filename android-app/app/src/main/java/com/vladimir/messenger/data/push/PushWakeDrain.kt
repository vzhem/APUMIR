package com.vladimir.messenger.data.push

import java.util.concurrent.CancellationException

/**
 * Bounded drain of the experimental signal inbox. It deliberately has no
 * Firebase, WorkManager or network implementation. [inspect] must obtain the
 * current profile and independently pinned peer, check the ACTIVE journal and
 * freshly derive the route; a queue claim/index hit is not authentication.
 * A null route denotes a generic recovery marker (for example provider deletion,
 * inbox overflow or an unavailable admission index). The caller must check
 * local opt-in/identity before requesting a bounded generic sync, and revalidate
 * each registration and peer before acting. [sync] receives only the bounded opaque route hints it
 * must process, including a null generic-recovery marker; no peer id, token,
 * capability or message payload is passed. It must revalidate live trust under
 * an ABA-safe lifecycle boundary before effects, not rely on [inspect] alone.
 *
 * The scheduler must redrive a pending inbox at [Result.Pending.readyAtMs],
 * including after process death, and close the enqueue/worker-exit race. A
 * service start, scheduled work, or provider acceptance is not a completed
 * sync. [sync] must return one [SyncResult] made by each [SyncItem] in the
 * batch; a missing, duplicate or foreign result retries the whole batch. Results
 * can arrive in any order and are matched by item identity, not by position or
 * route string. [SyncOutcome.COMPLETED_WINDOW] is valid only after the item's
 * bounded receive window ran, its events were consumed and accepted messages
 * were durably processed. Recovery also needs durable continuation for any
 * unfinished scan. A lease can expire while the callback is running; eventual
 * processing must therefore be idempotent and revalidate live trust. Failure,
 * inability to start, or an unfinished window must return RETRY. Even a
 * completed window does not prove delivery or a displayed notification. No
 * production implementation of that callback is connected yet.
 */
internal class PushWakeDrain(
    private val inbox: PushWakeQueue,
    private val nowMs: () -> Long,
    private val inspect: suspend (String?) -> Inspection,
    private val sync: suspend (SyncBatch) -> List<SyncResult>,
    private val recordFailure: () -> Unit,
) {
    enum class Inspection { ALLOW, DROP, RETRY }
    enum class SyncOutcome { COMPLETED_WINDOW, RETRY }

    /** Bounded hints only; a null route is the generic recovery marker, not a peer. */
    class SyncBatch internal constructor(routes: List<String?>) {
        val items: List<SyncItem> = java.util.Collections.unmodifiableList(routes.map { SyncItem(it) })
        override fun toString(): String = "PushWakeSyncBatch(redacted)"
    }

    /** Each item represents one lease; the same route can occur more than once. */
    class SyncItem internal constructor(val route: String?) {
        fun result(outcome: SyncOutcome): SyncResult = SyncResult(this, outcome)
        override fun toString(): String = "PushWakeSyncItem(redacted)"
    }

    class SyncResult internal constructor(internal val item: SyncItem, internal val outcome: SyncOutcome) {
        override fun toString(): String = "PushWakeSyncResult(redacted)"
    }

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
            val batch = SyncBatch(accepted.map { it.route })
            val outcomes = syncSafely(batch)
            for ((index, claim) in accepted.withIndex()) {
                val completedWindow = outcomes?.get(index) == SyncOutcome.COMPLETED_WINDOW
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

    private suspend fun syncSafely(batch: SyncBatch): List<SyncOutcome>? {
        val outcomes = try {
            // Match at most MAX_BATCH results to the exact items issued for
            // this batch. Do not log routes, callback data, or exception text.
            val supplied = sync(batch)
            if (supplied.size != batch.items.size) {
                null
            } else {
                val matched = arrayOfNulls<SyncOutcome>(batch.items.size)
                val seen = BooleanArray(batch.items.size)
                var invalid = false
                for (index in batch.items.indices) {
                    val result = supplied[index]
                    val target = batch.items.indexOfFirst { it === result.item }
                    if (target < 0 || seen[target]) {
                        invalid = true
                        break
                    }
                    seen[target] = true
                    matched[target] = result.outcome
                }
                if (invalid || matched.any { it == null }) null else matched.map { checkNotNull(it) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            recordFailure()
            return null
        }
        if (outcomes == null) recordFailure()
        return outcomes
    }

    companion object {
        const val MAX_BATCH = 16
    }
}
