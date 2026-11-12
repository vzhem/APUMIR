package com.vladimir.messenger.data.mirror

import android.content.Context
import com.vladimir.messenger.data.file.FileExchangeKeyStore
import com.vladimir.messenger.data.security.MessageSealer
import com.vladimir.messenger.data.security.SealedWire
import java.security.MessageDigest

/**
 * A peer's encrypted mirror payload is not automatically a trusted paired
 * frame: the legacy MessageSealer fallback can also open a contact's frame.
 * For reply metadata, require the active device's source attestation and either
 * the pairing room key or a legacy signer binding byte-identical to our local
 * binding. A claimed legacy node ID alone is not proof of our identity.
 * Call [afterOpen] only after MirrorChannel.openPayload succeeded for the same wire.
 */
internal object MirrorReplyTrust {
    fun verified(
        sourceAuthenticated: Boolean,
        sharedKey: ByteArray?,
        wire: String,
        localBinding: () -> ByteArray?,
        signerBinding: () -> ByteArray?,
    ): Boolean {
        if (!sourceAuthenticated) return false
        if (sharedKey != null && MirrorFrameCipher.open(sharedKey, wire) != null) return true
        val local = localBinding()?.takeIf { it.isNotEmpty() } ?: return false
        val signer = signerBinding()?.takeIf { it.isNotEmpty() } ?: return false
        return MessageDigest.isEqual(local, signer)
    }

    fun afterOpen(context: Context, nodeId: String, wire: String, sourceAuthenticated: Boolean): Boolean =
        verified(sourceAuthenticated, MirrorRoomToken.loadSyncKey(context), wire,
            localBinding = { FileExchangeKeyStore.publicBinding(context) },
            signerBinding = {
                if (nodeId.isNotBlank() && MessageSealer.openedByExpectedSender(wire, nodeId))
                    SealedWire.senderBinding(wire) else null
            },
        )
}
