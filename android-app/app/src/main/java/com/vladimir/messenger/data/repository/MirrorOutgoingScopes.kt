package com.vladimir.messenger.data.repository

import com.vladimir.messenger.data.mirror.MirrorRow

/** Check raw and canonical peer scopes before creating a chat for a paired frame. */
internal fun mirrorPeerPermitted(peer: String, messageId: String, isCleared: (String, String) -> Boolean): Boolean {
    if (!MirrorOutgoingPolicy.validRecipient(peer)) return false
    val canonical = OutboxRecipient.normalize(peer)
    return !isCleared(peer, messageId) && (canonical == peer || !isCleared(canonical, messageId))
}

/** A blank legacy echo may only resolve an existing local chat by its ID. */
internal fun mirrorEchoMayResolve(row: MirrorRow, isCleared: (String, String) -> Boolean): Boolean =
    row.recipientId.isBlank() || mirrorPeerPermitted(row.recipientId, row.id, isCleared)
