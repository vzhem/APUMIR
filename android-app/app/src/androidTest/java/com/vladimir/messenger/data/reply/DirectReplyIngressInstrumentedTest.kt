package com.vladimir.messenger.data.reply

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vladimir.messenger.data.group.GroupReplyIngress
import com.vladimir.messenger.data.group.GroupWire
import com.vladimir.messenger.data.group.roomGroupReplyIngress
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.data.local.entity.ChatEntity
import com.vladimir.messenger.data.local.entity.GroupEntity
import com.vladimir.messenger.data.local.entity.GroupMemberEntity
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
        db.groupDao().insertMember(GroupMemberEntity(groupId = "group", nodeId = other))
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
        assertEquals(1, db.messageDao().applyGroupReply("group", "group-parent", peer, other, "group-target", "Bob", "group quote"))
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
        // A shadow without a quote must not receive the locally conflicting one.
        assertEquals(listOf(DirectReplyWire.transportMessageId("parent")), forwarded)
        assertEquals(DirectReplyResult.APPLIED, send(receiver, "parent"))
        assertEquals(2, acknowledgements.size)
        assertEquals(2, forwarded.size)
    }

    @Test fun groupReplyUpdateRequiresTheMatchingGroupAtUpdateTime() = runBlocking {
        db.groupDao().insertGroup(GroupEntity(id = "group-a", title = "A", ownerId = peer))
        db.groupDao().insertGroup(GroupEntity(id = "group-b", title = "B", ownerId = peer))
        db.groupDao().insertMember(GroupMemberEntity(groupId = "group-a", nodeId = other))
        db.groupDao().insertMember(GroupMemberEntity(groupId = "group-b", nodeId = other))
        db.messageDao().insertMessage(row("personal"))
        db.messageDao().insertMessage(row("group-parent", "group-b", peer).copy(channel = "GROUP"))
        assertEquals(0, db.messageDao().applyGroupReply("group-a", "group-parent", peer, other, "target", "A", "wrong"))
        assertEquals(0, db.messageDao().applyGroupReply("group-a", "personal", peer, other, "target", "A", "wrong"))
        assertEquals(0, db.messageDao().applyGroupReply("group-b", "group-parent", other, other, "target", "X", "wrong sender"))
        assertEquals(1, db.messageDao().applyGroupReply("group-b", "group-parent", peer, other, "target", "B", "right"))
        assertEquals(0, db.messageDao().applyGroupReply("group-b", "group-parent", peer, other, "other-target", "X", "conflict"))
        assertEquals(1, db.messageDao().applyGroupReply("group-b", "group-parent", peer, other, "target", "B", "right"))
        assertNull(db.messageDao().getMessageById("personal")!!.replyToId)
        assertEquals("right", db.messageDao().getMessageById("group-parent")!!.replyText)
    }

    @Test fun groupEarlyRepliesRespectOwnerAndStoredAuthorInRealRoom() = runBlocking {
        val ownerA = "pk_" + "a".repeat(32)
        val ownerB = "pk_" + "b".repeat(32)
        var owner = ownerA
        db.groupDao().insertGroup(GroupEntity(id = "group", title = "Group", ownerId = ownerA))
        db.groupDao().insertMember(GroupMemberEntity(groupId = "group", nodeId = ownerA))
        db.groupDao().insertMember(GroupMemberEntity(groupId = "group", nodeId = ownerB))
        val receiver = roomGroupReplyIngress(db.groupDao(), db.messageDao(), { owner }, 10, 1000L) { 0L }
        val valid = GroupWire.Packet.Reply("group", "parent", "target", "Alice", "correct")
        val conflicting = valid.copy(replyToId = "other-target", replyText = "wrong")
        receiver.receive(peer, valid)
        receiver.receive(other, conflicting)
        db.messageDao().insertMessage(row("parent", "group", peer).copy(channel = "GROUP"))
        owner = ownerB
        receiver.parentInserted("group", "parent")
        assertNull(db.messageDao().getMessageById("parent")!!.replyToId)
        owner = ownerA
        receiver.parentInserted("group", "parent")
        var stored = db.messageDao().getMessageById("parent")!!
        assertEquals("target", stored.replyToId)
        assertEquals("correct", stored.replyText)
        receiver.receive(peer, conflicting)
        receiver.receive(other, conflicting)
        stored = db.messageDao().getMessageById("parent")!!
        assertEquals("target", stored.replyToId)
        assertEquals("correct", stored.replyText)
    }

    @Test fun groupReplyMembershipMustStillExistAtTheRoomUpdate() = runBlocking {
        val owner = "pk_" + "a".repeat(32)
        db.groupDao().insertGroup(GroupEntity(id = "group", title = "Group", ownerId = owner))
        db.groupDao().insertMember(GroupMemberEntity(groupId = "group", nodeId = owner))
        db.messageDao().insertMessage(row("parent", "group", peer).copy(channel = "GROUP"))
        var removeAfterLookup = true
        val updates = mutableListOf<Int>()
        val receiver = GroupReplyIngress(
            localId = { owner },
            isMember = { group, local -> db.groupDao().getMember(group, local)?.isBanned == false },
            parentGroup = { id ->
                val group = db.messageDao().getMessageById(id)?.chatId
                if (removeAfterLookup) db.groupDao().deleteMember("group", owner)
                group
            },
            apply = { local, sender, packet ->
                updates += db.messageDao().applyGroupReply(
                    packet.groupId, packet.messageId, sender, local,
                    packet.replyToId, packet.replyAuthor, packet.replyText,
                )
            },
            capacity = 10, ttlMs = 1000L, clock = { 0L },
        )
        val packet = GroupWire.Packet.Reply("group", "parent", "target", "Alice", "quote")
        receiver.receive(peer, packet)
        assertEquals(listOf(0), updates)
        assertNull(db.messageDao().getMessageById("parent")!!.replyToId)
        db.groupDao().insertMember(GroupMemberEntity(groupId = "group", nodeId = owner))
        removeAfterLookup = false
        receiver.receive(peer, packet)
        assertEquals(listOf(0, 1), updates)
        db.close()
        db = open()
        assertEquals("target", db.messageDao().getMessageById("parent")!!.replyToId)
    }

    @Test fun bannedGroupMembershipBlocksImmediateAndBufferedReplies() = runBlocking {
        val owner = "pk_" + "a".repeat(32)
        db.groupDao().insertGroup(GroupEntity(id = "group", title = "Group", ownerId = owner))
        val member = GroupMemberEntity(groupId = "group", nodeId = owner)
        db.groupDao().insertMember(member)
        val receiver = roomGroupReplyIngress(db.groupDao(), db.messageDao(), { owner }, 10, 1000L) { 0L }
        val valid = GroupWire.Packet.Reply("group", "parent", "target", "Alice", "quote")
        val conflict = valid.copy(replyToId = "other", replyText = "other")
        receiver.receive(peer, valid) // parent not inserted yet
        db.groupDao().insertMember(member.copy(isBanned = true))
        db.messageDao().insertMessage(row("parent", "group", peer).copy(channel = "GROUP"))
        receiver.parentInserted("group", "parent")
        receiver.receive(peer, conflict)
        assertNull(db.messageDao().getMessageById("parent")!!.replyToId)
        assertEquals(0, db.messageDao().applyGroupReply("group", "parent", peer, owner, "target", "Alice", "quote"))
        db.groupDao().deleteMember("group", owner)
        assertEquals(0, db.messageDao().applyGroupReply("group", "parent", peer, owner, "target", "Alice", "quote"))
        db.groupDao().insertMember(member)
        receiver.parentInserted("group", "parent")
        assertEquals("target", db.messageDao().getMessageById("parent")!!.replyToId)
        assertEquals("quote", db.messageDao().getMessageById("parent")!!.replyText)
    }

    @Test fun leftGroupWithStaleMemberCannotApplyOrBufferReplies() = runBlocking {
        val owner = "pk_" + "a".repeat(32)
        db.groupDao().insertGroup(GroupEntity(id = "group", title = "Group", ownerId = owner))
        db.groupDao().insertMember(GroupMemberEntity(groupId = "group", nodeId = owner))
        val receiver = roomGroupReplyIngress(db.groupDao(), db.messageDao(), { owner }, 10, 1000L) { 0L }
        val beforeLeave = GroupWire.Packet.Reply("group", "parent", "target", "Alice", "quote")
        receiver.receive(peer, beforeLeave) // early reply buffered while still a member
        db.groupDao().markLeft("group") // the member row may still exist or be recreated later
        db.messageDao().insertMessage(row("parent", "group", peer).copy(channel = "GROUP"))
        receiver.parentInserted("group", "parent")
        receiver.receive(peer, beforeLeave.copy(replyToId = "conflict"))
        assertNull(db.messageDao().getMessageById("parent")!!.replyToId)
        assertEquals(0, db.messageDao().applyGroupReply("group", "parent", peer, owner, "target", "Alice", "quote"))
        receiver.receive(peer, beforeLeave.copy(messageId = "late", replyToId = "late-target"))
        db.messageDao().insertMessage(row("late", "group", peer).copy(channel = "GROUP"))
        db.groupDao().clearLeft("group")
        receiver.parentInserted("group", "parent")
        receiver.parentInserted("group", "late")
        assertEquals("target", db.messageDao().getMessageById("parent")!!.replyToId)
        assertNull(db.messageDao().getMessageById("late")!!.replyToId) // not buffered after leaving

        // Membership preflight succeeded, but the group was left before the SQL update.
        db.messageDao().insertMessage(row("race", "group", peer).copy(channel = "GROUP"))
        var leaveAfterLookup = true
        val updates = mutableListOf<Int>()
        val racing = GroupReplyIngress(
            localId = { owner },
            isMember = { group, local ->
                db.groupDao().getGroupById(group)?.isLeft == false && db.groupDao().getMember(group, local)?.isBanned == false
            },
            parentGroup = { id ->
                val group = db.messageDao().getMessageById(id)?.chatId
                if (leaveAfterLookup) db.groupDao().markLeft("group")
                group
            },
            apply = { local, sender, packet ->
                updates += db.messageDao().applyGroupReply(
                    packet.groupId, packet.messageId, sender, local,
                    packet.replyToId, packet.replyAuthor, packet.replyText,
                )
            },
            capacity = 10, ttlMs = 1000L, clock = { 0L },
        )
        val racePacket = beforeLeave.copy(messageId = "race")
        racing.receive(peer, racePacket)
        assertEquals(listOf(0), updates)
        assertNull(db.messageDao().getMessageById("race")!!.replyToId)
        db.groupDao().clearLeft("group")
        leaveAfterLookup = false
        racing.receive(peer, racePacket)
        assertEquals(listOf(0, 1), updates)
        db.close()
        db = open()
        assertEquals("target", db.messageDao().getMessageById("race")!!.replyToId)
    }

    @Test fun unauthenticatedAndEarlyPacketsAreNotAcknowledged() = runBlocking {
        val receiver = ingress()
        assertEquals(DirectReplyResult.REJECTED, send(receiver, "parent", authenticated = false))
        assertEquals(DirectReplyResult.UNAPPLIED, send(receiver, "parent"))
        assertTrue(acknowledgements.isEmpty())
        assertTrue(forwarded.isEmpty())
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
