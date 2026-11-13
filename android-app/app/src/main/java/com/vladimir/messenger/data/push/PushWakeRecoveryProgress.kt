package com.vladimir.messenger.data.push

/**
 * Durable, compare-and-set progress for a generic recovery pass. The scope is
 * derived from the runtime's current local identity/lifecycle and the claimed
 * recovery marker's random incarnation, never from an incoming push. A snapshot
 * is not authorization for a peer or network effect. Its starting wake revision,
 * cursor, count and failure bit must be stored and authenticated together.
 *
 * [current] must be a fast, non-blocking check. The eventual runtime must also
 * coordinate identity replacement, restore and opt-out with the entire action;
 * snapshot comparisons alone cannot prevent ABA. Progress may be replayed after
 * a crash, so every route effect must be idempotent. Restoring or deleting the
 * progress database is not detectable here.
 */
internal interface PushWakeRecoveryProgress {
    class Snapshot internal constructor(
        val scope: String,
        val startRevision: Long,
        val after: String?,
        val hadFailure: Boolean,
        val visited: Int,
        internal val token: String,
    ) {
        override fun toString(): String = "PushWakeRecoverySnapshot(redacted)"
    }

    sealed interface Result {
        class Ready(val snapshot: Snapshot) : Result {
            override fun toString(): String = "PushWakeRecoveryProgressReady(redacted)"
        }
        object Stale : Result
        object Unavailable : Result
    }

    /**
     * A changed scope starts from the beginning. A newer marker revision does
     * not interrupt an in-progress pass: finish it, then retry a full pass for
     * the newer revision. With an empty cursor, capture the new revision before
     * the first effect. An older revision cannot resume a newer checkpoint.
     */
    fun load(scope: String, revision: Long, current: () -> Boolean): Result

    /** Advance after processing one route; never clear an earlier failure. */
    fun advance(expected: Snapshot, after: String, failed: Boolean, current: () -> Boolean): Result

    /** Reset after the last route or scan budget; a stale snapshot must never reset newer work. */
    fun reset(expected: Snapshot, current: () -> Boolean): Result

    companion object {
        // The journal transactionally caps its records at MAX_ENTRIES, and
        // recoveryPage fails closed for an oversized journal. Twice that bound
        // permits churn while guaranteeing that a stable valid index fits.
        const val MAX_SCAN_ROUTES = 2 * PushRegistrationStore.MAX_ENTRIES
    }
}
