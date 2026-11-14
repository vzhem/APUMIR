package com.vladimir.messenger.data.push

/**
 * Decides *whether* a correspondent should be woken, and with what.
 *
 * This is the missing direction of the feature: until now the app could receive a
 * signal, register a route and store ciphertext, but nothing ever asked the
 * service to wake the other side, so a sleeping phone was never woken by us.
 *
 * The rules are deliberately narrow, because a wake is the one action in this
 * design that a third party can also trigger - a leaked provider token is enough
 * to post the empty signal. Therefore:
 *
 *  - only a correspondent with a live pin *and* an accepted registration of our
 *    current ACTIVE epoch can be woken: the wake carries a capability derived
 *    from that exact pair, so it cannot address anyone else;
 *  - a wake is only a request to look in the mailbox. It carries no text, no
 *    sender, no chat id and no unread count, and it is never called delivery;
 *  - the budget is bounded and local. A wake already sent for a peer inside
 *    [MIN_INTERVAL_MS] is not sent again: a burst of messages must not become a
 *    burst of provider calls, and a loop of failures must not become a flood.
 *
 * No I/O happens here; the caller owns the transport and the clock.
 */
internal class PushWakeSignal(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
) {
    /** What the caller learned about a candidate target. */
    class Candidate(
        /** Null while our own route is not registered, so no wake can exist. */
        val material: PushWakeCrypto.Material?,
        /** The journal's last accepted wake for this peer, or 0. */
        val lastWakeAtMs: Long,
    )

    sealed interface Decision {
        /** A wake may be attempted; the caller closes nothing, Material owns that. */
        class Send(val request: PushWakeRequest) : Decision
        /** Too soon after the previous wake for this peer. */
        object TooSoon : Decision
        /** No registered capability for this peer: nothing addressable exists. */
        object NotRegistered : Decision
    }

    fun decide(candidate: Candidate): Decision {
        val material = candidate.material ?: return Decision.NotRegistered
        val now = nowMs()
        val last = candidate.lastWakeAtMs
        if (last > 0 && now >= last && now - last < minIntervalMs) return Decision.TooSoon
        val request = material.wakeRequest() ?: return Decision.NotRegistered
        return Decision.Send(request)
    }

    companion object {
        /**
         * Ten minutes per correspondent. Long enough that a conversation does not
         * cost one provider call per message, short enough that a peer who went
         * offline mid-conversation is still reachable.
         */
        const val MIN_INTERVAL_MS: Long = 10 * 60 * 1000

        /**
         * `ACCEPTED` means the Worker took the request. It does not mean the peer
         * was woken, saw anything, or that a message exists.
         */
        fun wasAccepted(result: PushHttpClient.WakeResult): Boolean =
            result == PushHttpClient.WakeResult.ACCEPTED
    }
}
