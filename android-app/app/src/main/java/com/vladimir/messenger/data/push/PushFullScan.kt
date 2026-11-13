package com.vladimir.messenger.data.push

import java.util.concurrent.CancellationException
import java.util.logging.Logger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Bounded full pass over *one* device's registration hints after a generic
 * recovery marker. It exists because a marker carries no route: the app must
 * find out which of its own registered routes are still live before it can ask
 * the server for ciphertext.
 *
 * For every page of the local index it re-derives the route from the *current*
 * pin and the *current* local identity, compares it with the stored hint, and
 * then runs a normal receive window. A stale hint (pin changed, contact removed,
 * journal replaced) is skipped, never "repaired" by writing a new index entry:
 * repairing trust from a hint would be TOFU by another name.
 *
 * Limits that remain after this class:
 *  - it does not serialize identity/pin/restore replacement, and a change
 *    between the re-derivation and the receive window is only noticed by the
 *    window's own `current` callback;
 *  - the page cursor is in memory for one call, so a marker produced while the
 *    pass runs needs another marker (periodic recovery already covers that);
 *  - a completed pass means "every bounded page was visited", not "every
 *    message was delivered".
 */
internal class PushFullScan(
    /** Bounded keyset page of opaque route hints (see [PushRegistrationStore.recoveryPage]). */
    private val page: (after: String?, limit: Int) -> PushRegistrationStore.RecoveryPage,
    /** Resolves one hint; the result is a hint, not authorization. */
    private val find: (String) -> PushRegistrationStore.RouteResult,
    private val trust: (localId: String, peerId: String) -> PushDrainTrustGate.Snapshot?,
    private val derive: (localId: String, peerId: String, snapshot: PushDrainTrustGate.Snapshot) -> String?,
    private val receive: suspend (route: String) -> PushReceiveWindow.Outcome,
    private val recordFailure: () -> Unit = {},
) {
    enum class Outcome {
        /** Every page of the bounded index was visited. */
        COMPLETED,
        /** At least one live route could not finish its window. */
        RETRY,
        /** The index could not be read or exceeded its own bound. */
        UNAVAILABLE,
    }

    suspend fun run(maxPages: Int = DEFAULT_PAGES): Outcome {
        if (maxPages !in 1..MAX_PAGES) return Outcome.RETRY
        var cursor: String? = null
        var retry = false
        repeat(maxPages) {
            currentCoroutineContext().ensureActive()
            val current = when (val read = page(cursor, PAGE_SIZE)) {
                is PushRegistrationStore.RecoveryPage.Ready -> read
                PushRegistrationStore.RecoveryPage.Invalid -> return Outcome.UNAVAILABLE
                PushRegistrationStore.RecoveryPage.Unavailable -> return Outcome.UNAVAILABLE
            }
            // An empty page that still advertises a cursor is an anomaly: treat
            // it as unfinished instead of declaring a complete pass.
            if (current.routes.isEmpty()) {
                return if (retry || current.next != null) Outcome.RETRY else Outcome.COMPLETED
            }
            for (route in current.routes) {
                currentCoroutineContext().ensureActive()
                val found = when (val hit = find(route)) {
                    is PushRegistrationStore.RouteResult.Found -> hit
                    // A hint whose record vanished is stale; skip, do not repair.
                    PushRegistrationStore.RouteResult.Missing -> continue
                    PushRegistrationStore.RouteResult.Unavailable -> {
                        retry = true
                        continue
                    }
                }
                val snapshot = try {
                    trust(found.localId, found.peerId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A store that cannot produce a snapshot is a failed check,
                    // never a reason to skip the route silently.
                    diag()
                    null
                }
                if (snapshot == null) {
                    retry = true
                    continue
                }
                val expected = try {
                    derive(found.localId, found.peerId, snapshot)
                } finally {
                    wipe(snapshot)
                }
                // A route that does not match the live pin/identity is stale.
                if (expected == null || expected != route) continue
                currentCoroutineContext().ensureActive()
                when (try {
                    receive(route)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    diag()
                    PushReceiveWindow.Outcome.RETRY
                }) {
                    PushReceiveWindow.Outcome.COMPLETED -> Unit
                    PushReceiveWindow.Outcome.RETRY -> retry = true
                }
            }
            cursor = current.next ?: return if (retry) Outcome.RETRY else Outcome.COMPLETED
        }
        // The page budget is a safety bound, not a silent "finished".
        return Outcome.RETRY
    }

    private fun diag() {
        try {
            recordFailure()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A broken counter must not change the outcome, but it must be visible.
            Logger.getLogger("PushFullScan").warning("Recovery diagnostics unavailable")
        }
    }

    private fun wipe(snapshot: PushDrainTrustGate.Snapshot) {
        snapshot.localBinding.fill(0)
        snapshot.pinnedPeerBinding.fill(0)
        snapshot.localSecret.fill(0)
    }

    private companion object {
        const val PAGE_SIZE = 16
        const val DEFAULT_PAGES = 16
        // The journal itself holds at most 2048 entries; 128*16 is its bound.
        const val MAX_PAGES = 128
    }
}
