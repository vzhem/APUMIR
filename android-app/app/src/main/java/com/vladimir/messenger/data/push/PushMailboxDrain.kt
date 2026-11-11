package com.vladimir.messenger.data.push

import java.util.concurrent.CancellationException

/**
 * Bounded mailbox traversal, deliberately disconnected from Firebase, storage,
 * the production router and scheduling. The caller binds one scan/checkpoint
 * to a single current identity, pin, route, device and epoch; a checkpoint is
 * not authority. An expired cursor must restart from the beginning. Periodic
 * full scans and the enqueue/worker-exit race still need durable scheduling.
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
) {
    init { require(maxPages in 1..MAX_PAGES) }

    enum class Commit { COMMITTED, ALREADY_COMMITTED, RETRY }

    class Checkpoint private constructor(internal val after: String, internal val unprocessed: Boolean) {
        override fun toString(): String = "PushMailboxCheckpoint(redacted)"

        companion object {
            fun first(): Checkpoint = Checkpoint("-", false)
            /** Persist only with the matching identity, pin, epoch and device. */
            fun resume(after: String, unprocessed: Boolean): Checkpoint? =
                if (PushMailboxProtocol.validId(after)) Checkpoint(after, unprocessed) else null
        }
    }

    sealed interface Result {
        /** A bounded full pass finished; not proof that all messages were delivered. */
        object Completed : Result
        /** At least one envelope could not be durably processed; back off and rescan. */
        object Unprocessed : Result
        /** The server no longer knows the cursor; schedule a fresh pass from the beginning. */
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
        val seen = HashSet<String>()
        repeat(maxPages) {
            if (!current()) return Result.Retry
            val page = when (val result = pull(after)) {
                is PushMailboxHttpClient.PullResult.Page -> result
                PushMailboxHttpClient.PullResult.CursorExpired -> return Result.Restart
                PushMailboxHttpClient.PullResult.Throttled, PushMailboxHttpClient.PullResult.Rejected,
                PushMailboxHttpClient.PullResult.Unavailable -> return Result.Retry
            }
            if (!current()) return Result.Retry
            // Defensive check even though the production HTTP codec validates
            // this already: do not process any part of a malformed page.
            if (page.items.size > PushMailboxProtocol.MAX_PAGE_ITEMS) return Result.Retry
            val items = page.items.toList()
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
        }
        return Result.Continue(Checkpoint.resume(after, unprocessed) ?: return Result.Restart)
    }

    companion object {
        const val MAX_PAGES = 16
    }
}
