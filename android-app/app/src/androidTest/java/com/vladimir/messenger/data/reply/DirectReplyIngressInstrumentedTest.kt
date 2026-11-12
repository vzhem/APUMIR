package com.vladimir.messenger.data.reply

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.data.local.entity.ChatEntity
import com.vladimir.messenger.data.local.entity.GroupEntity
import com.vladimir.messenger.data.local.entity.MessageEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room/SQLite; authentication, mirror transport and network are injected. */
@RunWith(AndroidJUnit4::class)
class DirectReplyIngressInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "direct_reply_ingress_test.db"
    private val peer = "pk_friend123"
    private val other = "pk_other456"
    private lateinit var db: AppDatabase
    private val acknowledgements = mutableListOf<Pair<String, String>>()
    private val forwarded = mutableListOf<String>()
    private val failures = mutableListOf<String>()

    @Before fun setUp() = runBlocking {
        context.deleteDatabase(name)
        db = open()
        db.chatDao().insertChat(ChatEntity(id = "chat", contactId = peer, contactName = "Friend"))
        db.chatDao().insertChat(ChatEntity(id = "other-chat", contactId = other, contactName = "Other"))
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
        context.deleteDatabase(name)
    }

    private fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
    private fun row(id: String, chat: String = "chat", sender: String = peer) = MessageEntity(
        id = id, chatId = chat, senderId = sender, content = "body", timestamp = 123L,
        isFromMe = false, status = "DELIVERED", channel = "LOCAL",
    )
    private fun ingress(
        cleared: (String, String) -> Boolean = { _, _ -> false },
        apply: suspend (String, DirectReplyWire.Packet) -> Boolean = { sender, packet ->
            db.messageDao().applyDirectReplyFromPeer(sender, packet.messageId, packet.replyToId, packet.replyAuthor, packet.replyText) > 0
        },
    ) = DirectReplyIngress(cleared, apply, { sender, packet, _ -> apply(sender, packet) },
        { _, _, id, _ -> forwarded += id },
        { id, sender -> acknowledgements += id to sender },
        { stage, _ -> failures += stage },
    )
    private suspend fun send(ingress: DirectReplyIngress, parent: String, sender: String = peer, authenticated: Boolean = true): DirectReplyResult {
        val text = DirectReplyWire.build(parent, "quoted", "Alice", "original text")
        return ingress.network(sender, "remote-chat-id", DirectReplyWire.transportMessageId(parent), text, authenticated, "opened-test-wire")
    }

    @Test fun verifiedReplyCommitsBeforeAckAndSurvivesReopen() = runBlocking {
        db.messageDao().insertMessage(row("parent"))
        val receiver = ingress()
        assertEquals(DirectReplyResult.APPLIED, send(receiver, "parent"))
        assertEquals(DirectReplyResult.APPLIED, send(receiver, "parent"))
        assertEquals(2, acknowledgements.size)
        assertTrue(acknowledgements.all { it == (DirectReplyWire.transportMessageId("parent") to peer) })
        db.close()
        db = open()
        val stored = db.messageDao().getMessageById("parent")!!
        assertEquals("quoted", stored.replyToId)
        assertEquals("Alice", stored.replyAuthor)
        assertEquals("original text", stored.replyText)
    }

    @Test fun wrongSenderChatOutgoingGroupAndTopicNeverChange() = runBlocking {
        db.chatDao().insertChat(ChatEntity(id = "group", contactId = peer, contactName = "Group"))
        db.groupDao().insertGroup(GroupEntity(id = "group", title = "Group", ownerId = peer))
        val rows = listOf(
            row("foreign", "other-chat", other), row("moved", "other-chat", peer),
            row("outgoing").copy(isFromMe = true), row("group-parent", "group", peer),
            row("legacy-group").copy(channel = "GROUP"), row("topic").copy(topicId = "topic-1"),
        )
        rows.forEach { db.messageDao().insertMessage(it) }
        val receiver = ingress()
        rows.forEach { assertEquals(DirectReplyResult.UNAPPLIED, send(receiver, it.id)) }
        assertTrue(acknowledgements.isEmpty())
        rows.forEach { assertNull(db.messageDao().getMessageById(it.id)!!.replyToId) }
        // A matching group reply still works through its own scoped query.
        assertEquals(1, db.messageDao().applyGroupReply("group", "group-parent", "group-target", "Bob", "group quote"))
        assertEquals("group-target", db.messageDao().getMessageById("group-parent")!!.replyToId)
    }

    @Test fun conflictingReplyCannotOverwriteTheFirstQuote() = runBlocking {
        db.messageDao().insertMessage(row("parent"))
        val receiver = ingress()
        assertEquals(DirectReplyResult.APPLIED, send(receiver, "parent"))
        val changed = DirectReplyWire.build("parent", "different-target", "Mallory", "different quote")
        assertEquals(DirectReplyResult.UNAPPLIED, receiver.network(peer, "chat", DirectReplyWire.transportMessageId("parent"), changed, true, "opened-test-wire"))
        val stored = db.messageDao().getMessageById("parent")!!
        assertEquals("quoted", stored.replyToId)
        assertEquals("Alice", stored.replyAuthor)
        assertEquals("original text", stored.replyText)
        assertEquals(1, acknowledgements.size)
        assertEquals(DirectReplyResult.APPLIED, send(receiver, "parent"))
        assertEquals(2, acknowledgements.size)
    }

    @Test fun groupReplyUpdateRequiresTheMatchingGroupAtUpdateTime() = runBlocking {
        db.groupDao().insertGroup(GroupEntity(id = "group-a", title = "A", ownerId = peer))
        db.groupDao().insertGroup(GroupEntity(id = "group-b", title = "B", ownerId = peer))
        db.messageDao().insertMessage(row("personal"))
        db.messageDao().insertMessage(row("group-parent", "group-b", peer).copy(channel = "GROUP"))
        assertEquals(0, db.messageDao().applyGroupReply("group-a", "group-parent", "target", "A", "wrong"))
        assertEquals(0, db.messageDao().applyGroupReply("group-a", "personal", "target", "A", "wrong"))
        assertEquals(1, db.messageDao().applyGroupReply("group-b", "group-parent", "target", "B", "right"))
        assertNull(db.messageDao().getMessageById("personal")!!.replyToId)
        assertEquals("right", db.messageDao().getMessageById("group-parent")!!.replyText)
    }

    @Test fun unauthenticatedAndEarlyPacketsAreNotAcknowledged() = runBlocking {
        val receiver = ingress()
        assertEquals(DirectReplyResult.REJECTED, send(receiver, "parent", authenticated = false))
        assertEquals(DirectReplyResult.UNAPPLIED, send(receiver, "parent"))
        assertTrue(acknowledgements.isEmpty())
        db.messageDao().insertMessage(row("parent"))
        // A retry can apply it, but no durable pending inbox is claimed here.
        assertEquals(DirectReplyResult.APPLIED, send(receiver, "parent"))
        assertEquals(1, acknowledgements.size)
    }

    @Test fun cancellationRollsBackTheRowAndNeverAcknowledges() = runBlocking {
        db.messageDao().insertMessage(row("parent"))
        val updated = CompletableDeferred<Unit>()
        val wait = CompletableDeferred<Unit>()
        val receiver = ingress(apply = { sender, packet ->
            db.withTransaction {
                db.messageDao().applyDirectReplyFromPeer(sender, packet.messageId, packet.replyToId, packet.replyAuthor, packet.replyText)
                updated.complete(Unit)
                wait.await()
                true
            }
        })
        val job = async(Dispatchers.IO) { send(receiver, "parent") }
        updated.await()
        assertTrue(acknowledgements.isEmpty())
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertNull(db.messageDao().getMessageById("parent")!!.replyToId)
        assertTrue(forwarded.isEmpty())
        assertTrue(failures.isEmpty())
    }

    @Test fun sqliteFailureAndClearPreflightCannotAcknowledge() = runBlocking {
        db.messageDao().insertMessage(row("parent"))
        val receiver = ingress()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_direct_reply BEFORE UPDATE ON messages BEGIN SELECT RAISE(ABORT, 'test'); END")
        assertEquals(DirectReplyResult.UNAPPLIED, send(receiver, "parent"))
        assertEquals(listOf("apply"), failures)
        assertTrue(acknowledgements.isEmpty())
        assertNull(db.messageDao().getMessageById("parent")!!.replyToId)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_direct_reply")
        assertEquals(DirectReplyResult.REJECTED, send(ingress(cleared = { _, _ -> true }), "parent"))
        assertTrue(acknowledgements.isEmpty())
        assertEquals(DirectReplyResult.APPLIED, send(receiver, "parent"))
        assertEquals(1, acknowledgements.size)
    }
}
