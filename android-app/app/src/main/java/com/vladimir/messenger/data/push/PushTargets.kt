package com.vladimir.messenger.data.push

/**
 * Enumerates the peers this installation may legitimately talk to.
 *
 * Two independent sources must agree before a peer is used:
 *  - the live pin store (Room, strict TOFU, never created here);
 *  - the encrypted registration index (only routes confirmed by the server).
 *
 * The gate (`PushRegistrationStore.recoveryPage` strictness is preserved by
 * `findRoute`) is re-checked for every entry, so a stale hint is skipped rather
 * than repaired. Nothing here performs network I/O, creates a contact or opens a
 * chat: the ids are the only output, and they are not logged.
 */
internal class PushTargets(
    private val page: (after: String?, limit: Int) -> PushRegistrationStore.RecoveryPage,
    private val find: (String) -> PushRegistrationStore.RouteResult,
    private val maxPages: Int = DEFAULT_PAGES,
    /**
     * Live pins, used only to *seed* a first registration.
     *
     * The index cannot bootstrap itself: it holds only routes the server already
     * confirmed, so a fresh install enumerates nothing and therefore registers
     * nobody - a closed loop that no amount of retrying opens. A pinned peer is
     * the independent source that breaks it, and it is still just a candidate:
     * the runner re-resolves the pair from the same pin before signing, so a pin
     * that disappeared in between yields `NotReady`, not a registration.
     */
    private val seededPeers: suspend () -> Pair<Outcome, List<Peer>>? = { null },
    private val recordFailure: () -> Unit = {},
) {
    enum class Outcome {
        /** Every live participant was enumerated; the list may still be empty. */
        COMPLETED,
        /** At least one page or record could not be read: do not act on a partial list. */
        RETRY,
    }

    class Peer(val localId: String, val peerId: String, val generation: Long) {
        override fun toString(): String = "PushTarget(redacted)"
    }

    /**
     * Every peer worth a registration attempt: what the index already knows,
     * plus pinned peers that have no route yet. The second half is the only way
     * a first route can ever exist.
     */
    suspend fun candidates(): Pair<Outcome, List<Peer>> {
        val (outcome, indexed) = enumerate()
        if (outcome != Outcome.COMPLETED) return outcome to emptyList()
        val seeds = try {
            seededPeers()
        } catch (_: Exception) {
            // A broken pin store is not "no pins": do not act on a partial set.
            diag()
            return Outcome.RETRY to emptyList()
        } ?: return outcome to indexed
        val (seedOutcome, pinned) = seeds
        if (seedOutcome != Outcome.COMPLETED) return Outcome.RETRY to emptyList()
        val known = indexed.map { it.peerId }.toHashSet()
        return Outcome.COMPLETED to (indexed + pinned.filter { it.peerId !in known })
    }

    fun enumerate(): Pair<Outcome, List<Peer>> {
        if (maxPages !in 1..MAX_PAGES) return Outcome.RETRY to emptyList()
        val peers = LinkedHashMap<String, Peer>()
        var cursor: String? = null
        var retry = false
        repeat(maxPages) {
            val pageResult = try {
                page(cursor, PAGE_SIZE)
            } catch (_: Exception) {
                // An unreadable page is not an empty index.
                diag()
                return Outcome.RETRY to emptyList()
            }
            val routes = when (pageResult) {
                is PushRegistrationStore.RecoveryPage.Ready -> pageResult.routes
                // A damaged index is not an empty index.
                PushRegistrationStore.RecoveryPage.Invalid,
                PushRegistrationStore.RecoveryPage.Unavailable -> return Outcome.RETRY to emptyList()
            }
            if (routes.isEmpty()) {
                return if (retry || pageResult.next != null) {
                    Outcome.RETRY to emptyList()
                } else {
                    Outcome.COMPLETED to peers.values.toList()
                }
            }
            for (route in routes) {
                val found = try {
                    find(route)
                } catch (_: Exception) {
                    diag()
                    retry = true
                    continue
                }
                when (found) {
                    PushRegistrationStore.RouteResult.Missing -> continue
                    PushRegistrationStore.RouteResult.Unavailable -> {
                        retry = true
                        continue
                    }
                    is PushRegistrationStore.RouteResult.Found -> {
                        if (found.localId == found.peerId) continue
                        peers[found.peerId + "\u0000" + found.localId] =
                            Peer(found.localId, found.peerId, found.generation)
                    }
                }
            }
            cursor = pageResult.next ?: return if (retry) {
                Outcome.RETRY to emptyList()
            } else {
                Outcome.COMPLETED to peers.values.toList()
            }
        }
        // The page budget is a bound, not a finished enumeration.
        return Outcome.RETRY to emptyList()
    }

    private fun diag() {
        try {
            recordFailure()
        } catch (_: Exception) {
            // Never log ids or exception text; a broken counter keeps the outcome.
            java.util.logging.Logger.getLogger("PushTargets").warning("Enumeration diagnostics unavailable")
        }
    }

    companion object {
        /** One pinned peer with no indexed route yet: a seed, never authority. */
        internal fun seed(peerId: String, localId: String): Peer = Peer(localId, peerId, 0L)

        /** Must match the journal's own page size limit. */
        internal const val PAGE_SIZE = 16
        internal const val DEFAULT_PAGES = 8
        internal const val MAX_PAGES = 16
    }
}
