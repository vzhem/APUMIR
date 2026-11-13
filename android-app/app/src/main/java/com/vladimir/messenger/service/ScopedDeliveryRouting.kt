package com.vladimir.messenger.service

import android.util.Log
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.data.receipt.ScopedDeliveryAck
import com.vladimir.messenger.data.receipt.applyPeerScopedDeliveryAck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Network and paired ACKs with a claimed sender are scoped before local cleanup.
 * A missing local row may belong to the paired device, so forward that ACK in
 * the legacy mirror format without releasing local custody; updated partners
 * independently match their row. Old partners still process it by ID alone.
 * The old MQTT event has no sender and remains an unauthenticated ID-only path.
 */
internal suspend fun routeOutgoingDeliveryAck(
    messageId: String,
    senderId: String?,
    database: AppDatabase,
    update: suspend (String) -> Boolean,
    noteTransport: (String, String?) -> Unit,
    release: (String) -> Unit,
    forward: (() -> Unit)? = null,
): Boolean {
    try {
        noteTransport(messageId, senderId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // A damaged scheduled-send journal must not block an unrelated Room ACK.
        Log.w("ScopedDeliveryRouting", "scheduled ACK observation failed: ${error.javaClass.simpleName}")
    }
    if (senderId == null) {
        return applyOutgoingDeliveryAck(messageId, update, database.messageDao()::getMessageById, release)
    }
    val result = applyPeerScopedDeliveryAck(database, messageId, senderId, update)
    currentCoroutineContext().ensureActive()
    if (result == ScopedDeliveryAck.UPDATED || result == ScopedDeliveryAck.ALREADY_COMMITTED) {
        try {
            release(messageId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // The committed status lets reconciliation/duplicate ACK retry cleanup.
            Log.w("ScopedDeliveryRouting", "native ACK cleanup scheduling failed: ${error.javaClass.simpleName}")
        }
    }
    if (result != ScopedDeliveryAck.REJECTED) {
        try {
            forward?.invoke()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("ScopedDeliveryRouting", "mirror ACK forwarding failed: ${error.javaClass.simpleName}")
        }
    }
    return result == ScopedDeliveryAck.UPDATED
}
