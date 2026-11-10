package com.vladimir.messenger.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.data.local.entity.ChatEntity
import com.vladimir.messenger.data.local.entity.MessageEntity
import kotlinx.coroutines.runBlocking
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
                    timestamp = 42, isFromMe = true, status = "QUEUED_OFFLINE", recipientId = "pk_offline",
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
            assertEquals("QUEUED_OFFLINE", messages.getMessageById("a0000")!!.status)
            assertEquals("DELIVERED", messages.getMessageById("z")!!.status)
            assertEquals(515, messages.queuedOfflineCount())
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
            val first = messages.getQueuedOfflineMessages(2)
            assertEquals(listOf("a", "b"), first.map { it.id })
            val second = messages.getQueuedOfflineMessagesAfter(first.last().timestamp, first.last().id, 2)
            assertEquals(listOf("c", "d"), second.map { it.id })
            val third = messages.getQueuedOfflineMessagesAfter(second.last().timestamp, second.last().id, 2)
            assertEquals(listOf("e"), third.map { it.id })
            assertFalse(third.isEmpty())
        } finally {
            db.close()
        }
    }
}
