package com.vladimir.messenger.data.schedule

import android.content.Context
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.group.GroupRepository
import com.vladimir.messenger.data.mirror.MirrorHub
import com.vladimir.messenger.data.repository.ChatRepository
import com.vladimir.messenger.service.NotificationHelper
import com.vladimir.messenger.util.ChatPreviews
import com.vladimir.messenger.util.NodeIds
import com.vladimir.messenger.util.QuietMessage
import com.vladimir.messenger.worker.DeferredRevealWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Stored receipts, NOT transport ACKs, prove that the receiving application saved a hidden copy. */
@Singleton
class DeferredDelivery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chats: ChatRepository,
    private val groups: GroupRepository,
    private val notifications: NotificationHelper,
    private val cancellations: DeferredCancellationRepository,
) {
    private val immediateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val senderMutex = Mutex()
    private val revealMutex = Mutex()
    private var assistCursor = 0

    suspend fun routeIncoming(
        sender: String, chatId: String, messageId: String, text: String,
        authenticated: Boolean, fromMirror: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!DeferredWire.isPacket(text)) return@withContext false
        // Malformed/unauthenticated reserved packets are consumed, never fall through to chat.
        if (!authenticated || !NodeIds.isNodeId(sender)) return@withContext true
        val cancel = DeferredWire.parseCancel(text)
        val cancelAck = DeferredWire.parseCancelAck(text)
        if (cancel != null) {
            if (!cancel.id.startsWith("deferred_")) return@withContext true
            val key = DeferredInboxStore.entryKey(context, sender, cancel.id)
            val applied = revealMutex.withLock {
                if (!DeferredInboxStore.cancel(context, sender, cancel)) return@withLock false
                // Keep crash recovery independent of the sender's next successful network attempt.
                DeferredRevealWorker.enqueue(context, key, 0L)
                cancellations.deleteVisible(sender, cancel.id)
                DeferredInboxStore.completeCancellation(context, key)
                true
            }
            if (applied) RustBridge.sendMessage(DeferredWire.transportId(cancel.id, sender, "cancelack"),
                chatId, sender, DeferredWire.cancelAck(cancel))
            if (!fromMirror && applied) MirrorHub.publishEnvelope(sender, chatId, messageId, text)
            return@withContext true
        }
        if (cancelAck != null) {
            val item = ScheduledSendStore.get(context, cancelAck.id.removePrefix("deferred_"))
            if (item != null && item.cancelRequested && DeferredDeliveryPolicy.acceptsReceipt(cancelAck,
                    item.id, item.ownerId, DeferredInboxStore.owner(context), item.recipients.orEmpty(), sender, item.wire)) {
                ScheduledSendStore.update(context, item.id) { it.copy(cancelAcked = (it.cancelAcked + sender).distinct(), lastError = "") }
            }
            if (!fromMirror) MirrorHub.publishEnvelope(sender, chatId, messageId, text)
            return@withContext true
        }
        val storedReceipt = DeferredWire.parseReceipt(text)
        val readyReceipt = DeferredWire.parseReady(text)
        val probe = DeferredWire.parseProbe(text)
        val receipt = storedReceipt ?: readyReceipt
        if (receipt != null) {
            val item = ScheduledSendStore.get(context, receipt.id.removePrefix("deferred_"))
            if (item != null && DeferredDeliveryPolicy.acceptsReceipt(receipt, item.id, item.ownerId,
                    DeferredInboxStore.owner(context), item.recipients.orEmpty(), sender, item.wire)) {
                ScheduledSendStore.update(context, item.id) {
                    if (storedReceipt != null) it.copy(confirmed = (it.confirmed + sender).distinct(), lastError = "")
                    else it.copy(ready = (it.ready + sender).distinct())
                }
                if (storedReceipt != null && !item.isGroup) chats.markOutgoingMessageDelivered("deferred_${item.id}")
                if (readyReceipt != null) immediateScope.launch {
                    try { pump(item.id, earlyOnly = false) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (error: Exception) { android.util.Log.w("DeferredDelivery", "Immediate pass deferred to durable work", error) }
                }
            }
        } else if (probe != null) {
            // A tiny unknown-kind probe is safe even for legacy mirror receivers. No body is sent
            // to a peer until it explicitly advertises support; READY does not confirm storage.
            RustBridge.sendMessage(DeferredWire.transportId(probe.id, sender, "ready"), chatId, sender, DeferredWire.ready(probe))
        } else {
            val letter = DeferredWire.parse(text) ?: return@withContext true
            // The stable logical message ID is separate from the transport's deduplication ID.
            if (!letter.id.startsWith("deferred_")) return@withContext true
            if (letter.groupId.isNotBlank() &&
                !groups.canAcceptDeferred(letter.groupId, letter.topicId, sender, letter.text, DeferredInboxStore.owner(context))
            ) return@withContext true
            if (!DeferredInboxStore.put(context, sender, letter)) return@withContext true
            DeferredRevealWorker.enqueue(context, DeferredInboxStore.entryKey(context, sender, letter.id), letter.showAtMs)
            // Persist + enqueue first. Native ACKs/custody receipts do not change confirmed counts.
            val ack = DeferredWire.receipt(letter)
            RustBridge.sendMessage(DeferredWire.transportId(letter.id, sender, "stored"), chatId, sender, ack)
        }
        if (!fromMirror) MirrorHub.publishEnvelope(sender, chatId, messageId, text)
        true
    }

    /** Returns true when this early/due pass is finished. False means durable retry, no discard. */
    suspend fun pump(id: String, earlyOnly: Boolean): Boolean = withContext(Dispatchers.IO) {
        senderMutex.withLock {
            var item = ScheduledSendStore.get(context, id) ?: return@withLock true
            val owner = DeferredInboxStore.owner(context)
            check(owner.startsWith("pk_")) { "Идентичность ещё не готова" }
            check(item.ownerId.isEmpty() || item.ownerId == owner) { "Отложенное сообщение принадлежит другой личности" }
            if (item.cancelRequested) return@withLock pumpCancellation(item)
            if (item.wire.isEmpty()) {
                val content = if (item.quiet) QuietMessage.wrap(item.text) else item.text
                val plan = if (item.isGroup) groups.prepareDeferred(item.targetId, item.topicId, content) else null
                val recipients = plan?.recipients ?: listOf(
                    chats.scheduledRecipient(item.targetId)
                        ?: error("Собеседник не найден"),
                ).filter { it != owner }
                val letter = DeferredWire.Letter("deferred_${item.id}", item.sendAtMs,
                    if (item.isGroup) item.targetId else "", plan?.topicId.orEmpty(), plan?.senderName.orEmpty(), content)
                val wire = DeferredWire.encode(letter)
                // Persist recipients and wire atomically before any possible transmission.
                item = ScheduledSendStore.update(context, id) {
                    it.copy(started = true, ownerId = owner, recipients = recipients, wire = wire, lastError = "")
                } ?: return@withLock true
            }
            val letter = DeferredWire.parse(item.wire) ?: error("Повреждена очередь отложенной отправки")
            if (item.isGroup) check(groups.canAcceptDeferred(letter.groupId, letter.topicId, owner, letter.text, owner)) {
                "Отправка в эту тему больше не разрешена"
            }
            val now = System.currentTimeMillis()
            if (!DeferredWire.due(letter.showAtMs, now)) {
                for ((index, peer) in DeferredDeliveryPolicy.batch(item.recipients.orEmpty(), item.nextPeer, item.confirmed)) {
                    if (!active(id)) return@withLock false
                    ScheduledSendStore.update(context, id) { it.copy(nextPeer = index + 1) }
                    val ready = peer in ScheduledSendStore.get(context, id)?.ready.orEmpty()
                    val kind = if (ready) "early" else "probe"
                    val payload = if (ready) item.wire else DeferredWire.probe(letter)
                    send(DeferredWire.transportId(letter.id, peer, kind), item.targetId, peer, payload)
                }
                return@withLock item.recipients.orEmpty().all { it in item.confirmed }
            }
            // The separate due work guarantees a wakeup even when early retries backed off.
            if (earlyOnly) return@withLock true
            if (!active(id)) return@withLock false
            if (item.isGroup) {
                groups.revealDeferred(owner, letter, owner)
            } else {
                chats.revealScheduledOutgoing(item.targetId, letter.id, letter.text,
                    item.recipients.orEmpty().all { it in item.confirmed })
            }
            for ((peerIndex, peer) in DeferredDeliveryPolicy.batch(item.recipients.orEmpty(), item.nextPeer, item.confirmed + item.handedOff)) {
                val latest = ScheduledSendStore.get(context, id) ?: return@withLock true
                if (latest.cancelRequested) return@withLock false
                if (!DeferredDeliveryPolicy.needsFallback(peer, latest.confirmed, latest.handedOff)) continue
                ScheduledSendStore.update(context, id) { it.copy(nextPeer = peerIndex + 1) }
                // Only AFTER the deadline: ordinary compatible packets for old/unconfirmed peers.
                val packets = if (item.isGroup) groups.deferredPackets(letter) else listOf(letter.text)
                var allSent = true
                for ((index, packet) in packets.withIndex()) {
                    if (!active(id)) return@withLock false
                    val transportId = if (item.isGroup) DeferredWire.transportId(letter.id, peer, "normal$index") else letter.id
                    if (!send(transportId, item.targetId, peer, packet)) allSent = false
                }
                if (allSent) ScheduledSendStore.update(context, id) { it.copy(handedOff = (it.handedOff + peer).distinct()) }
            }
            item = ScheduledSendStore.get(context, id) ?: return@withLock true
            if (item.cancelRequested) return@withLock false
            val done = item.recipients.orEmpty().all { it in item.confirmed || it in item.handedOff }
            if (done) ScheduledSendStore.completeDelivery(context, id) else false
        }
    }

    private fun active(id: String): Boolean = ScheduledSendStore.get(context, id)?.cancelRequested == false

    /** Best effort, but durable: a transport handoff never counts as cancellation confirmation. */
    private suspend fun pumpCancellation(item: ScheduledSendStore.Item): Boolean {
        val letter = DeferredWire.parse(item.wire) ?: error("Повреждён запрос отмены")
        val request = DeferredWire.parseCancel(DeferredWire.cancel(letter)) ?: error("Некорректный запрос отмены")
        check(DeferredInboxStore.cancel(context, item.ownerId, request, localIntent = true)) { "Не удалось сохранить отмену" }
        cancellations.deleteVisible(item.ownerId, letter.id, ownCopy = true)
        DeferredInboxStore.completeCancellation(context, DeferredInboxStore.entryKey(context, item.ownerId, letter.id))
        val wire = DeferredWire.cancel(letter)
        for ((index, peer) in DeferredDeliveryPolicy.batch(item.recipients.orEmpty(), item.nextPeer, item.cancelAcked)) {
            ScheduledSendStore.update(context, item.id) { it.copy(nextPeer = index + 1) }
            // A compatible fallback may already have left the native queue at/after the deadline.
            // Send the old deletion format too; do not claim that its transport ACK proves deletion.
            if (DeferredWire.due(item.sendAtMs, System.currentTimeMillis()) &&
                (!item.isGroup || cancellations.canUseLegacyGroupDelete(letter.groupId))) {
                val legacy = if (item.isGroup) {
                    com.vladimir.messenger.data.group.GroupWire.buildMessageDelete(letter.groupId, letter.id, item.ownerId)
                } else "APUDEL1|${item.targetId}|${letter.id}|${System.currentTimeMillis()}"
                send(DeferredWire.transportId(letter.id, peer, "cancel-legacy"), item.targetId, peer, legacy)
            }
            send(DeferredWire.transportId(letter.id, peer, "cancel"), item.targetId, peer, wire)
        }
        val latest = ScheduledSendStore.get(context, item.id) ?: return true
        val done = latest.recipients.orEmpty().all { it in latest.cancelAcked }
        if (done) ScheduledSendStore.remove(context, item.id)
        return done
    }

    /** Ordinary delivery ACKs are useful ONLY for post-deadline fallback, never as stored receipts. */
    fun noteTransportAck(transportId: String) {
        ScheduledSendStore.load(context)
        for (item in ScheduledSendStore.items.value) {
            if (!item.preDeliver || item.cancelRequested || item.ownerId != DeferredInboxStore.owner(context) ||
                !DeferredWire.due(item.sendAtMs, System.currentTimeMillis())) continue
            val letter = DeferredWire.parse(item.wire) ?: continue
            val count = if (item.isGroup) groups.deferredPackets(letter).size else 1
            for (peer in item.recipients.orEmpty()) {
                val ids = (0 until count).map { index ->
                    if (item.isGroup) DeferredWire.transportId(letter.id, peer, "normal$index") else letter.id
                }
                if (transportId !in ids) continue
                ScheduledSendStore.update(context, item.id) {
                    val acked = (it.ackedPackets + transportId).distinct()
                    val handedOff = if (ids.all { id -> id in acked }) (it.handedOff + peer).distinct() else it.handedOff
                    it.copy(ackedPackets = acked, handedOff = handedOff)
                }
            }
        }
    }

    /** Foreground-service assist: do not wait for a long WorkManager backoff after connectivity returns. */
    suspend fun pumpPending() {
        ScheduledSendStore.load(context)
        val pending = ScheduledSendStore.items.value.filter { it.preDeliver }
        val start = if (pending.isEmpty()) 0 else assistCursor % pending.size
        val batch = (pending.drop(start) + pending.take(start)).take(8)
        assistCursor = start + batch.size
        for (item in batch) {
            try {
                pump(item.id, earlyOnly = false)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ScheduledSendStore.update(context, item.id) { it.copy(lastError = error.message?.take(180).orEmpty()) }
            }
        }
        for ((key, at) in DeferredInboxStore.pending(context)) {
            if (DeferredWire.due(at, System.currentTimeMillis())) {
                try { reveal(key) } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    android.util.Log.w("DeferredDelivery", "Hidden letter retained for retry", error)
                }
            }
        }
    }

    private fun send(id: String, chatId: String, peer: String, text: String): Boolean {
        // Like file sending, a schedule created on the mirror shadow needs the network role.
        // Do not pass plaintext into mirror chat history just to reach the active device.
        if (MirrorHub.routeOutgoing() != null) {
            MirrorHub.claimEngine()
            return false
        }
        return RustBridge.sendMessage(id, chatId, peer, text)
    }

    /** Offline reveal. Never reads the network, and rechecks wall time after every restart. */
    suspend fun reveal(key: String): Boolean = withContext(Dispatchers.IO) {
        revealMutex.withLock {
            val cleanup = DeferredInboxStore.cancellation(context, key)
            if (cleanup != null) {
                cancellations.deleteVisible(cleanup.sender, cleanup.messageId, ownCopy = cleanup.sender == DeferredInboxStore.owner(context))
                DeferredInboxStore.completeCancellation(context, key)
                return@withLock true
            }
            val entry = DeferredInboxStore.get(context, key) ?: return@withLock true
            val letter = entry.letter
            if (!DeferredWire.due(letter.showAtMs, System.currentTimeMillis())) return@withLock false
            val chatId: String
            if (letter.groupId.isNotBlank()) {
                groups.revealDeferred(entry.sender, letter, entry.owner)
                chatId = letter.groupId
            } else {
                val chat = chats.getOrCreateChat(entry.sender, NodeIds.autoName(entry.sender))
                chats.saveIncomingMessage(chat.id, entry.sender, letter.id, letter.text, System.currentTimeMillis())
                chatId = chat.id
            }
            val row = chats.getMessageById(letter.id)
            if (row != null && !row.isFromMe && row.chatId == chatId && row.senderId == entry.sender) {
                notifications.showMessageNotification(chatId, entry.sender,
                    ChatPreviews.human(letter.text)?.take(200).orEmpty(), true,
                    letter.topicId.takeIf { it.isNotEmpty() }, quiet = QuietMessage.isQuiet(letter.text), messageId = letter.id)
            }
            DeferredInboxStore.complete(context, key)
            true
        }
    }
}
