package com.vladimir.messenger.data.chatclear

import android.content.Context
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.data.local.dao.ChatDao
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Authenticated incoming clear commands and peer-scoped application receipts. */
@Singleton
class ChatClearDelivery @Inject constructor(
    @ApplicationContext context: Context,
    private val chatDao: ChatDao,
    private val db: AppDatabase,
) {
    private val appContext = context.applicationContext
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private val lastAttempts = mutableMapOf<UUID, Long>()
    private var mirrorCursor: ChatClearTombstones.Cursor? = null
    private var mirrorOwner = ""
    private var nextMirrorSyncAt = 0L
    private val outbox = ChatClearOutbox(File(context.noBackupFilesDir, "chat_clear_outbox.v1"))
    private val apply = ChatClearApplier(context, chatDao, ChatClearLocalCleanup(db))
    private val protocol = ChatClearProtocol(outbox, { com.vladimir.messenger.data.schedule.DeferredInboxStore.owner(context) }, apply) { peer, ack ->
        val chat = chatDao.getChatByContactId(peer) ?: return@ChatClearProtocol
        val id = UUID.nameUUIDFromBytes("apu-clear-ack:$peer:$ack".toByteArray(Charsets.UTF_8)).toString()
        RustBridge.sendMessage(id, chat.id, peer, ack)
    }

    /** Unauthenticated or malformed control packets are consumed but never applied. */
    suspend fun routeIncoming(peer: String, text: String, authenticated: Boolean): Boolean =
        if (!ChatClearWire.isControl(text)) false else withContext(Dispatchers.IO) {
            protocol.receive(peer, authenticated, text)
        }

    /** Mirror frames are authenticated by the paired-device channel; never ACK a peer from the mirror. */
    internal suspend fun routeMirrorIncoming(peer: String, text: String): Boolean {
        if (!ChatClearWire.isControl(text)) return false
        val ack = ChatClearWire.decodeAck(text)
        if (ack != null) outbox.acknowledge(true, com.vladimir.messenger.data.schedule.DeferredInboxStore.owner(appContext), peer, ack)
        else ChatClearWire.decode(text)?.let { apply.apply(peer, it) }
        return true
    }

    /** Apply an owner's outgoing operation on the other paired device, without a network ACK. */
    internal suspend fun applyMirrorOutgoing(peer: String, text: String): Boolean {
        if (!ChatClearWire.isControl(text)) return false
        ChatClearWire.decode(text)?.let { apply.apply(peer, it) }
        return true
    }

    /**
     * Prepare an exact snapshot. This is not exposed in the UI yet: IDs absent
     * locally and mirror synchronization still require a complete protocol.
     */
    internal suspend fun enqueueSnapshot(chatId: String): Int = withContext(Dispatchers.IO) {
        mutex.lock()
        try {
            val owner = com.vladimir.messenger.data.schedule.DeferredInboxStore.owner(appContext)
            val chat = chatDao.getChatById(chatId) ?: error("Chat not found")
            val peer = chat.contactId
            require(owner.isNotBlank() && peer.isNotBlank() && owner != peer)
            val limit = ChatClearOutbox.MAX_ENTRIES * ChatClearWire.MAX_IDS + 1
            val ids = (db.messageDao().chatMessageIdsForClear(chatId, limit) +
                db.fileTransferDao().chatTransferMessageIdsForClear(chatId, limit)).distinct()
            require(ids.size < limit) { "Chat clear snapshot exceeds queue capacity" }
            val batches = ChatClearPolicy.batches(ids)
            outbox.enqueue(batches.map { ChatClearOutbox.Entry(owner, peer, chatId, it) })
            // Queue is durable before local mutation. If the process dies here,
            // the pump repeats this idempotent work before sending each batch.
            if (chatDao.getChatByContactId(peer)?.id == chatId) {
                for (batch in batches) {
                    try {
                        if (!apply.apply(peer, batch)) break
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        android.util.Log.w("ChatClearDelivery", "Queued local cleanup will be retried", error)
                        break
                    }
                }
            }
            batches.size
        } finally { mutex.unlock() }
    }

    /** Apply locally before sending and retry without claiming delivery until peer ACK. */
    internal suspend fun pump() = withContext(Dispatchers.IO) {
        mutex.lock()
        try {
            val owner = com.vladimir.messenger.data.schedule.DeferredInboxStore.owner(appContext)
            if (owner.isBlank()) return@withContext
            val now = System.currentTimeMillis()
            syncMirrorTombstones(owner, now)
            val pending = outbox.pending()
            val pendingIds = pending.mapTo(HashSet()) { it.batch.operation }
            lastAttempts.keys.retainAll(pendingIds)
            for (entry in pending.asSequence().filter { it.owner == owner && now - (lastAttempts[it.batch.operation] ?: 0L) >= RETRY_MS }.take(8)) {
                lastAttempts[entry.batch.operation] = now
                val chat = chatDao.getChatById(entry.chat) ?: continue
                if (chat.contactId != entry.peer || chatDao.getChatByContactId(entry.peer)?.id != entry.chat) continue
                if (!apply.apply(entry.peer, entry.batch)) continue
                val text = ChatClearWire.encode(entry.batch)
                val wireId = entry.batch.operation.toString()
                val mirrored = com.vladimir.messenger.data.mirror.MirrorHub.deliverAction(entry.peer, "", chat.id, text)
                if (!mirrored) {
                    com.vladimir.messenger.data.mirror.MirrorHub.publishOwnAction(entry.peer, "", chat.id, text)
                    RustBridge.retryMessage(wireId, chat.id, entry.peer, text) || RustBridge.sendMessage(wireId, chat.id, entry.peer, text)
                }
            }
        } finally { mutex.unlock() }
    }

    /** Bounded periodic full replay, including after the paired device reconnects. */
    private suspend fun syncMirrorTombstones(owner: String, now: Long) {
        if (mirrorOwner != owner) { mirrorOwner = owner; mirrorCursor = null; nextMirrorSyncAt = 0L }
        if (now < nextMirrorSyncAt || !File(appContext.noBackupFilesDir, "chat_clear.sqlite").exists()) return
        nextMirrorSyncAt = now + MIRROR_INTERVAL_MS
        val channel = com.vladimir.messenger.data.mirror.MirrorHub.routeOutgoing() ?: return
        val store = ChatClearRegistry.store(appContext)
        val rows = store.page(owner, mirrorCursor)
        if (rows.isEmpty()) { mirrorCursor = null; nextMirrorSyncAt = now + MIRROR_CYCLE_MS; return }
        val peer = rows.first().peer
        val samePeer = rows.takeWhile { it.peer == peer }
        val ids = samePeer.map { it.messageId }
        val batch = ChatClearWire.Batch(UUID.randomUUID(), ids)
        val chatId = chatDao.getChatByContactId(peer)?.id.orEmpty()
        if (channel.publishOutgoingEnvelope(peer, "", chatId, ChatClearWire.encode(batch), localOnly = true)) {
            mirrorCursor = samePeer.last()
        }
    }

    companion object {
        private const val RETRY_MS = 10L * 60 * 1000
        private const val MIRROR_INTERVAL_MS = 30_000L
        private const val MIRROR_CYCLE_MS = 15L * 60 * 1000
    }
}
