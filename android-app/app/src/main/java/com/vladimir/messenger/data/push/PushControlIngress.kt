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
     * `true` means the caller must drop the content. A null [wire] means the text
     * never arrived as a sealed envelope, so nothing can be imported and a
     * reserved text stays reserved. With the feature disabled this is exactly the
     * old behaviour: one boolean check and no I/O at all.
     */
    suspend fun reservedUnlessEpochAvailable(
        context: android.content.Context,
        text: String?,
        senderId: String,
        wire: String?,
    ): Boolean {
        if (!isReserved(text)) return false
        if (wire == null || !PushServiceLocator.enabled(context)) return true
        return !PushEpochIngress.accept(context, senderId, wire)
    }
}
