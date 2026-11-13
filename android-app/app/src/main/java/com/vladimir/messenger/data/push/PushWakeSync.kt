package com.vladimir.messenger.data.push

import java.util.concurrent.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.logging.Logger

/**
 * The `sync` callback [PushWakeDrain] requires but nothing implemented yet.
 *
 * For each leased hint it opens one bounded receive window through
 * [PushReceiveWindow] and reports [PushWakeDrain.SyncOutcome.COMPLETED_WINDOW]
 * **only** when that window actually completed. Every other case — missing or
 * stale trust, a disabled gate, an unavailable transport, an unfinished scan —
 * returns `RETRY`, so the lease stays leased and recovery redrives it.
 *
 * There is deliberately no result based on the number of items, on the server's
 * `202`/`204`, or on a notification being posted. A completed window does not
 * prove a message exists; a stored message is not proof a human saw anything.
 *
 * Per-item semantics:
 *  - a normal route hint is inspected against the live pin/identity/journal
 *    ([inspect]); anything but ALLOW retries or drops the lease as decided there;
 *  - the generic recovery marker asks for a full pass over the registration
 *    index, which this class cannot do yet (it would need the index enumerator
 *    and a per-route re-derivation). It therefore reports `RETRY` and never
 *    fabricates a completed window.
 */
internal class PushWakeSync(
    private val gate: () -> Boolean,
    private val inspect: suspend (String?) -> PushWakeDrain.Inspection,
    private val receive: suspend (String?) -> PushReceiveWindow.Outcome,
    private val fullScan: (suspend () -> Boolean)? = null,
    private val recordFailure: () -> Unit = {},
) {
    /**
     * Processes one batch and returns exactly one result per item, in the same
     * order. A missing/extra result is a protocol failure for the caller, so this
     * method never returns a partial list.
     */
    suspend fun sync(batch: PushWakeDrain.SyncBatch): List<PushWakeDrain.SyncResult> {
        currentCoroutineContext().ensureActive()
        if (!gate()) return retryAll(batch)
        val results = ArrayList<PushWakeDrain.SyncResult>(batch.items.size)
        for (item in batch.items) {
            currentCoroutineContext().ensureActive()
            results += item.result(process(item))
        }
        return results
    }

    private suspend fun process(item: PushWakeDrain.SyncItem): PushWakeDrain.SyncOutcome {
        val route = item.route
        if (route == null) {
            // The generic marker covers provider deletion, overflow and failed
            // local admission. Without a full indexed pass the only honest
            // answer is to keep the lease and retry later; a supplied scan must
            // itself return only after a bounded pass really finished.
            val scan = fullScan ?: return PushWakeDrain.SyncOutcome.RETRY
            return try {
                if (scan()) PushWakeDrain.SyncOutcome.COMPLETED_WINDOW else PushWakeDrain.SyncOutcome.RETRY
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A scan that threw did not finish; keep the lease and count it.
                diag()
                PushWakeDrain.SyncOutcome.RETRY
            }
        }
        val inspected = try {
            inspect(route)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diag()
            PushWakeDrain.Inspection.RETRY
        }
        currentCoroutineContext().ensureActive()
        return when (inspected) {
            PushWakeDrain.Inspection.DROP -> PushWakeDrain.SyncOutcome.COMPLETED_WINDOW
            PushWakeDrain.Inspection.RETRY -> PushWakeDrain.SyncOutcome.RETRY
            PushWakeDrain.Inspection.ALLOW -> try {
                when (receive(route)) {
                    PushReceiveWindow.Outcome.COMPLETED -> PushWakeDrain.SyncOutcome.COMPLETED_WINDOW
                    PushReceiveWindow.Outcome.RETRY -> PushWakeDrain.SyncOutcome.RETRY
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The window threw; it never produced a completed outcome.
                diag()
                PushWakeDrain.SyncOutcome.RETRY
            }
        }
    }

    private fun diag() {
        try {
            recordFailure()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A broken counter must not change the outcome.
            Logger.getLogger("PushWakeSync").warning("Wake sync diagnostics unavailable")
        }
    }

    private fun retryAll(batch: PushWakeDrain.SyncBatch): List<PushWakeDrain.SyncResult> =
        batch.items.map { it.result(PushWakeDrain.SyncOutcome.RETRY) }
}
