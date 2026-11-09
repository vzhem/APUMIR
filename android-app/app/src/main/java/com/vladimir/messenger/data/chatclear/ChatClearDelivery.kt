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
    private val mirrorReplay = ChatClearMirrorReplay<ChatClearTombstones.Cursor>()
    private var nextMirrorSyncAt = 0L
    private val outbox = ChatClearOutbox(File(context.noBackupFilesDir, "chat_clear_outbox.v1"))
    private val fileJournal = ChatClearFileJournal(context)
    private val fileSweep = ChatClearFileSweep(context, db, fileJournal)
    private val apply = ChatClearApplier(context, chatDao, ChatClearLocalCleanup(context, db, fileJournal))
    private val fileMetaGuard = ChatClearFileMetaGuard(
        { peer, id -> ChatClearRegistry.isCleared(appContext, peer, id) },
        { peer, batch -> apply.apply(peer, batch) },
    )
    internal suspend fun suppressMirrorFile(peer: String, groupId: String, name: String, messageId: String, transferId: String): Boolean =
        fileMetaGuard.suppress(peer, groupId, name, messageId, transferId)

    private val acknowledgements = ChatClearAcknowledgements(
        outbox,
        { com.vladimir.messenger.data.schedule.DeferredInboxStore.owner(appContext) },
    ) { entry ->
        val chat = chatDao.getChatById(entry.chat)
        chat?.contactId == entry.peer && chatDao.getChatByContactId(entry.peer)?.id == entry.chat &&
            apply.apply(entry.peer, entry.batch)
    }
    private suspend fun acknowledge(peer: String, authenticated: Boolean, operation: UUID) {
        mutex.lock()
        try { acknowledgements.receive(peer, authenticated, operation) }
        finally { mutex.unlock() }
    }
    private val protocol = ChatClearProtocol(::acknowledge, apply) { peer, ack ->
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
        if (ack != null) acknowledge(peer, true, ack)
        else ChatClearWire.decode(text)?.let { apply.apply(peer, it) }
        return true
    }

    /** Apply an owner's outgoing operation on the other paired device, without a network ACK. */
    internal suspend fun applyMirrorOutgoing(peer: String, text: String): Boolean {
        if (!ChatClearWire.isControl(text)) return false
        val ack = ChatClearWire.decodeMirrorAck(text)
        if (ack != null) {
            mutex.lock()
            try {
                val owner = com.vladimir.messenger.data.schedule.DeferredInboxStore.owner(appContext)
                if (mirrorReplay.acknowledge(owner, peer, ack)) nextMirrorSyncAt = 0L
            } finally { mutex.unlock() }
        } else {
            val batch = ChatClearWire.decode(text)
            if (batch != null && apply.apply(peer, batch)) {
                // Sent only over the paired channel. A lost receipt causes an idempotent retry.
                com.vladimir.messenger.data.mirror.MirrorHub.routeOutgoing()?.publishOutgoingEnvelope(
                    peer, "", "", ChatClearWire.encodeMirrorAck(batch.operation), localOnly = true,
                )
            }
        }
        return true
    }

    /** Snapshot of locally known IDs; it cannot include history visible only to the peer. */
    internal suspend fun enqueueSnapshot(chatId: String): Int = withContext(Dispatchers.IO) {
        mutex.lock()
        try {
            val owner = com.vladimir.messenger.data.schedule.DeferredInboxStore.owner(appContext)
            val chat = chatDao.getChatById(chatId) ?: error("Chat not found")
            val peer = chat.contactId
            require(owner.isNotBlank() && peer.isNotBlank() && owner != peer)
            val limit = ChatClearOutbox.MAX_ENTRIES * ChatClearWire.MAX_IDS + 1
            val ids = (db.messageDao().chatMessageIdsForClear(chatId, limit) +
                db.fileTransferDao().chatTransferMessageIdsForClear(chatId, limit) +
                db.fileTransferDao().chatTransferAliasesForClear(chatId, limit)).distinct()
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
            val now = android.os.SystemClock.elapsedRealtime()
            try { fileSweep.run(owner, now) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { android.util.Log.w("ChatClearDelivery", "Private file cleanup retry failed", error) }
            ChatClearPumpPolicy.mirrorThenDeliver(
                mirror = { syncMirrorTombstones(owner, now) },
                recordFailure = { android.util.Log.w("ChatClearDelivery", "Mirror sync failed; continuing peer delivery", it) },
            ) {
                val pending = outbox.pending()
                val pendingIds = pending.mapTo(HashSet()) { it.batch.operation }
                lastAttempts.keys.retainAll(pendingIds)
                for (entry in pending.asSequence().filter { it.owner == owner && ChatClearPumpPolicy.due(now, lastAttempts[it.batch.operation], RETRY_MS) }.take(8)) {
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
            }
        } finally { mutex.unlock() }
    }

    /** Bounded periodic full replay, including after the paired device reconnects. */
    private suspend fun syncMirrorTombstones(owner: String, now: Long) {
        if (mirrorReplay.selectOwner(owner)) nextMirrorSyncAt = 0L
        if (now < nextMirrorSyncAt || !File(appContext.noBackupFilesDir, "chat_clear.sqlite").exists()) return
        nextMirrorSyncAt = now + MIRROR_INTERVAL_MS
        val channel = com.vladimir.messenger.data.mirror.MirrorHub.routeOutgoing() ?: return
        var pending = mirrorReplay.pending
        if (pending == null) {
            val rows = ChatClearRegistry.store(appContext).page(owner, mirrorReplay.cursor)
            if (rows.isEmpty()) { mirrorReplay.restartCycle(); nextMirrorSyncAt = now + MIRROR_CYCLE_MS; return }
            val peer = rows.first().peer
            val samePeer = rows.takeWhile { it.peer == peer }
            val batch = ChatClearWire.Batch(UUID.randomUUID(), samePeer.map { it.messageId })
            mirrorReplay.stage(peer, batch, samePeer.last())
            pending = checkNotNull(mirrorReplay.pending)
        }
        val chatId = chatDao.getChatByContactId(pending.peer)?.id.orEmpty()
        // Sending is not an application receipt. Keep the same operation until
        // the paired device confirms durable tombstones and local cleanup.
        channel.publishOutgoingEnvelope(pending.peer, "", chatId, ChatClearWire.encode(pending.batch), localOnly = true)
    }

    companion object {
        private const val RETRY_MS = 10L * 60 * 1000
        private const val MIRROR_INTERVAL_MS = 30_000L
        private const val MIRROR_CYCLE_MS = 15L * 60 * 1000
    }
}
