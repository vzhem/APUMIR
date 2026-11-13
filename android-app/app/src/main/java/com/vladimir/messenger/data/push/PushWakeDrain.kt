package com.vladimir.messenger.data.push

import java.util.concurrent.CancellationException
import java.util.logging.Logger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Count-bounded drain of the experimental signal inbox. It deliberately has no
 * Firebase, WorkManager or network implementation. [inspect] must obtain the
 * current profile and independently pinned peer, check the ACTIVE journal and
 * freshly derive the route; a queue claim/index hit is not authentication.
 * A null route denotes a generic recovery marker (for example provider deletion,
 * inbox overflow or an unavailable admission index). The caller must check
 * local opt-in/identity before requesting a bounded generic sync, and revalidate
 * each registration and peer before acting. [sync] receives only bounded opaque
 * route hints and, for the null marker, its local incarnation and revision; no
 * peer id, token, capability or message payload is passed. The recovery scan
 * must use that exact item's signal, not a new identifier or a lease nonce.
 * It must revalidate live trust under
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
 *
 * Cancellation is checked at each synchronous storage/callback boundary. A
 * cancelled pass leaves uncompleted claims leased for later recovery. Checks
 * are cooperative, not a transaction with cancellation: an operation that
 * already began can still finish, and a committed completion is not undone.
 * Callbacks must not detach effects beyond their lifetime, and an actual
 * receive window must itself be bounded, cancellable and idempotent.
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

    /** A local marker identity, not a peer, provider value or authorization. */
    class RecoverySignal internal constructor(internal val incarnation: String, internal val revision: Long) {
        internal fun valid(): Boolean = revision > 0 && NONCE.matches(incarnation) && incarnation.any { it != '0' }
        override fun toString(): String = "PushWakeRecoverySignal(redacted)"
    }

    /** Bounded hints only; a null route is the generic recovery marker, not a peer. */
    class SyncBatch internal constructor(claims: List<PushWakeInbox.Claim>) {
        val items: List<SyncItem> = java.util.Collections.unmodifiableList(claims.map { claim ->
            SyncItem(claim.route, claim.recoveryNonce?.let { RecoverySignal(it, claim.revision) })
        })
        override fun toString(): String = "PushWakeSyncBatch(redacted)"
    }

    /** Each item represents one lease; the same route can occur more than once. */
    class SyncItem internal constructor(val route: String?, val recoverySignal: RecoverySignal? = null) {
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
        val context = currentCoroutineContext()
        context.ensureActive()
        val accepted = ArrayList<PushWakeInbox.Claim>(MAX_BATCH)
        var storageUnavailable = false
        for (index in 0 until MAX_BATCH) {
            context.ensureActive()
            val time = nowMs()
            context.ensureActive()
            val claimed = inbox.claim(time)
            context.ensureActive()
            val claim = when (claimed) {
                PushWakeInbox.ClaimResult.Empty -> break
                PushWakeInbox.ClaimResult.Unavailable -> {
                    storageUnavailable = true
                    break
                }
                is PushWakeInbox.ClaimResult.Ready -> claimed.claim
            }
            val signal = claim.recoveryNonce?.let { RecoverySignal(it, claim.revision) }
            if ((claim.route == null && (signal == null || !signal.valid())) || (claim.route != null && signal != null)) {
                // A malformed marker must not be presented as a completed pass.
                // Leave its lease untouched for diagnosis/recovery.
                storageUnavailable = true
                continue
            }
            when (inspectSafely(claim.route)) {
                Inspection.ALLOW -> accepted.add(claim)
                Inspection.DROP -> if (!completeClaim(claim, true)) storageUnavailable = true
                Inspection.RETRY -> if (!completeClaim(claim, false)) storageUnavailable = true
            }
        }
        context.ensureActive()
        if (accepted.isNotEmpty()) {
            val batch = SyncBatch(accepted)
            val outcomes = syncSafely(batch)
            context.ensureActive()
            for ((index, claim) in accepted.withIndex()) {
                context.ensureActive()
                val completedWindow = outcomes?.get(index) == SyncOutcome.COMPLETED_WINDOW
                if (!completeClaim(claim, completedWindow)) storageUnavailable = true
            }
        }
        context.ensureActive()
        if (storageUnavailable) return Result.Unavailable
        val time = nowMs()
        context.ensureActive()
        val pending = inbox.pending(time)
        context.ensureActive()
        return when (pending) {
            PushWakeInbox.PendingResult.Empty -> Result.Empty
            PushWakeInbox.PendingResult.Unavailable -> Result.Unavailable
            is PushWakeInbox.PendingResult.Pending -> Result.Pending(pending.readyAtMs)
        }
    }

    private suspend fun completeClaim(claim: PushWakeInbox.Claim, success: Boolean): Boolean {
        val context = currentCoroutineContext()
        context.ensureActive()
        val time = nowMs()
        context.ensureActive()
        val result = inbox.complete(claim, success, time)
        // A committed completion cannot be rolled back, but do not start the
        // next claim/completion or return success after observing cancellation.
        context.ensureActive()
        return completed(result)
    }

    private fun completed(result: PushWakeInbox.Completion): Boolean = when (result) {
        PushWakeInbox.Completion.COMPLETED, PushWakeInbox.Completion.NEWER_QUEUED,
        PushWakeInbox.Completion.RETRY_QUEUED, PushWakeInbox.Completion.STALE -> true
        PushWakeInbox.Completion.INVALID, PushWakeInbox.Completion.UNAVAILABLE -> false
    }

    private suspend fun inspectSafely(route: String?): Inspection = try {
        val result = inspect(route)
        currentCoroutineContext().ensureActive()
        result
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Never pass route or exception text to diagnostics.
        diag()
        Inspection.RETRY
    }

    private suspend fun syncSafely(batch: SyncBatch): List<SyncOutcome>? {
        val outcomes = try {
            // Match at most MAX_BATCH results to the exact items issued for
            // this batch. Do not log routes, callback data, or exception text.
            val supplied = sync(batch)
            currentCoroutineContext().ensureActive()
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
            diag()
            return null
        }
        // Reading an injected result list may itself race cancellation.
        currentCoroutineContext().ensureActive()
        if (outcomes == null) diag()
        currentCoroutineContext().ensureActive()
        return outcomes
    }

    private suspend fun diag() {
        currentCoroutineContext().ensureActive()
        try {
            recordFailure()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Never turn cancellation into a normal retry or expose details.
            currentCoroutineContext().ensureActive()
            Logger.getLogger("PushWakeDrain").warning("Wake diagnostics unavailable")
        }
        currentCoroutineContext().ensureActive()
    }

    companion object {
        const val MAX_BATCH = 16
        private val NONCE = Regex("^[0-9a-f]{64}$")
    }
}
