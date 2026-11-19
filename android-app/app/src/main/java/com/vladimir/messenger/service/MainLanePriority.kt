package com.vladimir.messenger.service

/**
 * Which drained events the main lane must take before service chatter.
 *
 * The Rust bus already orders its queue the same way (`is_interactive` and
 * `eviction_rank` in `rust-core/src/engine/events.rs`); this predicate is the
 * Kotlin half of one policy, so the two must not drift apart.
 *
 * Presence belongs here for a reason that is not cosmetic. An observation
 * carries the moment it was made, and [PresenceRecovery] drops anything older
 * than 200 s as "stale" — deliberately, because an old observation must never
 * turn a contact online. When broker restarts flood the queue with service
 * events, a delayed presence frame is not merely late: it arrives already
 * expired and the person reads "offline" about a contact who is right there
 * (owner's report 2026-11-18). Taking presence in the message burst keeps the
 * observation inside its own freshness window instead of shortening the guard.
 */
internal object MainLanePriority {
    private val INTERACTIVE = setOf(
        "message_received",
        "delivery_ack",
        "peer_discovered",
        "peer_lost",
    )

    fun isInteractive(eventType: String): Boolean = eventType in INTERACTIVE
}
