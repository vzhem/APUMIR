package com.vladimir.messenger.data.push

import com.vladimir.messenger.data.file.FileExchangePeerStore
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Opens a single mailbox item using the previously pinned peer and commits it
 * through the experimental Room boundary. No TOFU, contact creation, network,
 * FCM or notification display occurs here. The pin gate is held only across
 * local opening and Room work, never network IO.
 *
 * This is intentionally NOT connected to a live receiver. Before enabling it,
 * the caller must serialize identity, key and database restore against the
 * complete operation, fix single-message deletion/tombstones, arrange reliable
 * notification work without CoreServerService, and revalidate the same trust
 * before a later network ACK. Pin serialization and [current] alone do not
 * prevent identity ABA or protect another process. `COMMITTED` is only a local
 * Room result, not a sender receipt or proof that a user saw a notification.
 */
internal class PushMailboxIncoming(
    private val pins: FileExchangePeerStore,
    private val opener: PushMailboxMessageOpener,
    private val store: PushMailboxRoomStore,
) {
    suspend fun commit(
        wire: String,
        expectedSender: String,
        expectedRecipient: String,
        localBinding: ByteArray,
        localSecret: ByteArray,
        current: () -> Boolean,
    ): PushMailboxDrain.Commit {
        currentCoroutineContext().ensureActive()
        if (!PushMailboxMessageCodec.validPeers(expectedSender, expectedRecipient) ||
            localBinding.size !in 1..512 || localSecret.size != 32
        ) return PushMailboxDrain.Commit.RETRY
        val local = localBinding.copyOf()
        val secret = localSecret.copyOf()
        fun live(): Boolean = current() && MessageDigest.isEqual(localBinding, local) && MessageDigest.isEqual(localSecret, secret)
        return try {
            if (!live()) return PushMailboxDrain.Commit.RETRY
            pins.withPinnedBinding(expectedSender) { pin ->
                try {
                    currentCoroutineContext().ensureActive()
                    if (pin == null || !live()) return@withPinnedBinding PushMailboxDrain.Commit.RETRY
                    val message = opener.open(wire, local, secret, pin, expectedSender, expectedRecipient, ::live)
                    currentCoroutineContext().ensureActive()
                    if (message == null) return@withPinnedBinding PushMailboxDrain.Commit.RETRY
                    val result = store.commit(message, pin, ::live)
                    currentCoroutineContext().ensureActive()
                    result
                } finally { pin?.fill(0) }
            }
        } finally {
            local.fill(0)
            secret.fill(0)
        }
    }
}
