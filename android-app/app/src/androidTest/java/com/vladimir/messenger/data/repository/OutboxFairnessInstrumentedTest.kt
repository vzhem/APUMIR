package com.vladimir.messenger.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vladimir.messenger.data.chatclear.ChatClearTombstones
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.data.local.entity.ChatEntity
import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.data.local.entity.GroupEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OutboxFairnessInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun actualRoomQueueReachesLaterRecipientPastManyBlockedRows() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val chats = db.chatDao()
            val messages = db.messageDao()
            chats.insertChat(ChatEntity(id = "offline", contactId = "pk_offline", contactName = "Offline"))
            chats.insertChat(ChatEntity(id = "online", contactId = "pk_online", contactName = "Online"))
            repeat(515) {
                messages.insertMessageIgnore(MessageEntity(
                    id = "a%04d".format(it), chatId = "offline", senderId = "self", content = "body",
                    timestamp = 42, isFromMe = true,
                    status = if (it % 2 == 0) "PENDING" else "QUEUED_OFFLINE", recipientId = "pk_offline",
                ))
            }
            messages.insertMessageIgnore(MessageEntity(
                id = "z", chatId = "online", senderId = "self", content = "body",
                timestamp = 42, isFromMe = true, status = "QUEUED_OFFLINE", recipientId = "pk_online",
            ))
            val tried = mutableListOf<String>()
            val pump = QueuedOutboxPump(messages, chats) { id, _, peer, _ ->
                tried += id
                if (peer == "pk_online") {
                    // An ACK racing the transport callback must not be downgraded to SENT.
                    messages.markOutgoingMessageDelivered(id)
                    true
                } else false
            }
            var cycles = 0
            do {
                val progress = pump.pump(20)
                cycles++
                assertTrue("scan must finish", cycles < 40)
            } while (progress.moreToScan)
            assertEquals(listOf("a0000", "z"), tried)
            assertEquals("PENDING", messages.getMessageById("a0000")!!.status)
            assertEquals("DELIVERED", messages.getMessageById("z")!!.status)
            assertEquals(515, messages.countPendingOutgoing())
        } finally {
            db.close()
        }
    }

    @Test fun aBadOldestMessageDoesNotHideLaterMessagesInTheSameRoomChat() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val chats = db.chatDao()
            val messages = db.messageDao()
            chats.insertChat(ChatEntity(id = "chat", contactId = "pk_peer", contactName = "Peer"))
            listOf("a", "b", "c").forEach { id ->
                messages.insertMessageIgnore(MessageEntity(
                    id = id, chatId = "chat", senderId = "self", content = "body",
                    timestamp = 42, isFromMe = true, status = "QUEUED_OFFLINE", recipientId = "pk_peer",
                ))
            }
            val tried = mutableListOf<String>()
            val pump = QueuedOutboxPump(messages, chats) { id, _, _, _ ->
                tried += id
                id != "a"
            }
            val first = pump.pump(20)
            assertFalse(first.moreToScan)
            assertEquals(listOf("a"), tried)
            val second = pump.pump(20)
            assertFalse(second.moreToScan)
            assertEquals(listOf("a", "b", "c"), tried)
            assertEquals("QUEUED_OFFLINE", messages.getMessageById("a")!!.status)
            assertEquals("SENT", messages.getMessageById("b")!!.status)
            assertEquals("SENT", messages.getMessageById("c")!!.status)
            assertEquals(1, messages.queuedOfflineCount())
        } finally {
            db.close()
        }
    }

    @Test fun growingRoomQueueEndsScanAndRetriesBlockedChat() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val chats = db.chatDao()
            val messages = db.messageDao()
            chats.insertChat(ChatEntity(id = "offline", contactId = "pk_offline", contactName = "Offline"))
            chats.insertChat(ChatEntity(id = "online", contactId = "pk_online", contactName = "Online"))
            suspend fun add(id: String, timestamp: Long, chat: String) {
                messages.insertMessageIgnore(MessageEntity(
                    id = id, chatId = chat, senderId = "self", content = "body", timestamp = timestamp,
                    isFromMe = true, status = "QUEUED_OFFLINE", recipientId = "pk_$chat",
                ))
            }
            add("a", 1, "offline")
            add("b", 2, "offline")
            add("z", 3, "online")
            val tried = mutableListOf<String>()
            val pump = QueuedOutboxPump(messages, chats) { id, _, peer, _ ->
                tried += id
                peer == "pk_online"
            }
            var cycles = 0
            do {
                val progress = pump.pump(1)
                add("new$cycles", 100L + cycles, "offline")
                cycles++
                assertTrue("a scan must terminate while rows are appended", cycles < 10)
            } while (progress.moreToScan)
            assertEquals(3, cycles)
            assertEquals(listOf("a", "z"), tried)
            repeat(2) { pump.pump(1) }
            assertEquals(listOf("a", "z", "b"), tried)
            assertEquals("QUEUED_OFFLINE", messages.getMessageById("a")!!.status)
            assertEquals("SENT", messages.getMessageById("z")!!.status)
        } finally {
            db.close()
        }
    }

    @Test fun discoveryQueryIsContactScopedAndBoundedAcrossChats() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val chats = db.chatDao()
            val messages = db.messageDao()
            chats.insertChat(ChatEntity(id = "other", contactId = "pk_other", contactName = "Other"))
            chats.insertChat(ChatEntity(id = "peer1", contactId = "pk_peer", contactName = "Peer"))
            chats.insertChat(ChatEntity(id = "peer2", contactId = "pk_peer", contactName = "Peer"))
            repeat(100) { i ->
                messages.insertMessageIgnore(MessageEntity(
                    id = "other%03d".format(i), chatId = "other", senderId = "self", content = "private",
                    timestamp = 1, isFromMe = true, status = "PENDING", recipientId = "pk_other",
                ))
            }
            repeat(25) { i ->
                messages.insertMessageIgnore(MessageEntity(
                    id = "peer%03d".format(i), chatId = if (i % 2 == 0) "peer1" else "peer2",
                    senderId = "self", content = "private", timestamp = 2, isFromMe = true,
                    status = if (i % 2 == 0) "PENDING" else "QUEUED_OFFLINE", recipientId = "pk_peer",
                ))
            }
            messages.insertMessageIgnore(MessageEntity(
                id = "not-retryable", chatId = "peer1", senderId = "self", content = "private",
                timestamp = 0, isFromMe = true, status = "SENT", recipientId = "pk_peer",
            ))
            val selected = messages.getRetryableOutgoingForPeer("pk_peer", 20)
            assertEquals((0 until 20).map { "peer%03d".format(it) }, selected.map { it.id })
            assertTrue(messages.getRetryableOutgoingForPeer("pk_unknown", 20).isEmpty())
        } finally {
            db.close()
        }
    }

    @Test fun initialFailureCannotUndoConcurrentSuccessOrAck() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val messages = db.messageDao()
            listOf("success", "ack", "queued").forEach { id ->
                messages.insertMessageIgnore(MessageEntity(
                    id = id, chatId = "chat", senderId = "self", content = "private",
                    timestamp = 1, isFromMe = true, status = "PENDING", recipientId = "pk_peer",
                ))
            }
            assertEquals(1, messages.updateOutgoingRetryResult("success", "SENT", "LOCAL"))
            assertEquals(1, messages.markOutgoingMessageDelivered("ack"))
            assertEquals(0, messages.queueAfterInitialSendFailure("success"))
            assertEquals(0, messages.queueAfterInitialSendFailure("ack"))
            assertEquals(1, messages.queueAfterInitialSendFailure("queued"))
            assertEquals("SENT", messages.getMessageById("success")!!.status)
            assertEquals("DELIVERED", messages.getMessageById("ack")!!.status)
            assertEquals("QUEUED_OFFLINE", messages.getMessageById("queued")!!.status)
        } finally {
            db.close()
        }
    }

    @Test fun unconfirmedPersonalTextRetriesSameIdAndStopsAfterRoomAck() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val chats = db.chatDao()
            val messages = db.messageDao()
            chats.insertChat(ChatEntity(id = "chat", contactId = "pk_peer", contactName = "Peer"))
            messages.insertMessageIgnore(MessageEntity(
                id = "stable", chatId = "chat", senderId = "self", content = "hello",
                timestamp = 42, isFromMe = true, status = "SENT", recipientId = "pk_peer",
            ))
            val sent = mutableListOf<String>()
            fun pump() = UnconfirmedOutboxPump(messages, chats, { id, _, peer, body ->
                assertEquals("pk_peer", peer)
                assertEquals("hello", body)
                sent += id
                true
            }, { false })
            assertEquals(1, pump().pump(20).attempted)
            // Transport success is only SENT. Recreating the pump simulates a
            // lost ACK/process restart and must keep the same logical ID.
            assertEquals("SENT", messages.getMessageById("stable")!!.status)
            assertEquals(1, pump().pump(20).attempted)
            assertEquals(listOf("stable", "stable"), sent)
            assertEquals(1, messages.markOutgoingMessageDelivered("stable"))
            assertEquals(0, pump().pump(20).attempted)
            assertEquals("DELIVERED", messages.getMessageById("stable")!!.status)
        } finally { db.close() }
    }

    @Test fun unconfirmedPumpSkipsGroupsDeferredMediaAndServicesAndRespectsMirror() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val chats = db.chatDao()
            val messages = db.messageDao()
            chats.insertChat(ChatEntity(id = "personal", contactId = "pk_peer", contactName = "Peer"))
            chats.insertChat(ChatEntity(id = "group", contactId = "pk_peer", contactName = "Group"))
            db.groupDao().insertGroup(GroupEntity(id = "group", title = "Group", ownerId = "pk_owner"))
            suspend fun add(id: String, chat: String = "personal", content: String = "body", status: String = "SENT") {
                messages.insertMessageIgnore(MessageEntity(
                    id = id, chatId = chat, senderId = "self", content = content, timestamp = 42,
                    isFromMe = true, status = status, recipientId = "pk_peer",
                ))
            }
            add("a-service", content = "APUTYP1|1")
            add("deferred_letter")
            add("gif", content = "APUGIFREF1|abc")
            add("group-text", chat = "group")
            add("pending", status = "PENDING")
            add("z-text")
            val sent = mutableListOf<String>()
            val send: suspend (String, String, String, String) -> Boolean = { id, _, _, _ -> sent += id; true }
            assertEquals(0, UnconfirmedOutboxPump(messages, chats, send, { true }).pump(20).attempted)
            val progress = UnconfirmedOutboxPump(messages, chats, send, { false }).pump(20)
            assertEquals(1, progress.attempted)
            assertEquals(listOf("z-text"), sent)
        } finally { db.close() }
    }

    @Test fun roomReconciliationRepairsCommittedAckAfterRestartWithoutTouchingOtherRows() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val messages = db.messageDao()
            suspend fun add(id: String, mine: Boolean, status: String) {
                messages.insertMessageIgnore(MessageEntity(
                    id = id, chatId = "chat", senderId = if (mine) "self" else "pk_peer", content = "body",
                    timestamp = 42, isFromMe = mine, status = status,
                ))
            }
            add("delivered", true, "DELIVERED")
            add("incoming", false, "DELIVERED")
            add("pending", true, "PENDING")
            add("read", true, "READ")
            add("sent", true, "SENT")
            val removed = mutableListOf<String>()
            // These model restored local-native IDs; no native code or network
            // is needed to verify the real Room query and restart repair.
            val restored = listOf("delivered", "incoming", "missing", "pending", "read", "sent")
            val afterRestart = RelayCustodyReconciler(
                { CustodySnapshot(restored, removed::add) }, messages::terminalOutgoingIds,
            )
            assertFalse(afterRestart.reconcile())
            assertEquals(listOf("delivered", "read"), removed)
            assertEquals("SENT", messages.getMessageById("sent")!!.status)
            assertEquals("PENDING", messages.getMessageById("pending")!!.status)
        } finally { db.close() }
    }

    @Test fun ackDuringLateNativeSendReconcilesTheNewlyRetainedCopy() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val allowNative = CountDownLatch(1)
        try {
            val messages = db.messageDao()
            messages.insertMessageIgnore(MessageEntity(
                id = "racing", chatId = "chat", senderId = "self", content = "body", timestamp = 42,
                isFromMe = true, status = "SENT", recipientId = "pk_peer",
            ))
            val entered = CompletableDeferred<Unit>()
            val retained = AtomicBoolean(true)
            val released = mutableListOf<String>()
            val sender = NetworkOutboxSender(
                messages,
                nativeSend = { _, _, _, _, _ ->
                    entered.complete(Unit)
                    check(allowNative.await(5, TimeUnit.SECONDS))
                    // A send admitted before the ACK can finish and retain a
                    // native copy after the first cleanup already ran.
                    retained.set(true)
                    false
                },
                confirm = { id -> retained.set(false); synchronized(released) { released += id } },
            )
            val attempt = async(Dispatchers.Default) { sender.send("racing", "chat", "pk_peer", "body") }
            withTimeout(5_000) { entered.await() }
            assertEquals(1, messages.markOutgoingMessageDelivered("racing"))
            // Model the first asynchronous cleanup winning the race.
            retained.set(false)
            allowNative.countDown()
            assertFalse(withTimeout(5_000) { attempt.await() })
            assertEquals("DELIVERED", messages.getMessageById("racing")!!.status)
            assertFalse(retained.get())
            assertEquals(listOf("racing"), synchronized(released) { released.toList() })
        } finally { allowNative.countDown(); db.close() }
    }

    @Test fun queuedPageRereadsAcknowledgedDeletedAndEditedRows() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val chats = db.chatDao()
            val messages = db.messageDao()
            chats.insertChat(ChatEntity(id = "chat", contactId = "pk_contact", contactName = "Peer"))
            listOf("a-first", "b-ack", "c-delete", "d-sent", "e-incoming", "f-edited").forEachIndexed { n, id ->
                messages.insertMessageIgnore(MessageEntity(
                    id = id, chatId = "chat", senderId = "self", content = "old", timestamp = n.toLong(),
                    isFromMe = true, status = "QUEUED_OFFLINE", recipientId = "pk_original",
                ))
            }
            val sent = mutableListOf<Triple<String, String, String>>()
            val pump = QueuedOutboxPump(messages, chats) { id, _, peer, body ->
                sent += Triple(id, peer, body)
                if (id == "a-first") {
                    messages.markOutgoingMessageDelivered("b-ack")
                    messages.deleteChatMessages("chat", listOf("c-delete"))
                    messages.updateOutgoingRetryResult("d-sent", "SENT", "LOCAL")
                    messages.insertMessage(messages.getMessageById("e-incoming")!!.copy(isFromMe = false))
                    messages.insertMessage(messages.getMessageById("f-edited")!!.copy(content = "new", recipientId = "pk_new"))
                }
                true
            }
            val progress = pump.pump(20)
            assertEquals(listOf(Triple("a-first", "pk_original", "old"), Triple("f-edited", "pk_new", "new")), sent)
            assertEquals(2, progress.attempted)
            assertEquals(2, progress.sent)
            assertFalse(progress.moreToScan)
            assertEquals("DELIVERED", messages.getMessageById("b-ack")!!.status)
            assertEquals("SENT", messages.getMessageById("d-sent")!!.status)
        } finally { db.close() }
    }

    @Test fun queuedRetriesKeepSavedRecipientAndParseLegacyLinksWithoutUnsafeFallback() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val chats = db.chatDao()
            val messages = db.messageDao()
            val cases = listOf(
                Triple("stored", "pk_current", "pk_original"),
                Triple("blank", "pk_current", "\t\u2003"),
                Triple("link", "pk_current", "apu://add?node=pk_original&name=x"),
                Triple("invalid", "pk_current", "not-a-node"),
                Triple("nul", "pk_current", "node=pk_current\u0000suffix"),
                Triple("legacy-chat", "apu://add?node=pk_legacy&name=x", ""),
            )
            cases.forEachIndexed { n, (id, contact, recipient) ->
                chats.insertChat(ChatEntity(id = id, contactId = contact, contactName = "Peer"))
                messages.insertMessageIgnore(MessageEntity(
                    id = id, chatId = id, senderId = "self", content = "body", timestamp = n.toLong(),
                    isFromMe = true, status = "PENDING", recipientId = recipient,
                ))
            }
            messages.insertMessageIgnore(MessageEntity(
                id = "missing-chat", chatId = "missing", senderId = "self", content = "body", timestamp = 6,
                isFromMe = true, status = "PENDING", recipientId = "pk_saved",
            ))
            val sent = mutableMapOf<String, String>()
            val pump = QueuedOutboxPump(messages, chats) { id, _, peer, _ -> sent[id] = peer; true }
            var cycles = 0
            do { val progress = pump.pump(20); cycles++; assertTrue(cycles < 5) } while (progress.moreToScan)
            assertEquals(mapOf("stored" to "pk_original", "blank" to "pk_current", "link" to "pk_original",
                "legacy-chat" to "pk_legacy", "missing-chat" to "pk_saved"), sent)
            assertEquals("PENDING", messages.getMessageById("invalid")!!.status)
            assertEquals("PENDING", messages.getMessageById("nul")!!.status)
        } finally { db.close() }
    }

    @Test fun discoveryRoomQueryAndRetryRespectSavedRecipientsAndLiveRows() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val chats = db.chatDao()
            val messages = db.messageDao()
            chats.insertChat(ChatEntity(id = "chat", contactId = "pk_peer", contactName = "Peer"))
            suspend fun add(id: String, timestamp: Long, recipient: String, status: String = "PENDING") {
                messages.insertMessageIgnore(MessageEntity(
                    id = id, chatId = "chat", senderId = "self", content = "old", timestamp = timestamp,
                    isFromMe = true, status = status, recipientId = recipient,
                ))
            }
            repeat(25) { add("wrong$it", 0, "pk_other") }
            add("prefix", 0, "apu://add?node=pk_peerExtra&name=x")
            add("second-node", 0, "node=pk_other&node=pk_peer")
            add("nul", 0, "node=pk_peer\u0000suffix")
            add("first", 1, "pk_peer")
            add("ack", 2, "pk_peer")
            add("delete", 3, "pk_peer")
            add("blank", 4, "\t\u2003")
            add("link", 5, "apu://add?node=pk_peer&name=x")
            add("edited", 6, "pk_peer")
            repeat(55) { add("sent-wrong$it", 100L + it, "pk_other", "SENT") }
            assertEquals(listOf("first", "ack", "delete", "blank", "link", "edited"),
                messages.getRetryableOutgoingForPeer("pk_peer", 20).map { it.id })
            assertEquals(listOf("edited", "link", "blank", "delete", "ack", "first"),
                messages.getUnconfirmedOutgoingMessages("chat", "pk_peer", 50).map { it.id })
            val sent = mutableListOf<Pair<String, String>>()
            val retry = roomPeerOutboxRetry(messages, chats, { id, _, peer, body ->
                assertEquals("pk_peer", peer)
                sent += id to body
                if (id == "first") {
                    messages.markOutgoingMessageDelivered("ack")
                    messages.deleteChatMessages("chat", listOf("delete"))
                    messages.insertMessage(messages.getMessageById("edited")!!.copy(content = "new"))
                }
                true
            }, { error("unexpected error") })
            assertEquals(4, retry.retry("pk_peer"))
            assertEquals(listOf("first", "blank", "link", "edited"), sent.map { it.first })
            assertEquals("new", sent.last().second)
            assertEquals("DELIVERED", messages.getMessageById("ack")!!.status)
        } finally { db.close() }
    }

    @Test fun unconfirmedRoomRetriesLegacyRecipientInsteadOfChangedChat() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val chats = db.chatDao()
            val messages = db.messageDao()
            chats.insertChat(ChatEntity(id = "chat", contactId = "pk_changed", contactName = "Peer"))
            listOf("pk_original", "apu://add?node=pk_legacy&name=x", "\t\u2003", "not-a-node", "node=pk_original\u0000suffix").forEachIndexed { n, recipient ->
                messages.insertMessageIgnore(MessageEntity(
                    id = "m$n", chatId = "chat", senderId = "self", content = "hello", timestamp = n.toLong(),
                    isFromMe = true, status = "SENT", recipientId = recipient,
                ))
            }
            assertEquals(3, messages.countUnconfirmedPersonalSent())
            val sent = mutableMapOf<String, String>()
            val pump = UnconfirmedOutboxPump(messages, chats, { id, _, peer, _ -> sent[id] = peer; true }, { false })
            var cycles = 0
            do { val progress = pump.pump(20); cycles++; assertTrue(cycles < 5) } while (progress.moreToScan)
            assertEquals(mapOf("m0" to "pk_original", "m1" to "pk_legacy", "m2" to "pk_changed"), sent)
        } finally { db.close() }
    }

    @Test fun durableClearTombstonesSuppressAllAutomaticRetriesWithoutBlockingOtherRows() = runBlocking {
        val file = File(context.noBackupFilesDir, "outbox-clear-${UUID.randomUUID()}.sqlite")
        val tombstones = ChatClearTombstones(context, file)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val chats = db.chatDao()
            val messages = db.messageDao()
            chats.insertChat(ChatEntity(id = "chat", contactId = "pk_peer", contactName = "Peer"))
            chats.insertChat(ChatEntity(id = "moved", contactId = "pk_current", contactName = "Current"))
            suspend fun add(id: String, time: Long, status: String, chat: String = "chat", peer: String = "pk_peer") {
                messages.insertMessageIgnore(MessageEntity(
                    id = id, chatId = chat, senderId = "self", content = "body", timestamp = time,
                    isFromMe = true, status = status, recipientId = peer,
                ))
            }
            add("blocked-pending", 1, "PENDING")
            add("allowed-pending", 2, "PENDING")
            add("blocked-sent", 3, "SENT")
            add("allowed-sent", 4, "SENT")
            add("moved", 5, "PENDING", "moved", "pk_original")
            add("orphan", 6, "PENDING", "missing-chat", "pk_original")
            assertTrue(tombstones.remember("owner", "pk_peer", listOf("blocked-pending", "blocked-sent")))
            // Clear is scoped to the chat contact even when a moved row kept an older recipient.
            assertTrue(tombstones.remember("owner", "pk_current", listOf("moved")))
            assertTrue(tombstones.remember("other-owner", "pk_peer", listOf("allowed-pending")))
            assertTrue(tombstones.remember("owner", "pk_other", listOf("allowed-sent")))
            val sent = mutableListOf<String>()
            val guard = AutomaticOutboxSender(
                chatContact = { chats.getChatById(it)?.contactId },
                isCleared = { peer, id -> tombstones.contains("owner", peer, id) },
                transport = { id, _, _, _ -> sent += id; true },
                recordFailure = { error("unexpected preflight failure") },
            )
            val progress = QueuedOutboxPump(messages, chats, guard::send).pump(20)
            assertEquals(1, progress.attempted)
            assertEquals(1, progress.sent)
            assertFalse(progress.moreToScan)
            assertEquals(listOf("allowed-pending"), sent)
            assertEquals("PENDING", messages.getMessageById("blocked-pending")!!.status)
            assertEquals("PENDING", messages.getMessageById("moved")!!.status)
            assertEquals("PENDING", messages.getMessageById("orphan")!!.status)
            sent.clear()
            val retry = roomPeerOutboxRetry(messages, chats, guard::send) { error("unexpected error") }
            assertEquals(2, retry.retry("pk_peer"))
            assertEquals(setOf("allowed-pending", "allowed-sent"), sent.toSet())
            sent.clear()
            val unconfirmed = UnconfirmedOutboxPump(messages, chats, guard::send, { false })
            var cycles = 0
            do { val step = unconfirmed.pump(20); cycles++; assertTrue(cycles < 5) } while (step.moreToScan)
            assertEquals(setOf("allowed-pending", "allowed-sent"), sent.toSet())
            assertEquals("SENT", messages.getMessageById("blocked-sent")!!.status)
        } finally {
            db.close()
            tombstones.close()
            context.deleteDatabase(file.absolutePath)
            listOf("-wal", "-shm", "-journal").forEach { File(file.absolutePath + it).delete() }
        }
    }

    @Test fun discoveryRotatesPastClearedRoomWindowsWithoutSendingThem() = runBlocking {
        val file = File(context.noBackupFilesDir, "discovery-clear-${UUID.randomUUID()}.sqlite")
        val tombstones = ChatClearTombstones(context, file)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val chats = db.chatDao()
            val messages = db.messageDao()
            chats.insertChat(ChatEntity(id = "chat", contactId = "pk_peer", contactName = "Peer"))
            val ids = (0 until 120).map { "m%03d".format(it) }
            ids.forEach { id ->
                messages.insertMessageIgnore(MessageEntity(
                    id = id, chatId = "chat", senderId = "self", content = "body", timestamp = 42,
                    isFromMe = true, status = "PENDING", recipientId = "pk_peer",
                ))
            }
            ids.filter { it != "m050" }.chunked(com.vladimir.messenger.data.chatclear.ChatClearWire.MAX_IDS)
                .forEach { assertTrue(tombstones.remember("owner", "pk_peer", it)) }
            val sent = mutableListOf<String>()
            val guard = AutomaticOutboxSender(
                chatContact = { chats.getChatById(it)?.contactId },
                isCleared = { peer, id -> tombstones.contains("owner", peer, id) },
                transport = { id, _, _, _ -> sent += id; true },
                recordFailure = { error("unexpected preflight failure") },
            )
            val retry = roomPeerOutboxRetry(messages, chats, guard::send) { error("unexpected retry error") }
            // The allowed row is neither in the oldest pending page nor in the
            // newest recent page. Earlier cleared pages remain in Room.
            assertEquals(0, retry.retry("pk_peer"))
            assertEquals(0, retry.retry("pk_peer"))
            assertEquals(1, retry.retry("pk_peer"))
            assertEquals(listOf("m050"), sent)
            assertEquals("SENT", messages.getMessageById("m050")!!.status)
            assertEquals("PENDING", messages.getMessageById("m000")!!.status)
            assertEquals("PENDING", messages.getMessageById("m119")!!.status)
        } finally {
            db.close()
            tombstones.close()
            context.deleteDatabase(file.absolutePath)
            listOf("-wal", "-shm", "-journal").forEach { File(file.absolutePath + it).delete() }
        }
    }

    @Test fun failedClearPreflightSkipsOnlyThatRowAndNeverCountsAsNetwork() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val chats = db.chatDao()
            val messages = db.messageDao()
            chats.insertChat(ChatEntity(id = "chat", contactId = "pk_peer", contactName = "Peer"))
            suspend fun add(id: String, time: Long, status: String) {
                messages.insertMessageIgnore(MessageEntity(
                    id = id, chatId = "chat", senderId = "self", content = "body", timestamp = time,
                    isFromMe = true, status = status, recipientId = "pk_peer",
                ))
            }
            add("error-pending", 1, "PENDING")
            add("allowed-pending", 2, "PENDING")
            add("error-sent", 3, "SENT")
            add("allowed-sent", 4, "SENT")
            val sent = mutableListOf<String>()
            var errors = 0
            val guard = AutomaticOutboxSender(
                chatContact = { chats.getChatById(it)?.contactId },
                isCleared = { _, id -> if (id.startsWith("error-")) error("storage unavailable") else false },
                transport = { id, _, _, _ -> sent += id; true },
                recordFailure = { errors++ },
            )
            val progress = QueuedOutboxPump(messages, chats, guard::send).pump(20)
            assertEquals(1, progress.attempted)
            assertEquals(1, progress.sent)
            assertFalse(progress.moreToScan)
            assertEquals(listOf("allowed-pending"), sent)
            assertEquals(1, errors)
            sent.clear()
            val retry = roomPeerOutboxRetry(messages, chats, guard::send) { error("unexpected retry error") }
            assertEquals(2, retry.retry("pk_peer"))
            assertEquals(setOf("allowed-pending", "allowed-sent"), sent.toSet())
            assertEquals(3, errors)
            sent.clear()
            val unconfirmed = UnconfirmedOutboxPump(messages, chats, guard::send, { false })
            var attempts = 0
            var cycles = 0
            do {
                val step = unconfirmed.pump(20)
                attempts += step.attempted
                cycles++
                assertTrue(cycles < 5)
            } while (step.moreToScan)
            assertEquals(2, attempts)
            assertEquals(setOf("allowed-pending", "allowed-sent"), sent.toSet())
            assertEquals(4, errors)
            assertEquals("PENDING", messages.getMessageById("error-pending")!!.status)
            assertEquals("SENT", messages.getMessageById("error-sent")!!.status)
        } finally { db.close() }
    }

    @Test fun cancelledClearPreflightPropagatesAndEachRetryCanResume() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val chats = db.chatDao()
            val messages = db.messageDao()
            chats.insertChat(ChatEntity(id = "chat", contactId = "pk_peer", contactName = "Peer"))
            messages.insertMessageIgnore(MessageEntity(
                id = "message", chatId = "chat", senderId = "self", content = "body", timestamp = 1,
                isFromMe = true, status = "PENDING", recipientId = "pk_peer",
            ))
            var cancel = true
            var sent = 0
            val guard = AutomaticOutboxSender(
                chatContact = { if (cancel) throw CancellationException("cancel") else chats.getChatById(it)?.contactId },
                isCleared = { _, _ -> false }, transport = { _, _, _, _ -> sent++; true },
                recordFailure = { error("cancellation must not be reported as a failure") },
            )
            val queued = QueuedOutboxPump(messages, chats, guard::send)
            assertTrue(runCatching { queued.pump(20) }.exceptionOrNull() is CancellationException)
            assertEquals(0, sent)
            assertEquals("PENDING", messages.getMessageById("message")!!.status)
            cancel = false
            assertEquals(1, queued.pump(20).sent)
            val retry = roomPeerOutboxRetry(messages, chats, guard::send) { error("unexpected retry error") }
            cancel = true
            assertTrue(runCatching { retry.retry("pk_peer") }.exceptionOrNull() is CancellationException)
            assertEquals(1, sent)
            cancel = false
            assertEquals(1, retry.retry("pk_peer"))
            val unconfirmed = UnconfirmedOutboxPump(messages, chats, guard::send, { false })
            cancel = true
            assertTrue(runCatching { unconfirmed.pump(20) }.exceptionOrNull() is CancellationException)
            assertEquals(2, sent)
            cancel = false
            assertEquals(1, unconfirmed.pump(20).sent)
            assertEquals(3, sent)
        } finally { db.close() }
    }

    @Test fun roomKeysetPaginationKeepsAllEqualTimestampIds() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val messages = db.messageDao()
            listOf("a", "c", "b", "e", "d").forEach { id ->
                messages.insertMessageIgnore(MessageEntity(
                    id = id, chatId = "chat", senderId = "self", content = id,
                    timestamp = 123, isFromMe = true, status = "QUEUED_OFFLINE",
                ))
            }
            val first = messages.getRetryableOutgoingMessages(2)
            assertEquals(listOf("a", "b"), first.map { it.id })
            val second = messages.getRetryableOutgoingMessagesAfter(first.last().timestamp, first.last().id, 2)
            assertEquals(listOf("c", "d"), second.map { it.id })
            val third = messages.getRetryableOutgoingMessagesAfter(second.last().timestamp, second.last().id, 2)
            assertEquals(listOf("e"), third.map { it.id })
            assertFalse(third.isEmpty())
        } finally {
            db.close()
        }
    }
}
