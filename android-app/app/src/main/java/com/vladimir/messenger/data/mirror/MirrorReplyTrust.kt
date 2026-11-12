package com.vladimir.messenger.data.mirror

import android.content.Context
import com.vladimir.messenger.data.security.MessageSealer

/**
 * A peer's encrypted mirror payload is not automatically a trusted paired
 * frame: the legacy MessageSealer fallback can also open a contact's frame.
 * For reply metadata, require both the active device's source attestation and
 * either the pairing room key or a legacy frame signed by our own identity.
 * Call [afterOpen] only after MirrorChannel.openPayload succeeded for the same wire.
 */
internal object MirrorReplyTrust {
    fun verified(sourceAuthenticated: Boolean, sharedKey: ByteArray?, wire: String, signedBySelf: () -> Boolean): Boolean {
        if (!sourceAuthenticated) return false
        if (sharedKey != null && MirrorFrameCipher.open(sharedKey, wire) != null) return true
        return signedBySelf()
    }

    fun afterOpen(context: Context, nodeId: String, wire: String, sourceAuthenticated: Boolean): Boolean {
        if (!sourceAuthenticated) return false
        return verified(true, MirrorRoomToken.loadSyncKey(context), wire) {
            nodeId.isNotBlank() && MessageSealer.openedByExpectedSender(wire, nodeId)
        }
    }
}
