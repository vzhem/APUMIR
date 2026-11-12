package com.vladimir.messenger.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.data.local.entity.ChatEntity
import com.vladimir.messenger.data.local.entity.GroupEntity
import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.data.reply.DirectReplyIngress
import com.vladimir.messenger.data.reply.DirectReplyResult
import com.vladimir.messenger.data.reply.DirectReplyWire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room/SQLite, production retry adapters and wire; transport and authentication are fakes. */
@RunWith(AndroidJUnit4::class)
class ReplyAwareOutboxSenderInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val senderName = "reply_retry_sender_test.db"
    private val receiverName = "reply_retry_receiver_test.db"
    private val peer = "pk_receiver123"
    private val senderId = "pk_sender123"
    private lateinit var senderDb: AppDatabase
    private lateinit var receiverDb: AppDatabase

    private fun open(name: String) = Room.databaseBuilder(context, AppDatabase::class.java, name).build()

    @Before fun setUp() = runBlocking {
        context.deleteDatabase(senderName)
        context.deleteDatabase(receiverName)
        senderDb = open(senderName)
        receiverDb = open(receiverName)
        senderDb.chatDao().insertChat(ChatEntity(id = "chat", contactId = peer, contactName = "Receiver"))
        receiverDb.chatDao().insertChat(ChatEntity(id = "incoming", contactId = senderId, contactName = "Sender"))
    }

    @After fun tearDown() {
        if (::senderDb.isInitialized) senderDb.close()
        if (::receiverDb.isInitialized) receiverDb.close()
        context.deleteDatabase(senderName)
        context.deleteDatabase(receiverName)
    }

    private fun row(id: String = "parent", chat: String = "chat") = MessageEntity(
        id = id, chatId = chat, senderId = "self", content = "answer", timestamp = 42L,
        isFromMe = true, status = "QUEUED_OFFLINE", channel = "UNKNOWN", recipientId = peer,
        replyToId = "quoted", replyAuthor = "Alice", replyText = "quoted text",
    )

    private fun outbox(
        body: suspend (String, String, String, String) -> Boolean,
        reply: suspend (String, String, String, String) -> Boolean,
        manual: suspend (String, String, String, String) -> Boolean = body,
        cleared: (String, String) -> Boolean = { _, _ -> false },
    ): ReplyAwareOutboxSender {
        fun guard(transport: suspend (String, String, String, String) -> Boolean) = AutomaticOutboxSender(
            chatContact = { senderDb.chatDao().getChatById(it)?.contactId },
            isCleared = cleared,
            transport = transport,
            recordFailure = { throw AssertionError("unexpected clear lookup failure", it) },
        )
        val automatic = guard(body)
        val sidecar = OutgoingReplySidecar(
            senderDb.messageDao()::getMessageById, automatic::preflight, reply,
        ) { throw AssertionError("unexpected sidecar failure", it) }
        return roomReplyAwareOutboxSender(
            senderDb.messageDao(), senderDb.chatDao(), senderDb.groupDao(), automatic, guard(manual), sidecar,
        )
    }

    @Test fun queuedAndUnconfirmedRetriesSurviveReopenUntilParentAck() = runBlocking {
        senderDb.messageDao().insertMessage(row())
        var online = false
        var bodies = 0
        val replies = mutableListOf<Pair<String, DirectReplyWire.Packet>>()
        val body: suspend (String, String, String, String) -> Boolean = { id, chat, recipient, _ ->
            assertEquals("parent", id); assertEquals("chat", chat); assertEquals(peer, recipient)
            bodies++
            online
        }
        val reply: suspend (String, String, String, String) -> Boolean = { id, chat, recipient, text ->
            assertEquals("chat", chat); assertEquals(peer, recipient)
            replies += id to DirectReplyWire.parse(text)!!
            true
        }
        var current = outbox(body, reply)
        val offline = QueuedOutboxPump(senderDb.messageDao(), senderDb.chatDao(), current::send).pump(20)
        assertEquals(1, offline.attempted)
        assertEquals(0, offline.sent)
        assertEquals("QUEUED_OFFLINE", senderDb.messageDao().getMessageById("parent")!!.status)
        senderDb.close()
        senderDb = open(senderName)
        online = true
        current = outbox(body, reply)
        val queued = QueuedOutboxPump(senderDb.messageDao(), senderDb.chatDao(), current::send).pump(20)
        assertEquals(1, queued.sent)
        assertEquals("SENT", senderDb.messageDao().getMessageById("parent")!!.status)
        val unconfirmed = UnconfirmedOutboxPump(senderDb.messageDao(), senderDb.chatDao(), current::send, { false })
        assertEquals(1, unconfirmed.pump(20).attempted)
        assertEquals(1, senderDb.messageDao().markOutgoingMessageDelivered("parent"))
        senderDb.close()
        senderDb = open(senderName)
        current = outbox(body, reply)
        assertEquals(0, QueuedOutboxPump(senderDb.messageDao(), senderDb.chatDao(), current::send).pump(20).attempted)
        assertEquals(0, UnconfirmedOutboxPump(senderDb.messageDao(), senderDb.chatDao(), current::send, { false }).pump(20).attempted)
        assertEquals("DELIVERED", senderDb.messageDao().getMessageById("parent")!!.status)
        assertEquals(3, bodies)
        assertEquals(3, replies.size)
        assertTrue(replies.all { it.first == DirectReplyWire.transportMessageId("parent") })
        assertTrue(replies.all { it.second == DirectReplyWire.Packet("parent", "quoted", "Alice", "quoted text") })
    }

    @Test fun discoveryRetryOffersSavedReplyAndStopsAfterParentAck() = runBlocking {
        senderDb.messageDao().insertMessage(row().copy(status = "PENDING"))
        var bodies = 0
        val replies = mutableListOf<String>()
        val body: suspend (String, String, String, String) -> Boolean = { id, _, recipient, _ ->
            assertEquals("parent", id); assertEquals(peer, recipient); bodies++; true
        }
        val reply: suspend (String, String, String, String) -> Boolean = { id, _, recipient, text ->
            assertEquals(peer, recipient)
            assertEquals("quoted", DirectReplyWire.parse(text)!!.replyToId)
            replies += id
            true
        }
        var sender = outbox(body, reply)
        val retry = roomPeerOutboxRetry(senderDb.messageDao(), senderDb.chatDao(), sender::send)
        assertEquals(1, retry.retry(peer))
        assertEquals("SENT", senderDb.messageDao().getMessageById("parent")!!.status)
        assertEquals(1, senderDb.messageDao().markOutgoingMessageDelivered("parent"))
        senderDb.close()
        senderDb = open(senderName)
        sender = outbox(body, reply)
        assertEquals(0, roomPeerOutboxRetry(senderDb.messageDao(), senderDb.chatDao(), sender::send).retry(peer))
        assertEquals(1, bodies)
        assertEquals(listOf(DirectReplyWire.transportMessageId("parent")), replies)
    }

    @Test fun earlyReplyIsNotAcknowledgedUntilARealRoomParentExists() = runBlocking {
        senderDb.messageDao().insertMessage(row())
        val results = mutableListOf<DirectReplyResult>()
        val acknowledgements = mutableListOf<String>()
        suspend fun apply(sender: String, packet: DirectReplyWire.Packet): Boolean =
            receiverDb.messageDao().applyDirectReplyFromPeer(
                sender, packet.messageId, packet.replyToId, packet.replyAuthor, packet.replyText,
            ) > 0
        val ingress = DirectReplyIngress(
            isCleared = { _, _ -> false }, apply = ::apply, applyNetwork = { sender, packet, _ -> apply(sender, packet) },
            forwardVerified = { _, _, _, _ -> }, acknowledge = { id, _ -> acknowledgements += id },
            recordFailure = { stage, error -> throw AssertionError("unexpected $stage failure", error) },
        )
        var bodies = 0
        val sender = outbox(
            body = { id, _, _, _ ->
                bodies++
                if (bodies == 1) false else {
                    receiverDb.messageDao().insertMessage(MessageEntity(
                        id = id, chatId = "incoming", senderId = senderId, content = "answer",
                        timestamp = 42L, isFromMe = false, status = "DELIVERED", channel = "LOCAL",
                    ))
                    true
                }
            },
            reply = { id, _, _, text ->
                val result = ingress.network(senderId, "remote-chat", id, text, true, "injected-authenticated-wire")
                results += result
                result == DirectReplyResult.APPLIED
            },
        )
        val pump = QueuedOutboxPump(senderDb.messageDao(), senderDb.chatDao(), sender::send)
        assertEquals(0, pump.pump(20).sent)
        assertEquals(listOf(DirectReplyResult.UNAPPLIED), results)
        assertTrue(acknowledgements.isEmpty())
        assertNull(receiverDb.messageDao().getMessageById("parent"))
        assertEquals(1, pump.pump(20).sent)
        assertEquals(listOf(DirectReplyResult.UNAPPLIED, DirectReplyResult.APPLIED), results)
        assertEquals(listOf(DirectReplyWire.transportMessageId("parent")), acknowledgements)
        val incoming = receiverDb.messageDao().getMessageById("parent")!!
        assertEquals("quoted", incoming.replyToId)
        assertEquals("Alice", incoming.replyAuthor)
        assertEquals("quoted text", incoming.replyText)
    }

    @Test fun deletedRetargetedGroupTopicAndClearedRowsNeverSendReply() = runBlocking {
        val cases = listOf("deleted", "recipient", "contact", "missing-chat", "group", "channel", "topic", "blank-topic", "file", "clear")
        for (id in cases) {
            val chat = "chat-$id"
            senderDb.chatDao().insertChat(ChatEntity(id = chat, contactId = peer, contactName = "Receiver"))
            senderDb.messageDao().insertMessage(row(id, chat))
        }
        val cleared = mutableSetOf<String>()
        val replies = mutableListOf<String>()
        val sender = outbox(
            body = { id, chat, _, _ ->
                val messages = senderDb.messageDao()
                when (id) {
                    "deleted" -> messages.deleteById(id)
                    "recipient" -> messages.insertMessage(messages.getMessageById(id)!!.copy(recipientId = "pk_other123"))
                    "contact" -> senderDb.chatDao().updateChat(senderDb.chatDao().getChatById(chat)!!.copy(contactId = "pk_other123"))
                    "missing-chat" -> senderDb.chatDao().deleteChat(senderDb.chatDao().getChatById(chat)!!)
                    "group" -> senderDb.groupDao().insertGroup(GroupEntity(id = chat, title = "Group", ownerId = peer))
                    "channel" -> messages.insertMessage(messages.getMessageById(id)!!.copy(channel = " group "))
                    "topic" -> messages.insertMessage(messages.getMessageById(id)!!.copy(topicId = "topic"))
                    "blank-topic" -> messages.insertMessage(messages.getMessageById(id)!!.copy(topicId = " "))
                    "file" -> messages.insertMessage(messages.getMessageById(id)!!.copy(status = "LOCAL_FILE"))
                    "clear" -> cleared += id
                }
                true
            },
            reply = { id, _, _, _ -> replies += id; true },
            cleared = { _, id -> id in cleared },
        )
        for (id in cases) assertEquals(true, sender.send(id, "chat-$id", peer, "answer"))
        assertTrue(replies.isEmpty())
    }

    @Test fun parentAckDuringBodyKeepsCurrentReplyButStopsLaterScans() = runBlocking {
        senderDb.messageDao().insertMessage(row())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val replies = mutableListOf<String>()
        val sender = outbox(
            body = { _, _, _, _ -> entered.complete(Unit); release.await(); true },
            reply = { id, _, _, _ -> replies += id; true },
        )
        withTimeout(15_000) {
            val pump = QueuedOutboxPump(senderDb.messageDao(), senderDb.chatDao(), sender::send)
            val sending = async { pump.pump(20) }
            entered.await()
            assertEquals(1, senderDb.messageDao().markOutgoingMessageDelivered("parent"))
            release.complete(Unit)
            assertEquals(1, sending.await().sent)
            assertEquals("DELIVERED", senderDb.messageDao().getMessageById("parent")!!.status)
            assertEquals(0, pump.pump(20).attempted)
            assertEquals(0, UnconfirmedOutboxPump(senderDb.messageDao(), senderDb.chatDao(), sender::send, { false }).pump(20).attempted)
        }
        assertEquals(listOf(DirectReplyWire.transportMessageId("parent")), replies)
    }

    @Test fun failedReplyAndParentAckLeaveNoDurableSidecarRetry() = runBlocking {
        senderDb.messageDao().insertMessage(row())
        var bodies = 0
        var replies = 0
        val body: suspend (String, String, String, String) -> Boolean = { _, _, _, _ -> bodies++; true }
        val reply: suspend (String, String, String, String) -> Boolean = { _, _, _, _ -> replies++; false }
        var sender = outbox(body, reply)
        assertEquals(1, QueuedOutboxPump(senderDb.messageDao(), senderDb.chatDao(), sender::send).pump(20).sent)
        assertEquals(1, senderDb.messageDao().markOutgoingMessageDelivered("parent"))
        senderDb.close()
        senderDb = open(senderName)
        sender = outbox(body, reply)
        assertEquals(0, UnconfirmedOutboxPump(senderDb.messageDao(), senderDb.chatDao(), sender::send, { false }).pump(20).attempted)
        assertEquals(0, QueuedOutboxPump(senderDb.messageDao(), senderDb.chatDao(), sender::send).pump(20).attempted)
        assertEquals(1, bodies)
        assertEquals(1, replies)
    }

    @Test fun manualRetryUsesManualTransportAndParentClearScope() = runBlocking {
        senderDb.messageDao().insertMessage(row())
        var isCleared = false
        var clearDuringBody = false
        var manualBodies = 0
        var replies = 0
        val sender = outbox(
            body = { _, _, _, _ -> throw AssertionError("automatic transport must not be used") },
            manual = { _, _, _, _ -> manualBodies++; if (clearDuringBody) isCleared = true; false },
            reply = { _, _, _, _ -> replies++; true },
            cleared = { _, id -> id == "parent" && isCleared },
        )
        assertEquals(false, sender.sendManual("parent", "chat", peer, "answer"))
        assertEquals(1, replies)
        isCleared = true
        assertNull(sender.sendManual("parent", "chat", peer, "answer"))
        isCleared = false
        clearDuringBody = true
        assertEquals(false, sender.sendManual("parent", "chat", peer, "answer"))
        assertEquals(2, manualBodies)
        assertEquals(1, replies)
    }
}
