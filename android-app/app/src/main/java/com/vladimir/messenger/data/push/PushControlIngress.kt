package com.vladimir.messenger.data.push

/**
 * Early quarantine for the reserved push-control family while remote push is
 * disabled. This is a classifier, NOT authentication: callers must discard
 * matching plaintext or successfully opened content without learning a pin,
 * creating a chat, mirroring, sending an ACK, or running another handler.
 *
 * Even malformed or whitespace-prefixed controls are reserved. This agrees
 * with the chat repository's whitespace-tolerant service-packet guard, but
 * runs before any of the other receive-path effects. A future enabled receiver
 * must replace this quarantine only with a prior-pin/identity-safe handler.
 */
internal object PushControlIngress {
    private val family = PushEpochProtocol.PREFIX.removeSuffix("|")

    fun isReserved(text: String?): Boolean {
        if (text == null) return false
        var start = 0
        while (start < text.length && text[start].isWhitespace()) start++
        return text.regionMatches(start, family, 0, family.length)
    }

    /**
     * Quarantine check that gives the push feature its one authenticated
     * exception: [PushEpochIngress] may consume a sealed epoch packet from the
     * pinned peer.
     *
     * Always returns `true` for every reserved `APUPUSH…` text so neither an
     * invalid frame nor an accepted epoch announcement can ever fall through to
     * create a phantom 1:1 contact/chat, learn a TOFU pin, mirror, or bump
     * message counters. [onEpochAccepted] runs only when [wire] was a sealed
     * envelope whose epoch packet [PushEpochIngress.accept] authenticated and
     * accepted (`APPLIED` or `DUPLICATE`), allowing the caller to clean up the
     * sender's durable relay copy without ever ACKing unsealed or rejected text.
     */
    suspend fun reservedUnlessEpochAvailable(
        context: android.content.Context,
        text: String?,
        senderId: String,
        wire: String?,
        onEpochAccepted: () -> Unit = {},
    ): Boolean {
        if (!isReserved(text)) return false
        if (wire == null || !PushServiceLocator.enabled(context)) return true
        if (PushEpochIngress.accept(context, senderId, wire)) onEpochAccepted()
        return true
    }
}
