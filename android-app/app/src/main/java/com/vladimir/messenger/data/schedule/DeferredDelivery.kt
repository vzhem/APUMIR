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
                // Cancellation is locked out atomically BEFORE the first possible transmission.
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
            if (item.isGroup) {
                groups.revealDeferred(owner, letter, owner)
            } else {
                chats.revealScheduledOutgoing(item.targetId, letter.id, letter.text,
                    item.recipients.orEmpty().all { it in item.confirmed })
            }
            for ((peerIndex, peer) in DeferredDeliveryPolicy.batch(item.recipients.orEmpty(), item.nextPeer, item.confirmed + item.handedOff)) {
                val latest = ScheduledSendStore.get(context, id) ?: return@withLock true
                if (!DeferredDeliveryPolicy.needsFallback(peer, latest.confirmed, latest.handedOff)) continue
                ScheduledSendStore.update(context, id) { it.copy(nextPeer = peerIndex + 1) }
                // Only AFTER the deadline: ordinary compatible packets for old/unconfirmed peers.
                val packets = if (item.isGroup) groups.deferredPackets(letter) else listOf(letter.text)
                var allSent = true
                for ((index, packet) in packets.withIndex()) {
                    val transportId = if (item.isGroup) DeferredWire.transportId(letter.id, peer, "normal$index") else letter.id
                    if (!send(transportId, item.targetId, peer, packet)) allSent = false
                }
                if (allSent) ScheduledSendStore.update(context, id) { it.copy(handedOff = (it.handedOff + peer).distinct()) }
            }
            item = ScheduledSendStore.get(context, id) ?: return@withLock true
            val done = item.recipients.orEmpty().all { it in item.confirmed || it in item.handedOff }
            if (done) ScheduledSendStore.remove(context, id)
            done
        }
    }

    /** Ordinary delivery ACKs are useful ONLY for post-deadline fallback, never as stored receipts. */
    fun noteTransportAck(transportId: String) {
        ScheduledSendStore.load(context)
        for (item in ScheduledSendStore.items.value) {
            if (!item.preDeliver || item.ownerId != DeferredInboxStore.owner(context) ||
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
                    letter.topicId.takeIf { it.isNotEmpty() }, quiet = QuietMessage.isQuiet(letter.text))
            }
            DeferredInboxStore.complete(context, key)
            true
        }
    }
}
