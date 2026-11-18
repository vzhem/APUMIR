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
 *
 * A transport ID minted by `EphemeralControl.transportId` is the one exception
 * to "no row, no release": such a frame never has a chat row by construction, so
 * the ACK is the only proof it arrived. Releasing it is what keeps a delivered
 * service frame from living in the own relay reserve and being re-offered to
 * peers and the broker until it expires. The ID is a name-based UUID no peer can
 * guess, and `release` drops only our own entry with that exact ID.
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
    // Владелец 2026-11-15, отчёт с телефона на 517: «ACK без локальной строки=1004»
    // при живых «MQTT: delivery ACK received for eph1-[id]». Квитанции приходят на
    // транспортные ID служебных кадров (eph1-…), а снятие удержания звалось только
    // тогда, когда находилась и обновлялась своя строка в базе. У служебного кадра
    // строки нет и не будет, поэтому его резервная копия жила до истечения срока и
    // пере-предлагалась соседям и брокеру, хотя получатель кадр уже получил.
    val serviceFrame =
        com.vladimir.messenger.data.security.EphemeralControl.isTransportId(messageId)
    if (senderId == null) {
        val changed = applyOutgoingDeliveryAck(messageId, update, database.messageDao()::getMessageById, release)
        if (serviceFrame) release(messageId)
        return changed
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
    } else if (serviceFrame) {
        // Сверка с получателем для служебного кадра невозможна — строки нет.
        // Подтверждение доставки остаётся единственным доказательством, что он
        // дошёл, и без снятия удержания копия пере-предлагалась бы дальше.
        release(messageId)
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
