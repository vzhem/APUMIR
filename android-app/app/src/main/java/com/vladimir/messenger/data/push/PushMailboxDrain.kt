package com.vladimir.messenger.data.push

import java.util.concurrent.CancellationException

/**
 * Bounded mailbox traversal, deliberately disconnected from Firebase, storage,
 * the production router and scheduling. The caller binds and integrity-protects
 * the entire scan/checkpoint (including recent IDs and page count) to a single
 * current identity, pin, route, device and epoch; a checkpoint is not authority.
 * An expired cursor or an exhausted scan budget must restart from the beginning
 * with backoff. Periodic full scans and the enqueue/worker-exit race still need
 * durable scheduling. A bounded history catches short cycles across worker runs;
 * the page budget bounds successfully checkpointed progress and must never be
 * reset mid-scan. A retry can replay pages since the last checkpoint, so a future
 * scheduler must also persist backoff and independently bound repeated failures.
 *
 * [commit] receives ONLY the untrusted envelope, not the server's outer id or
 * receipt. It must authenticate against an independently existing pin, validate
 * the original id/participants inside the ciphertext, coordinate identity,
 * pin and restore through the actual Room transaction, and return COMMITTED or
 * ALREADY_COMMITTED only after the result (or a safe tombstone) is durable.
 * An existing ID from another sender, conflicting content, a failed save, or an
 * unsupported control packet is not success. Do not route arbitrary decrypted
 * controls or create a TOFU pin here. Notification work must be durable too.
 *
 * [ack] must independently revalidate trust and sign the exact receipts. It is
 * called only for committed items; 204 means operation accepted, not that a row
 * matched or that the sender received a delivery receipt. A failure or crash may
 * cause already committed messages to be read again, so commit must be
 * idempotent across the ordinary transport and mailbox. [current] is only a
 * quick preflight, not an ABA-resistant lifecycle lock or cancellation of IO.
 */
internal class PushMailboxDrain(
    private val pull: suspend (String) -> PushMailboxHttpClient.PullResult,
    private val commit: suspend (String) -> Commit,
    private val ack: suspend (List<PushMailboxReceipt>) -> PushMailboxHttpClient.AckResult,
    private val current: () -> Boolean,
    private val recordFailure: () -> Unit,
    private val maxPages: Int = MAX_PAGES,
    private val maxScanPages: Int = MAX_SCAN_PAGES,
) {
    init { require(maxPages in 1..MAX_PAGES && maxScanPages in 1..MAX_SCAN_PAGES) }

    enum class Commit { COMMITTED, ALREADY_COMMITTED, RETRY }

    class Checkpoint private constructor(
        internal val after: String,
        internal val unprocessed: Boolean,
        internal val pages: Int,
        internal val recent: List<String>,
    ) {
        override fun toString(): String = "PushMailboxCheckpoint(redacted)"

        companion object {
            fun first(): Checkpoint = Checkpoint("-", false, 0, emptyList())

            /** Persist all fields together under the matching identity, pin, epoch and device. */
            fun resume(after: String, unprocessed: Boolean, pages: Int, recent: List<String>): Checkpoint? {
                if (!PushMailboxProtocol.validId(after) || pages !in 1..MAX_SCAN_PAGES) return null
                // Copy at most the bound, even if an untrusted mutable list is supplied.
                val snapshot = ArrayList<String>(MAX_RECENT_IDS)
                for (id in recent) {
                    if (snapshot.size == MAX_RECENT_IDS || !PushMailboxProtocol.validId(id)) return null
                    snapshot.add(id)
                }
                if (snapshot.isEmpty() || snapshot.last() != after || snapshot.size > pages * PushMailboxProtocol.MAX_PAGE_ITEMS ||
                    snapshot.toSet().size != snapshot.size
                ) return null
                return Checkpoint(after, unprocessed, pages, java.util.Collections.unmodifiableList(snapshot))
            }
        }
    }

    sealed interface Result {
        /** A bounded full pass finished; not proof that all messages were delivered. */
        object Completed : Result
        /** At least one envelope could not be durably processed; back off and rescan. */
        object Unprocessed : Result
        /** Expired cursor or exhausted budget; back off and start a fresh full pass. */
        object Restart : Result
        /** An operational failure or stale trust; retry without consuming the wake. */
        object Retry : Result
        /** More pages remain to be checked; durably schedule this checkpoint. */
        class Continue(val checkpoint: Checkpoint) : Result {
            override fun toString(): String = "PushMailboxContinue(redacted)"
        }
    }

    suspend fun run(checkpoint: Checkpoint = Checkpoint.first()): Result = try {
        scan(checkpoint)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Never expose the route, ciphertext, original id, receipt or exception.
        recordFailure()
        Result.Retry
    }

    private suspend fun scan(checkpoint: Checkpoint): Result {
        if (checkpoint.after != "-" && !PushMailboxProtocol.validId(checkpoint.after)) return Result.Restart
        if (!current()) return Result.Retry
        var after = checkpoint.after
        var unprocessed = checkpoint.unprocessed
        var pages = checkpoint.pages
        val recent = java.util.ArrayDeque(checkpoint.recent)
        // Retain all IDs seen during this run as well as the bounded history
        // from the checkpoint. Only the recent suffix is persisted.
        val seen = HashSet(checkpoint.recent)
        repeat(maxPages) {
            if (pages >= maxScanPages) return Result.Restart
            if (!current()) return Result.Retry
            val page = when (val result = pull(after)) {
                is PushMailboxHttpClient.PullResult.Page -> result
                PushMailboxHttpClient.PullResult.CursorExpired -> return Result.Restart
                PushMailboxHttpClient.PullResult.Throttled, PushMailboxHttpClient.PullResult.Rejected,
                PushMailboxHttpClient.PullResult.Unavailable -> return Result.Retry
            }
            if (!current()) return Result.Retry
            // Defensive bounded snapshot even though the HTTP codec validates
            // this already; a mutable callback list cannot grow this allocation.
            val items = ArrayList<PushMailboxItem>(PushMailboxProtocol.MAX_PAGE_ITEMS)
            for (item in page.items) {
                if (items.size == PushMailboxProtocol.MAX_PAGE_ITEMS) return Result.Retry
                items.add(item)
            }
            if (items.isEmpty()) {
                if (page.next != null) return Result.Retry
                return if (unprocessed) Result.Unprocessed else Result.Completed
            }
            if (page.next != items.last().id || items.any {
                    !PushMailboxProtocol.validId(it.id) || it.id == after || !seen.add(it.id) ||
                        !PushMailboxProtocol.validId(it.receipt) || !PushMailboxProtocol.validTimestamp(it.expiresAt) ||
                        !PushMailboxProtocol.validEnvelope(it.envelope)
                }
            ) return Result.Retry

            val receipts = ArrayList<PushMailboxReceipt>(items.size)
            for (item in items) {
                if (!current()) return Result.Retry
                val result = try {
                    commit(item.envelope)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    recordFailure()
                    Commit.RETRY
                }
                if (!current()) return Result.Retry
                when (result) {
                    Commit.COMMITTED, Commit.ALREADY_COMMITTED -> receipts.add(PushMailboxReceipt(item.id, item.receipt))
                    Commit.RETRY -> unprocessed = true
                }
            }
            if (receipts.isNotEmpty()) {
                if (!current()) return Result.Retry
                // The restricted signer/Worker require sorted unique ids.
                val sorted = receipts.sortedBy { it.id }
                if (ack(sorted) != PushMailboxHttpClient.AckResult.ACCEPTED) return Result.Retry
                if (!current()) return Result.Retry
            }
            after = page.next!!
            for (item in items) {
                recent.addLast(item.id)
                if (recent.size > MAX_RECENT_IDS) recent.removeFirst()
            }
            pages++
        }
        return Result.Continue(Checkpoint.resume(after, unprocessed, pages, recent.toList()) ?: return Result.Restart)
    }

    companion object {
        const val MAX_PAGES = 16
        // The server currently limits a route to 4096 rows; a larger bound also
        // allows partial pages while bounding checkpointed long-cycle progress.
        const val MAX_SCAN_PAGES = 8192
        const val MAX_RECENT_IDS = 64
    }
}
