package com.vladimir.messenger.data.push

import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.logging.Logger

/**
 * The real mailbox receive window behind [PushReceiveWindow]: a signed pull, one
 * authenticated commit per envelope, and an ACK only for durably committed
 * receipts.
 *
 * Trust rules:
 *  - local profile, pinned peer, local secret and registration are re-read from
 *    the live stores for every window ([PushTrustResolver]);
 *  - the route we were woken for must equal the route re-derived from those
 *    values, otherwise nothing is read at all;
 *  - before every network effect the same values are read again and compared
 *    with the baseline snapshot of this window, so a pin change, a re-pairing,
 *    an identity swap or a restore aborts the window instead of acknowledging
 *    something that is no longer trusted;
 *  - a commit re-opens the envelope under that same pinned peer and writes it
 *    through `PushMailboxRoomStore` in one transaction.
 *
 * A completed window still means only "the server had no further ciphertext for
 * this route at that moment". It is not a delivery receipt, and a row in Room is
 * not a notification the user saw. The re-checks are snapshots, not a lock: a
 * change landing between a check and the following request can still be
 * accepted by the server.
 */
internal class PushMailboxReceiver(
    private val resolver: PushTrustResolver,
    private val client: PushMailboxHttpClient,
    private val incoming: PushMailboxIncoming,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val windowBudgetMs: () -> Long = { System.currentTimeMillis() + WINDOW_BUDGET_MS },
    private val recordFailure: () -> Unit = {},
) {
    suspend fun receive(route: String, peerId: String): PushReceiveWindow.Outcome {
        val pass = try {
            resolver.resolve(peerId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diag()
            null
        } ?: return PushReceiveWindow.Outcome.RETRY
        try {
            val state = pass.registration ?: return PushReceiveWindow.Outcome.RETRY
            val material = pass.derive(state.epochHex) ?: return PushReceiveWindow.Outcome.RETRY
            try {
                val derived = material.routeHex() ?: return PushReceiveWindow.Outcome.RETRY
                // A stale hint proves nothing: only the freshly derived route
                // may open a window.
                if (derived != route) return PushReceiveWindow.Outcome.RETRY
                val anchor = baseline(pass.localId, pass.peerId) ?: return PushReceiveWindow.Outcome.RETRY
                try {
                    if (!fresh(anchor)) return PushReceiveWindow.Outcome.RETRY
                    val until = windowBudgetMs()
                    return PushReceiveWindow(recordFailure).run(
                        pull = { after ->
                            if (expired(until) || !fresh(anchor)) {
                                PushMailboxHttpClient.PullResult.Unavailable
                            } else {
                                val request = material.mailboxPull(
                                    pass.localBinding, pass.pinnedPeerBinding, state, nowSeconds(), after,
                                ) ?: return@run PushMailboxHttpClient.PullResult.Rejected
                                client.pull(request)
                            }
                        },
                        commit = { envelope ->
                            if (expired(until) || !fresh(anchor)) {
                                PushMailboxDrain.Commit.RETRY
                            } else {
                                incoming.commit(
                                    wire = envelope,
                                    expectedSender = pass.peerId,
                                    expectedRecipient = pass.localId,
                                    localBinding = pass.localBinding,
                                    localSecret = pass.localSecret,
                                    current = { !expired(until) },
                                )
                            }
                        },
                        ack = { receipts ->
                            if (expired(until) || !fresh(anchor)) {
                                PushMailboxHttpClient.AckResult.UNAVAILABLE
                            } else {
                                val request = material.mailboxAck(
                                    pass.localBinding, pass.pinnedPeerBinding, state, nowSeconds(), receipts,
                                ) ?: return@run PushMailboxHttpClient.AckResult.REJECTED
                                client.ack(request)
                            }
                        },
                        // Coarse budget guard; the real trust check is the fresh
                        // snapshot taken before every effect above.
                        current = { !expired(until) },
                    )
                } finally {
                    anchor.localBinding.fill(0)
                    anchor.pinnedPeerBinding.fill(0)
                    anchor.localSecret.fill(0)
                }
            } finally {
                material.close()
            }
        } finally {
            pass.close()
        }
    }

    private fun expired(until: Long): Boolean = System.currentTimeMillis() > until

    private suspend fun baseline(localId: String, peerId: String): PushDrainTrustGate.Snapshot? =
        try {
            resolver.snapshot(localId, peerId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diag()
            null
        }

    /** Re-read the live stores and compare with the baseline taken for this window. */
    private suspend fun fresh(anchor: PushDrainTrustGate.Snapshot): Boolean {
        val now = baseline(anchor.senderId, anchor.recipientId) ?: return false
        return try {
            anchor.senderId == now.senderId && anchor.recipientId == now.recipientId &&
                MessageDigest.isEqual(anchor.localBinding, now.localBinding) &&
                MessageDigest.isEqual(anchor.pinnedPeerBinding, now.pinnedPeerBinding) &&
                MessageDigest.isEqual(anchor.localSecret, now.localSecret)
        } finally {
            now.localBinding.fill(0)
            now.pinnedPeerBinding.fill(0)
            now.localSecret.fill(0)
        }
    }

    private fun diag() {
        try {
            recordFailure()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Logger.getLogger("PushMailboxReceiver").warning("Receive diagnostics unavailable")
        }
    }

    companion object {
        /** One window must finish inside this budget or it retries. */
        internal const val WINDOW_BUDGET_MS = 90_000L
    }
}
