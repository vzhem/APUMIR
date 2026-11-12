package com.vladimir.messenger.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vladimir.messenger.data.chatclear.ChatClearTombstones
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.data.local.entity.ChatEntity
import com.vladimir.messenger.data.local.entity.MessageEntity
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room/SQLite and tombstones; paired channel, identity lifecycle and network are not exercised. */
@RunWith(AndroidJUnit4::class)
class MirrorOutgoingStoreInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "mirror_outgoing_atomic_test.db"
    private val clearFile = File(context.noBackupFilesDir, "mirror-clear-${UUID.randomUUID()}.sqlite")
    private lateinit var db: AppDatabase
    private lateinit var tombstones: ChatClearTombstones

    @Before fun setUp() {
        context.deleteDatabase(databaseName)
        db = open()
        tombstones = ChatClearTombstones(context, clearFile)
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
        if (::tombstones.isInitialized) tombstones.close()
        context.deleteDatabase(databaseName)
        context.deleteDatabase(clearFile.absolutePath)
        listOf("", "-wal", "-shm", "-journal").forEach { File(clearFile.path + it).delete() }
    }

    private fun open() = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
    private suspend fun chat(id: String = "chat", peer: String = "pk_friend") =
        db.chatDao().insertChat(ChatEntity(id = id, contactId = peer, contactName = "Friend"))
    private fun row(id: String = "message", chat: String = "chat", peer: String = "pk_friend") = MessageEntity(
        id = id, chatId = chat, senderId = "self", content = "private text", timestamp = 42L,
        isFromMe = true, status = "PENDING", recipientId = peer,
    )
    private fun store() = MirrorOutgoingStore(db) { peer, id -> tombstones.contains("owner", peer, id) }

    @Test fun replayedPartnerFramesNeverReplaceDeliveredRows() = runBlocking {
        chat()
        assertEquals(MirrorStage.Inserted, store().stageNew(row(), "pk_friend", "first preview"))
        assertEquals(1, db.messageDao().markOutgoingMessageDelivered("message"))
        repeat(3) {
            val result = store().stageNew(row(), "pk_friend", "late preview") as MirrorStage.Existing
            assertEquals("DELIVERED", result.message.status)
        }
        assertEquals("DELIVERED", db.messageDao().getMessageById("message")!!.status)
        assertEquals("private text", db.messageDao().getMessageById("message")!!.content)
        assertEquals("first preview", db.chatDao().getChatById("chat")!!.lastMessage)
    }

    @Test fun concurrentFirstFramesHaveExactlyOneInsertionAcrossReopen() = runBlocking {
        chat()
        val results = coroutineScope {
            (1..16).map { async(Dispatchers.IO) { store().stageNew(row(), "pk_friend", "preview") } }.awaitAll()
        }
        assertEquals(1, results.count { it == MirrorStage.Inserted })
        assertEquals(15, results.count { it is MirrorStage.Existing })
        db.close()
        db = open()
        assertTrue(store().stageNew(row(), "pk_friend", "later") is MirrorStage.Existing)
        assertEquals("preview", db.chatDao().getChatById("chat")!!.lastMessage)
        assertEquals("PENDING", db.messageDao().getMessageById("message")!!.status)
    }

    @Test fun idCollisionsCannotTouchAnotherChatOrIncomingRow() = runBlocking {
        chat()
        chat("other", "pk_other")
        val other = row(id = "collision", chat = "other", peer = "pk_other").copy(content = "other private text", status = "SENT")
        db.messageDao().insertMessageIgnore(other)
        val candidate = row(id = "collision")
        assertEquals(MirrorStage.Rejected, store().stageNew(candidate, "pk_friend", "wrong"))
        assertNull(store().prepareRetry(candidate, "pk_friend"))
        store().applyEcho(candidate.copy(status = "SENT"), "pk_friend", "wrong")
        assertEquals(other, db.messageDao().getMessageById("collision"))
        assertNull(db.chatDao().getChatById("chat")!!.lastMessage)

        // Legacy rows can have no saved recipient. Copying the request's chat
        // onto this row would make retry fall back to the wrong person's contact.
        val legacy = row(id = "legacy", chat = "other", peer = "").copy(content = "legacy private text")
        db.messageDao().insertMessageIgnore(legacy)
        assertNull(store().prepareRetry(row(id = "legacy", peer = "pk_friend").copy(content = legacy.content), "pk_friend"))
        assertEquals(legacy, db.messageDao().getMessageById("legacy"))

        val incoming = row(id = "incoming").copy(senderId = "pk_friend", isFromMe = false, status = "DELIVERED")
        db.messageDao().insertMessageIgnore(incoming)
        assertEquals(MirrorStage.Rejected, store().stageNew(row(id = "incoming"), "pk_friend", "wrong"))
        assertNull(store().prepareRetry(row(id = "incoming"), "pk_friend"))
        store().applyEcho(row(id = "incoming", peer = "pk_friend").copy(status = "SENT"), "pk_friend", "wrong")
        assertEquals(incoming, db.messageDao().getMessageById("incoming"))
    }

    @Test fun peerScopedClearBlocksAllMirrorPathsWithoutTouchingOtherPeers() = runBlocking {
        chat()
        chat("other", "pk_other")
        assertTrue(tombstones.remember("owner", "pk_friend", listOf("cleared")))
        val cleared = row(id = "cleared")
        assertEquals(MirrorStage.Rejected, store().stageNew(cleared, "pk_friend", "private"))
        assertNull(store().prepareRetry(cleared, "pk_friend"))
        store().applyEcho(cleared.copy(status = "SENT"), "pk_friend", "private")
        assertNull(db.messageDao().getMessageById("cleared"))

        val unrelated = row(id = "cleared", chat = "other", peer = "pk_other")
        assertEquals(MirrorStage.Inserted, store().stageNew(unrelated, "pk_other", "other"))
        assertEquals(MirrorStage.Rejected, store().stageNew(cleared, "pk_friend", "private"))
        assertEquals(unrelated, db.messageDao().getMessageById("cleared"))

        val pending = row(id = "pending")
        db.messageDao().insertMessageIgnore(pending)
        assertTrue(tombstones.remember("owner", "pk_friend", listOf("pending")))
        assertNull(store().prepareRetry(pending, "pk_friend"))
        store().applyEcho(pending.copy(status = "SENT"), "pk_friend", "private")
        assertEquals(pending, db.messageDao().getMessageById("pending"))
    }

    @Test fun missingOrChangedChatAndMalformedRecipientFailClosed() = runBlocking {
        val candidate = row()
        assertEquals(MirrorStage.Rejected, store().stageNew(candidate, "pk_friend", "preview"))
        assertNull(store().prepareRetry(candidate, "pk_friend"))
        chat(peer = "pk_changed")
        assertEquals(MirrorStage.Rejected, store().stageNew(candidate, "pk_friend", "preview"))
        assertNull(store().prepareRetry(candidate, "pk_friend"))
        store().applyEcho(candidate.copy(status = "SENT"), "pk_friend", "preview")
        assertNull(db.messageDao().getMessageById("message"))
        assertEquals(MirrorStage.Rejected, store().stageNew(candidate.copy(recipientId = "pk_changed\u0000x"), "pk_changed", "preview"))
        assertNull(db.chatDao().getChatById("chat")!!.lastMessage)
    }

    @Test fun clearDuringInsertionRemovesOnlyTheNewRowAndLeavesNoPreview() = runBlocking {
        chat()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val checks = AtomicInteger()
        val store = MirrorOutgoingStore(db) { peer, id ->
            if (checks.incrementAndGet() == 2) {
                started.countDown()
                check(release.await(15, TimeUnit.SECONDS))
            }
            tombstones.contains("owner", peer, id)
        }
        try {
            val pending = async(Dispatchers.IO) { store.stageNew(row(), "pk_friend", "preview") }
            assertTrue(started.await(15, TimeUnit.SECONDS))
            assertTrue(tombstones.remember("owner", "pk_friend", listOf("message")))
            release.countDown()
            assertEquals(MirrorStage.Rejected, pending.await())
            assertNull(db.messageDao().getMessageById("message"))
            assertNull(db.chatDao().getChatById("chat")!!.lastMessage)
        } finally { release.countDown() }
    }

    @Test fun retryRequiresSameScopeBodyAndCurrentNonterminalStatus() = runBlocking {
        chat()
        chat("other", "pk_other")
        val original = row()
        assertEquals(MirrorStage.Inserted, store().stageNew(original, "pk_friend", "preview"))
        assertEquals(original, store().prepareRetry(original, "pk_friend"))
        assertNull(store().prepareRetry(original.copy(content = "different"), "pk_friend"))
        assertNull(store().prepareRetry(original.copy(recipientId = "pk_other"), "pk_friend"))
        assertNull(store().prepareRetry(original.copy(chatId = "other", recipientId = "pk_other"), "pk_other"))
        assertEquals(1, db.messageDao().markOutgoingMessageDelivered("message"))
        assertNull(store().prepareRetry(original, "pk_friend"))
        assertNull(store().prepareRetry(row(id = "new").copy(status = "READ"), "pk_friend"))
        assertNull(db.messageDao().getMessageById("new"))
        assertEquals("DELIVERED", db.messageDao().getMessageById("message")!!.status)
    }

    @Test fun missingManualRetryIsDurableButClearedIdsAreNotRecreated() = runBlocking {
        chat()
        assertEquals(row(), store().prepareRetry(row(), "pk_friend"))
        assertNull(db.chatDao().getChatById("chat")!!.lastMessage)
        db.close()
        db = open()
        assertEquals(row(), store().prepareRetry(row(), "pk_friend"))
        assertTrue(tombstones.remember("owner", "pk_friend", listOf("deleted")))
        assertNull(store().prepareRetry(row(id = "deleted"), "pk_friend"))
        assertNull(db.messageDao().getMessageById("deleted"))
    }

    @Test fun echoIsScopedAndNeverDowngradesSentOrDeliveryReceipt() = runBlocking {
        chat()
        chat("other", "pk_other")
        val sent = row().copy(status = "SENT")
        store().applyEcho(sent, "pk_friend", "preview")
        assertEquals(sent, db.messageDao().getMessageById("message"))
        store().applyEcho(row().copy(status = "QUEUED_OFFLINE"), "pk_friend", "late")
        assertEquals("SENT", db.messageDao().getMessageById("message")!!.status)
        assertEquals(1, db.messageDao().markOutgoingMessageDelivered("message"))
        store().applyEcho(sent, "pk_friend", "late")
        assertEquals("DELIVERED", db.messageDao().getMessageById("message")!!.status)
        store().applyEcho(row(chat = "other", peer = "pk_other").copy(status = "SENT"), "pk_other", "wrong")
        assertEquals("DELIVERED", db.messageDao().getMessageById("message")!!.status)
        assertNull(db.chatDao().getChatById("other")!!.lastMessage)
        assertEquals("preview", db.chatDao().getChatById("chat")!!.lastMessage)
    }

    @Test fun storageFailureAndCancellationRollbackInsertedRowAndPreview() = runBlocking {
        chat()
        for (error in listOf(IllegalStateException("storage"), CancellationException("cancelled"))) {
            val checks = AtomicInteger()
            val failing = MirrorOutgoingStore(db) { _, _ ->
                if (checks.incrementAndGet() == 2) throw error
                false
            }
            val failure = runCatching { failing.stageNew(row(), "pk_friend", "preview") }.exceptionOrNull()
            if (error is CancellationException) assertTrue(failure is CancellationException)
            else assertTrue(failure is IllegalStateException)
            assertNull(db.messageDao().getMessageById("message"))
            assertNull(db.chatDao().getChatById("chat")!!.lastMessage)
        }
        assertEquals(MirrorStage.Inserted, store().stageNew(row(), "pk_friend", "preview"))
    }

    @Test fun echoNeverDeletesAnIgnoredConflictingRowWhenClearArrives() = runBlocking {
        chat()
        val incoming = row().copy(senderId = "pk_friend", isFromMe = false, status = "DELIVERED")
        db.messageDao().insertMessageIgnore(incoming)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = MirrorOutgoingStore(db) { _, _ ->
            started.countDown()
            check(release.await(15, TimeUnit.SECONDS))
            false // model an old preflight result obtained before the tombstone
        }
        try {
            val echo = async(Dispatchers.IO) { store.applyEcho(row().copy(status = "SENT"), "pk_friend", "wrong") }
            assertTrue(started.await(15, TimeUnit.SECONDS))
            assertTrue(tombstones.remember("owner", "pk_friend", listOf("message")))
            release.countDown()
            echo.await()
            assertEquals(incoming, db.messageDao().getMessageById("message"))
            assertFalse(db.messageDao().getMessageById("message")!!.isFromMe)
        } finally { release.countDown() }
    }
}
