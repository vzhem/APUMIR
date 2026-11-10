package com.vladimir.messenger.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.data.local.entity.ChatEntity
import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.data.local.entity.GroupEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
