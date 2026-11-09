package com.vladimir.messenger.data.chatclear

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.data.local.entity.ChatEntity
import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.data.local.entity.MessageReactionEntity
import com.vladimir.messenger.data.local.entity.DirectPollEntity
import com.vladimir.messenger.data.local.entity.DirectPollVoteEntity
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatClearLocalCleanupInstrumentedTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
    private val cleanup = ChatClearLocalCleanup(db)
    @After fun close() = db.close()

    @Test fun onlyRequestedChatAndIdsAreRemovedAndPreviewIsRecomputed() = runBlocking {
        db.chatDao().insertChat(ChatEntity("first", "peer-a", "A", unreadCount = 1))
        db.chatDao().insertChat(ChatEntity("other", "peer-b", "B"))
        db.messageDao().insertMessage(MessageEntity("old", "first", "peer-a", "old", 1))
        db.messageDao().insertMessage(MessageEntity("new", "first", "peer-a", "new", 2))
        db.messageDao().insertMessage(MessageEntity("other-id", "other", "peer-b", "other", 3))
        db.messageReactionDao().put(MessageReactionEntity("old", "peer-a", "first", "😀", 1))
        db.directPollDao().insertPoll(DirectPollEntity("poll", "first", "old", "q", "a"))
        db.directPollDao().putVote(DirectPollVoteEntity("poll", "voter"))
        db.fileTransferDao().insertTransferIgnore(FileTransferEntity(
            "transfer", "old", "first", "peer-a", "INCOMING", "file.txt", "text/plain", 1L, 1, 1, "hash", "COMPLETE", createdAtMs = 1, expiresAtMs = 10, updatedAtMs = 1,
        ))
        db.fileTransferDao().insertTransferIgnore(FileTransferEntity(
            "avatar", "old", "first", "peer-a", "INCOMING", "apu-avatar-video-test", "video/mp4", 1L, 1, 1, "hash", "COMPLETE", createdAtMs = 1, expiresAtMs = 10, updatedAtMs = 1,
        ))
        assertTrue(cleanup.apply("first", listOf("old", "other-id", "not-yet")))
        assertTrue(db.messageReactionDao().getForMessage("old").isEmpty())
        assertNull(db.directPollDao().getPoll("poll"))
        assertTrue(db.directPollDao().votesFor("poll").isEmpty())
        assertNull(db.fileTransferDao().getTransfer("transfer"))
        assertNotNull(db.fileTransferDao().getTransfer("avatar"))
        assertNull(db.messageDao().getMessageById("old"))
        assertNotNull(db.messageDao().getMessageById("new"))
        assertNotNull(db.messageDao().getMessageById("other-id"))
        assertEquals("new", db.chatDao().getChatById("first")?.lastMessage)
        assertTrue(cleanup.apply("first", listOf("old")))
        assertFalse(cleanup.apply("missing", listOf("old")))
    }

    @Test fun authenticatedRequestRecordsLateIdsAndCleansOnlyTheMatchingChat() = runBlocking {
        db.chatDao().insertChat(ChatEntity("first", "friend", "Friend"))
        db.messageDao().insertMessage(MessageEntity("old", "first", "friend", "old", 1))
        val file = File(ApplicationProvider.getApplicationContext<Context>().noBackupFilesDir, "clear-apply-${UUID.randomUUID()}.sqlite")
        val tombstones = ChatClearTombstones(ApplicationProvider.getApplicationContext(), file)
        try {
            val apply = ChatClearApply(
                ownerId = { "owner" },
                chatForPeer = { db.chatDao().getChatByContactId(it)?.id },
                tombstone = { owner, peer, ids -> tombstones.remember(owner, peer, ids) },
                cleanup = { chatId, ids -> cleanup.apply(chatId, ids) },
            )
            val batch = ChatClearWire.Batch(UUID.randomUUID(), listOf("old", "late"))
            val replies = mutableListOf<String>()
            val receiver = ChatClearReceiver(apply) { _, ack -> replies.add(ack) }
            receiver.receive("friend", false, ChatClearWire.encode(batch))
            assertNotNull(db.messageDao().getMessageById("old"))
            receiver.receive("friend", true, ChatClearWire.encode(batch))
            assertNull(db.messageDao().getMessageById("old"))
            assertTrue(tombstones.contains("owner", "friend", "late"))
            assertFalse(tombstones.contains("owner", "other", "late"))
            assertEquals(listOf(ChatClearWire.encodeAck(batch.operation)), replies)
        } finally {
            tombstones.close()
            file.delete()
            File(file.path + "-wal").delete()
            File(file.path + "-shm").delete()
        }
    }
}
