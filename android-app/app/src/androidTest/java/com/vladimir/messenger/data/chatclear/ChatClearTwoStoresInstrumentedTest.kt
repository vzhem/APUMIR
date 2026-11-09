package com.vladimir.messenger.data.chatclear

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.data.local.entity.ChatEntity
import com.vladimir.messenger.data.local.entity.MessageEntity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Two independent Room/SQLite stores, with a simulated lossy transport on one emulator. */
@RunWith(AndroidJUnit4::class)
class ChatClearTwoStoresInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val first = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val second = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val firstFile = File(context.noBackupFilesDir, "clear-two-a-${UUID.randomUUID()}.sqlite")
    private val secondFile = File(context.noBackupFilesDir, "clear-two-b-${UUID.randomUUID()}.sqlite")
    private val outboxFile = File(context.noBackupFilesDir, "clear-two-out-${UUID.randomUUID()}")
    private val firstTombstones = ChatClearTombstones(context, firstFile)
    private val secondTombstones = ChatClearTombstones(context, secondFile)

    @After fun close() {
        first.close(); second.close(); firstTombstones.close(); secondTombstones.close()
        for (file in listOf(firstFile, secondFile, outboxFile)) {
            file.delete(); File(file.path + "-wal").delete(); File(file.path + "-shm").delete(); File(file.path + "-journal").delete()
        }
    }

    private fun apply(db: AppDatabase, store: ChatClearTombstones, owner: String): ChatClearApply {
        val cleanup = ChatClearLocalCleanup(db) { true }
        return ChatClearApply(
            ownerId = { owner },
            chatForPeer = { db.chatDao().getChatByContactId(it)?.id },
            tombstone = { localOwner, peer, ids -> store.remember(localOwner, peer, ids) },
            cleanup = { chat, ids -> cleanup.apply(chat, ids) },
            relatedIds = { chat, ids -> ChatClearFileAliases(db.fileTransferDao()).relatedIds(chat, ids) },
        )
    }

    @Test fun offlineRetryIsIdempotentAndDoesNotDeleteNewOrUnknownMessages() = runBlocking {
        first.chatDao().insertChat(ChatEntity("local-a", "b", "B"))
        second.chatDao().insertChat(ChatEntity("local-b", "a", "A"))
        first.messageDao().insertMessage(MessageEntity("common", "local-a", "b", "old", 1))
        first.messageDao().insertMessage(MessageEntity("late-at-b", "local-a", "a", "in flight", 2, isFromMe = true))
        second.messageDao().insertMessage(MessageEntity("common", "local-b", "b", "old", 1, isFromMe = true))
        second.messageDao().insertMessage(MessageEntity("peer-only", "local-b", "b", "not known at A", 2, isFromMe = true))

        val firstApply = apply(first, firstTombstones, "a")
        val secondApply = apply(second, secondTombstones, "b")
        val snapshot = ChatClearSnapshot(first).capture("local-a")
        val batch = ChatClearPolicy.batches(snapshot.ids).single()
        val queue = ChatClearOutbox(outboxFile)
        queue.enqueue(listOf(ChatClearOutbox.Entry("a", "b", "local-a", batch)))
        assertTrue(firstApply.apply("b", batch))
        first.messageDao().insertMessage(MessageEntity("new-a", "local-a", "a", "new", 3, isFromMe = true))
        second.messageDao().insertMessage(MessageEntity("new-b", "local-b", "b", "new", 3, isFromMe = true))

        val replies = mutableListOf<String>()
        var dropReceipt = true
        val receiver = ChatClearReceiver(secondApply) { _, ack -> if (!dropReceipt) replies.add(ack) }
        val packet = ChatClearWire.encode(batch)
        assertTrue(receiver.receive("a", false, packet))
        assertNotNull(second.messageDao().getMessageById("common"))
        assertTrue(receiver.receive("a", true, packet)) // delivery after offline; receipt is lost
        assertNull(second.messageDao().getMessageById("common"))
        assertTrue(secondTombstones.contains("b", "a", "late-at-b"))
        assertEquals(1, ChatClearOutbox(outboxFile).pending().size)

        dropReceipt = false
        assertTrue(receiver.receive("a", true, packet))
        val acknowledgements = ChatClearAcknowledgements(ChatClearOutbox(outboxFile), { "a" }) {
            firstApply.apply(it.peer, it.batch)
        }
        assertFalse(acknowledgements.receive("attacker", true, batch.operation))
        assertEquals(listOf(ChatClearWire.encodeAck(batch.operation)), replies)
        assertTrue(acknowledgements.receive("b", true, ChatClearWire.decodeAck(replies.single())!!))
        assertTrue(queue.pending().isEmpty())
        assertNotNull(first.messageDao().getMessageById("new-a"))
        assertNotNull(second.messageDao().getMessageById("new-b"))
        // A cannot safely tell whether a peer-only message predates its request.
        assertNotNull(second.messageDao().getMessageById("peer-only"))
    }
}
