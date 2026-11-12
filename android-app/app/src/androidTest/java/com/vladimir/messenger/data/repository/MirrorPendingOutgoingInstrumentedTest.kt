package com.vladimir.messenger.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vladimir.messenger.data.chatclear.ChatClearTombstones
import com.vladimir.messenger.data.chatclear.ChatClearWire
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.data.local.entity.ChatEntity
import com.vladimir.messenger.data.local.entity.GroupEntity
import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.data.mirror.MirrorRow
import com.vladimir.messenger.data.reply.DirectReplyWire
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room/SQLite and JSON; paired transport, network and identity are injected. */
@RunWith(AndroidJUnit4::class)
class MirrorPendingOutgoingInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val shadowName = "mirror_pending_shadow_test.db"
    private val activeName = "mirror_pending_active_test.db"
    private val clearFile = File(context.noBackupFilesDir, "mirror-pending-clear-${UUID.randomUUID()}.sqlite")
    private val peer = "pk_friend123"
    private lateinit var shadow: AppDatabase
    private lateinit var active: AppDatabase
    private lateinit var tombstones: ChatClearTombstones

    private fun open(name: String) = Room.databaseBuilder(context, AppDatabase::class.java, name).build()

    @Before fun setUp() = runBlocking {
        context.deleteDatabase(shadowName)
        context.deleteDatabase(activeName)
        shadow = open(shadowName)
        active = open(activeName)
        tombstones = ChatClearTombstones(context, clearFile)
        shadow.chatDao().insertChat(ChatEntity(id = "shadow-chat", contactId = peer, contactName = "Friend"))
        active.chatDao().insertChat(ChatEntity(id = "active-chat", contactId = peer, contactName = "Friend"))
    }

    @After fun tearDown() {
        if (::shadow.isInitialized) shadow.close()
        if (::active.isInitialized) active.close()
        if (::tombstones.isInitialized) tombstones.close()
        context.deleteDatabase(shadowName)
        context.deleteDatabase(activeName)
        listOf("", "-wal", "-shm", "-journal").forEach { File(clearFile.path + it).delete() }
    }

    private fun row(id: String = "parent") = outgoingTextRow(
        id, "shadow-chat", peer, "answer", 42L,
        DirectReplyWire.Target("quoted", "Alice", "line one\n line two"), "red", "blue",
    )

    private fun loader(
        load: suspend (OutboxPosition?, Int) -> List<MessageEntity> = { after, limit ->
            if (after == null) shadow.messageDao().pendingMirrorOutgoing(limit)
            else shadow.messageDao().pendingMirrorOutgoingAfter(after.timestamp, after.id, limit)
        },
    ): MirrorPendingOutgoing {
        val guard = AutomaticOutboxSender(
            chatContact = { shadow.chatDao().getChatById(it)?.contactId },
            isCleared = { contact, id -> tombstones.contains("owner", contact, id) },
            transport = { _, _, _, _ -> error("network not used") },
            recordFailure = { throw AssertionError("unexpected guard failure", it) },
        )
        return MirrorPendingOutgoing(
            countAtStart = { shadow.messageDao().countPendingMirrorOutgoing() },
            load = load,
            find = { shadow.messageDao().getMessageById(it) },
            chatName = { shadow.chatDao().getChatById(it)?.contactName },
            isPersonalChat = { shadow.groupDao().getGroupById(it) == null },
            isCleared = { contact, id -> tombstones.contains("owner", contact, id) },
            preflight = guard::preflight,
            recordFailure = { throw AssertionError("unexpected loader failure", it) },
        )
    }

    @Test fun queuedQuoteAndPaletteSurviveReopenJsonAndActiveStaging() = runBlocking {
        shadow.messageDao().insertMessage(row().copy(status = "QUEUED_OFFLINE"))
        shadow.close()
        shadow = open(shadowName)
        val saved = shadow.messageDao().getMessageById("parent")!!
        val frame = MirrorRow.fromJson(JSONObject(loader().page(50).rows.single().toJson().toString()))
        assertEquals(saved.replyToId, frame.replyToId)
        assertEquals("Alice", frame.replyAuthor)
        assertEquals("line one line two", frame.replyText)
        assertEquals("red", frame.textColor)
        assertEquals("blue", frame.senderNameColor)
        assertEquals("QUEUED_OFFLINE", frame.status)
        val escaped = frame.copy(content = "Привет \"мир\"\n\\path 😀", replyAuthor = "Юлия / \\ \"",
            replyText = "цитата\nс новой строкой 😀")
        assertEquals(escaped, MirrorRow.fromJson(JSONObject(escaped.toJson().toString())))
        val legacy = JSONObject(escaped.toJson().toString()).apply {
            remove("reply_to"); remove("reply_author"); remove("reply_text")
            remove("text_color"); remove("sender_name_color"); remove("manual_retry")
        }
        val decodedLegacy = MirrorRow.fromJson(legacy)
        assertEquals("", decodedLegacy.replyToId)
        assertEquals("", decodedLegacy.replyAuthor)
        assertEquals("", decodedLegacy.replyText)
        assertEquals("", decodedLegacy.textColor)
        assertEquals("", decodedLegacy.senderNameColor)
        assertFalse(decodedLegacy.manualRetry)

        // Simulate the first paired frame having been lost. The dispatcher and
        // Room store are production code; the transport and initial callback
        // are injected, so this is not an end-to-end network test.
        val store = MirrorOutgoingStore(active) { contact, id -> tombstones.contains("owner", contact, id) }
        var staged = 0
        val dispatcher = MirrorOutgoingDispatcher(
            isCleared = { contact, id -> tombstones.contains("owner", contact, id) },
            localChat = { assertEquals(peer, it); "active-chat" },
            initial = { incoming, localChat ->
                val reply = incoming.replyToId.takeIf { it.isNotBlank() }?.let {
                    DirectReplyWire.Target(it, incoming.replyAuthor, incoming.replyText)
                }
                val activeRow = outgoingTextRow(incoming.id, localChat, incoming.recipientId, incoming.content,
                    incoming.timestamp, reply, incoming.textColor, incoming.senderNameColor)
                assertEquals(MirrorStage.Inserted, store.stageNew(activeRow, peer, "answer"))
                staged++
            },
            manual = { _, _ -> error("unexpected manual retry") },
            recordFailure = { throw AssertionError("unexpected dispatch failure", it) },
        )
        dispatcher.dispatch(frame)
        assertEquals(1, staged)
        active.close()
        active = open(activeName)
        val copied = active.messageDao().getMessageById("parent")!!
        assertEquals("quoted", copied.replyToId)
        assertEquals("Alice", copied.replyAuthor)
        assertEquals("line one line two", copied.replyText)
        assertEquals("red", copied.textColor)
        assertEquals("blue", copied.senderNameColor)

        val guard = AutomaticOutboxSender(
            chatContact = { active.chatDao().getChatById(it)?.contactId },
            isCleared = { contact, id -> tombstones.contains("owner", contact, id) },
            transport = { _, _, _, _ -> error("network not used") },
            recordFailure = { throw AssertionError("unexpected guard failure", it) },
        )
        val packets = mutableListOf<Pair<String, String>>()
        val sidecar = OutgoingReplySidecar(active.messageDao()::getMessageById, guard::preflight,
            { id, _, _, packet -> packets += id to packet; true },
            { throw AssertionError("unexpected sidecar failure", it) })
        assertTrue(sidecar.send("parent", "active-chat", peer,
            DirectReplyWire.Target(frame.replyToId, frame.replyAuthor, frame.replyText)))
        assertEquals(DirectReplyWire.transportMessageId("parent"), packets.single().first)
        assertEquals(DirectReplyWire.Packet("parent", "quoted", "Alice", "line one line two"),
            DirectReplyWire.parse(packets.single().second))
    }

    @Test fun personalQueryExcludesGroupsAndTopicsBeforeTheLimit() = runBlocking {
        shadow.groupDao().insertGroup(GroupEntity(id = "group", title = "Group", ownerId = "pk_owner"))
        repeat(55) { index ->
            shadow.messageDao().insertMessage(row("group-$index").copy(chatId = "group", timestamp = 1L))
        }
        shadow.messageDao().insertMessage(row("legacy-group").copy(channel = "GROUP", timestamp = 2L))
        shadow.messageDao().insertMessage(row("topic").copy(topicId = "thread", timestamp = 3L))
        shadow.messageDao().insertMessage(row("incoming").copy(isFromMe = false, timestamp = 4L))
        shadow.messageDao().insertMessage(row("terminal").copy(status = "DELIVERED", timestamp = 5L))
        repeat(100) { index ->
            shadow.messageDao().insertMessage(row("sent-$index").copy(status = "SENT", timestamp = 0L))
        }
        for (id in listOf("c", "a", "b")) shadow.messageDao().insertMessage(row(id))
        assertEquals(listOf("a", "b"), shadow.messageDao().pendingMirrorOutgoing(2).map { it.id })
        assertEquals(listOf("a", "b"), loader().page(2).rows.map { it.id })
        assertEquals(listOf("a", "b", "c"), loader().page(50).rows.map { it.id })
    }

    @Test fun durableTombstoneSuppressesPendingFrameAcrossReopen() = runBlocking {
        shadow.messageDao().insertMessage(row())
        assertEquals(listOf("parent"), loader().page(50).rows.map { it.id })
        assertTrue(tombstones.remember("owner", peer, listOf("parent")))
        tombstones.close()
        tombstones = ChatClearTombstones(context, clearFile)
        shadow.close()
        shadow = open(shadowName)
        assertTrue(loader().page(50).rows.isEmpty())
        assertTrue(shadow.messageDao().getMessageById("parent") != null)
    }

    @Test fun pendingPagesPassBlockedRowsAndWrapWithRealRoom() = runBlocking {
        val blocked = (0 until 60).map { "blocked-%02d".format(it) }
        blocked.forEach { shadow.messageDao().insertMessage(row(it)) }
        shadow.messageDao().insertMessage(row("later"))
        blocked.chunked(ChatClearWire.MAX_IDS).forEach { assertTrue(tombstones.remember("owner", peer, it)) }
        shadow.close()
        shadow = open(shadowName)
        val pending = loader()
        repeat(3) { assertTrue(pending.page(20).rows.isEmpty()) }
        assertEquals(listOf("later"), pending.page(20).rows.map { it.id })
        // The cursor is only a traversal hint; a new process starts a new pass.
        val afterRestart = loader()
        repeat(3) { assertTrue(afterRestart.page(20).rows.isEmpty()) }
        assertEquals(listOf("later"), afterRestart.page(20).rows.map { it.id })
    }

    @Test fun rowOrGroupChangingAfterSelectionDoesNotProduceAFrame() = runBlocking {
        shadow.messageDao().insertMessage(row())
        val statusRace = loader(load = { _, limit ->
            val selected = shadow.messageDao().pendingMirrorOutgoing(limit)
            assertEquals(1, shadow.messageDao().markOutgoingMessageDelivered("parent"))
            selected
        })
        assertTrue(statusRace.page(50).rows.isEmpty())
        assertEquals("DELIVERED", shadow.messageDao().getMessageById("parent")!!.status)
        shadow.messageDao().insertMessage(row("second"))
        val groupRace = loader(load = { _, limit ->
            val selected = shadow.messageDao().pendingMirrorOutgoing(limit)
            shadow.groupDao().insertGroup(GroupEntity(id = "shadow-chat", title = "Group", ownerId = "pk_owner"))
            selected
        })
        assertTrue(groupRace.page(50).rows.isEmpty())
        assertFalse(shadow.messageDao().pendingMirrorOutgoing(50).any { it.id == "second" })
    }
}
