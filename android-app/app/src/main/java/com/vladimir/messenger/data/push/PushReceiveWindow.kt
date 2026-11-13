package com.vladimir.messenger.data.push

import java.util.concurrent.CancellationException
import java.util.logging.Logger

/**
 * One bounded mailbox receive window for a single wake lease.
 *
 * It wires [PushMailboxDrain] (page traversal, ordering, ACK policy) to the
 * caller's already-verified trust material. It does not open ciphertext, write
 * Room or send a notification itself: those are the injected callbacks, and the
 * only way this class reports success is [Outcome.COMPLETED], which is produced
 * solely by [PushMailboxDrain.Result.Completed].
 *
 * Everything except `Completed` means "the window did not finish": the wake
 * lease must stay leased for recovery. A completed window is still not a
 * delivered message and not a receipt.
 */
internal class PushReceiveWindow(
    private val recordFailure: () -> Unit = {},
) {
    enum class Outcome { COMPLETED, RETRY }

    /**
     * @param pull pulls one page after the opaque cursor (already signed by the
     *        caller's restricted signer).
     * @param commit opens one envelope against the pinned peer and commits it
     *        durably; it must return COMMITTED/ALREADY_COMMITTED only after
     *        durable local result.
     * @param ack acknowledges exactly the committed receipts.
     * @param current re-checked before every effect; false abandons the window.
     */
    suspend fun run(
        pull: suspend (String) -> PushMailboxHttpClient.PullResult,
        commit: suspend (String) -> PushMailboxDrain.Commit,
        ack: suspend (List<PushMailboxReceipt>) -> PushMailboxHttpClient.AckResult,
        current: () -> Boolean,
        maxPages: Int = DEFAULT_PAGES,
    ): Outcome {
        if (maxPages !in 1..PushMailboxDrain.MAX_PAGES) return Outcome.RETRY
        val drain = PushMailboxDrain(pull, commit, ack, current, recordFailure, maxPages)
        return try {
            when (val result = drain.run()) {
                // A pass that ended normally proves only that the server had no
                // more ciphertext for this route at that moment.
                PushMailboxDrain.Result.Completed -> Outcome.COMPLETED
                // Unprocessed leaves at least one envelope unhandled: not done.
                PushMailboxDrain.Result.Unprocessed -> Outcome.RETRY
                PushMailboxDrain.Result.Restart -> Outcome.RETRY
                PushMailboxDrain.Result.Retry -> Outcome.RETRY
                // More pages exist: the caller must continue from the
                // checkpoint rather than claim the window finished.
                is PushMailboxDrain.Result.Continue -> Outcome.RETRY
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Never include route, ciphertext, receipts or exception text.
            try {
                recordFailure()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A broken counter cannot turn a failure into success.
                Logger.getLogger("PushReceiveWindow").warning("Failure counter unavailable")
            }
            Outcome.RETRY
        }
    }

    private companion object {
        const val DEFAULT_PAGES = PushMailboxDrain.MAX_PAGES
    }
}
