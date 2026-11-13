package com.vladimir.messenger.service

import com.vladimir.messenger.data.local.dao.PendingNotificationCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * One bounded, resumable pass through locally pending notifications. The high
 * water mark keeps a stream of newer arrivals from starving an older failed
 * item: failures remain pending and are revisited after this finite pass ends.
 * Room rowids and the checkpoint are only scheduling hints, not identities or
 * authorization. A lost/stale checkpoint causes a safe rescan; restoring Room
 * or replacing the local identity still needs a separate lifecycle boundary.
 */
internal class IncomingNotificationSweep(
    private val maxPendingRowId: suspend () -> Long?,
    private val page: suspend (after: Long?, through: Long, limit: Int) -> List<PendingNotificationCandidate>,
    private val process: suspend (String) -> IncomingNotificationProcessor.Result,
    private val current: () -> Boolean,
    private val recordFailure: (Exception) -> Unit,
) {
    data class Cursor(
        val after: Long? = null,
        val through: Long? = null,
        val hadFailure: Boolean = false,
        val hadBlocked: Boolean = false,
    ) {
        fun valid(): Boolean = if (through == null) {
            after == null && !hadFailure && !hadBlocked
        } else {
            after != null && after <= through
        }
    }

    data class Step(val cursor: Cursor, val retry: Boolean)

    /** Null means identity became stale: do not save a checkpoint or claim success. */
    suspend fun run(saved: Cursor, limit: Int = 64): Step? {
        require(limit in 1..128)
        currentCoroutineContext().ensureActive()
        if (!current()) return null
        val cursor = saved.takeIf { it.valid() } ?: Cursor()
        val max = cursor.through ?: maxPendingRowId()
        currentCoroutineContext().ensureActive()
        if (!current()) return null
        if (max == null) return Step(Cursor(), retry = false)
        // A null starting cursor includes even zero/negative SQLite rowids in
        // an unusual restored database; no timestamp is used for ordering.
        val rows = page(cursor.after, max, limit)
        currentCoroutineContext().ensureActive()
        if (!current()) return null
        if (rows.size > limit) return Step(cursor, retry = true)
        var last = cursor.after
        val ids = HashSet<String>()
        for (row in rows) {
            if ((last != null && row.sequence <= last) || row.sequence > max || row.id.isBlank() || !ids.add(row.id)) {
                return Step(cursor, retry = true)
            }
            last = row.sequence
        }
        var hadFailure = cursor.hadFailure
        var hadBlocked = cursor.hadBlocked
        for (row in rows) {
            currentCoroutineContext().ensureActive()
            if (!current()) return null
            try {
                when (process(row.id)) {
                    IncomingNotificationProcessor.Result.DONE -> Unit
                    IncomingNotificationProcessor.Result.RETRY -> hadFailure = true
                    IncomingNotificationProcessor.Result.BLOCKED -> hadBlocked = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                // A broken notification (or diagnostic callback) must not hide later rows.
                hadFailure = true
                try {
                    recordFailure(error)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                }
            }
            currentCoroutineContext().ensureActive()
            if (!current()) return null
        }
        if (rows.size == limit) return Step(Cursor(last, max, hadFailure, hadBlocked), retry = true)
        // End of the fixed window: wrap before retrying failures or newer rows.
        val remaining = maxPendingRowId()
        currentCoroutineContext().ensureActive()
        if (!current()) return null
        // A purely system-blocked queue waits for the independent periodic job
        // or an explicit resume after permission/settings change; do not spin a
        // 30-second retry forever. A new row, transient failure, or ambiguous
        // state still asks WorkManager to retry. Rowid reuse can defer an
        // arrival behind the cursor until the independent periodic pass.
        val retry = remaining != null && (remaining > max || hadFailure || !hadBlocked)
        return Step(Cursor(), retry = retry)
    }
}
