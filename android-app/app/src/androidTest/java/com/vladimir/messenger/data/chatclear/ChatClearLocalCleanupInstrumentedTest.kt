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
    private val cleanup = ChatClearLocalCleanup(db) { true }
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

    @Test fun privateFileFailureKeepsDatabaseRowsForRetry() = runBlocking {
        db.chatDao().insertChat(ChatEntity("chat", "friend", "Friend"))
        db.messageDao().insertMessage(MessageEntity("id", "chat", "friend", "file", 1))
        db.fileTransferDao().insertTransferIgnore(FileTransferEntity(
            "transfer", "id", "chat", "friend", "INCOMING", "file.txt", "text/plain", 1L, 1, 1,
            "hash", "COMPLETE", createdAtMs = 1, expiresAtMs = 10, updatedAtMs = 1,
        ))
        var filesAvailable = false
        val guarded = ChatClearLocalCleanup(db) { filesAvailable }
        assertFalse(guarded.apply("chat", listOf("id")))
        assertNotNull(db.messageDao().getMessageById("id"))
        assertNotNull(db.fileTransferDao().getTransfer("transfer"))
        filesAvailable = true
        assertTrue(guarded.apply("chat", listOf("id")))
        assertNull(db.messageDao().getMessageById("id"))
        assertNull(db.fileTransferDao().getTransfer("transfer"))
    }

    @Test fun concurrentTransferChangeForcesRetryBeforeRemovingRows() = runBlocking {
        db.chatDao().insertChat(ChatEntity("chat", "friend", "Friend"))
        db.messageDao().insertMessage(MessageEntity("id", "chat", "friend", "file", 1))
        db.fileTransferDao().insertTransferIgnore(FileTransferEntity(
            "transfer", "id", "chat", "friend", "INCOMING", "file.txt", "text/plain", 1L, 1, 1,
            "hash", "TRANSFERRING", createdAtMs = 1, expiresAtMs = 10, updatedAtMs = 1,
        ))
        val guarded = ChatClearLocalCleanup(db) {
            db.openHelper.writableDatabase.execSQL("UPDATE file_transfers SET updatedAtMs=2 WHERE transferId='transfer'")
            true
        }
        assertFalse(guarded.apply("chat", listOf("id")))
        assertNotNull(db.messageDao().getMessageById("id"))
        assertNotNull(db.fileTransferDao().getTransfer("transfer"))
    }

    @Test fun privateCopiesAreRemovedButIndependentFilesAreKept() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val transferId = UUID.randomUUID().toString().replace("-", "")
        val chunks = File(context.noBackupFilesDir, "file_transfers/v1/$transferId")
        val received = File(context.noBackupFilesDir, "file_received/v1/$transferId")
        val preview = File(context.noBackupFilesDir, "file_preview/v1/$transferId.jpg")
        val independent = File(context.noBackupFilesDir, "independent-$transferId")
        chunks.mkdirs(); received.mkdirs(); preview.parentFile!!.mkdirs()
        File(chunks, "encrypted").writeText("encrypted")
        File(received, "copy.txt").writeText("copy")
        preview.writeText("preview")
        independent.writeText("user copy")
        try {
            db.chatDao().insertChat(ChatEntity("chat", "friend", "Friend"))
            db.messageDao().insertMessage(MessageEntity("id", "chat", "friend", "file", 1))
            db.fileTransferDao().insertTransferIgnore(FileTransferEntity(
                transferId, "id", "chat", "friend", "INCOMING", "file.txt", "text/plain", 1L, 1, 1,
                "hash", "COMPLETE", createdAtMs = 1, expiresAtMs = 10, updatedAtMs = 1,
            ))
            assertTrue(ChatClearLocalCleanup(context, db).apply("chat", listOf("id")))
            assertFalse(chunks.exists())
            assertFalse(received.exists())
            assertFalse(preview.exists())
            assertEquals("user copy", independent.readText())
            assertNull(db.fileTransferDao().getTransfer(transferId))
        } finally {
            chunks.deleteRecursively(); received.deleteRecursively(); preview.delete(); independent.delete()
        }
    }

    @Test fun fileAliasesResolveBothSenderIdsAndRecipientPlaceholdersWithinOneChat() = runBlocking {
        db.chatDao().insertChat(ChatEntity("first", "friend", "Friend"))
        db.chatDao().insertChat(ChatEntity("other", "other-peer", "Other"))
        db.messageDao().insertMessage(MessageEntity("sender-id", "first", "owner", "file", 1, isFromMe = true))
        db.messageDao().insertMessage(MessageEntity("keep", "other", "owner", "other", 2, isFromMe = true))
        val firstId = "a".repeat(64)
        val secondId = "b".repeat(64)
        val avatarId = "c".repeat(64)
        val otherId = "d".repeat(64)
        fun transfer(id: String, message: String, chat: String, name: String) = FileTransferEntity(
            id, message, chat, "friend", "OUTGOING", name, "text/plain", 1L, 1, 1, "hash", "COMPLETE",
            createdAtMs = 1, expiresAtMs = 10, updatedAtMs = 1,
        )
        db.fileTransferDao().insertTransferIgnore(transfer(firstId, "sender-id", "first", "file.txt"))
        db.fileTransferDao().insertTransferIgnore(transfer(secondId, "sender-id", "first", "file.txt"))
        db.fileTransferDao().insertTransferIgnore(transfer(avatarId, "sender-id", "first", "apu-avatar-video-test"))
        db.fileTransferDao().insertTransferIgnore(transfer(otherId, "keep", "other", "file.txt"))
        val aliases = db.fileTransferDao().chatTransferAliasesForClear("first", 100).toSet()
        assertEquals(setOf("file-$firstId", "file-$secondId"), aliases)
        val remembered = mutableSetOf<String>()
        val coordinator = ChatClearApply(
            ownerId = { "owner" },
            chatForPeer = { db.chatDao().getChatByContactId(it)?.id },
            tombstone = { _, _, ids -> remembered.addAll(ids); true },
            cleanup = { chat, ids -> cleanup.apply(chat, ids) },
            relatedIds = { chat, ids -> ChatClearFileAliases(db.fileTransferDao()).relatedIds(chat, ids) },
        )
        assertTrue(coordinator.apply("friend", ChatClearWire.Batch(UUID.randomUUID(), listOf("file-$firstId"))))
        assertEquals(setOf("sender-id", "file-$firstId", "file-$secondId"), remembered)
        assertNull(db.messageDao().getMessageById("sender-id"))
        assertNull(db.fileTransferDao().getTransfer(firstId))
        assertNull(db.fileTransferDao().getTransfer(secondId))
        assertNotNull(db.fileTransferDao().getTransfer(avatarId))
        assertNotNull(db.fileTransferDao().getTransfer(otherId))
        assertNotNull(db.messageDao().getMessageById("keep"))
    }

    @Test fun mirrorFileInsertedDuringClearIsRemovedByPostInsertionGuard() = runBlocking {
        db.chatDao().insertChat(ChatEntity("chat", "friend", "Friend"))
        val file = File(ApplicationProvider.getApplicationContext<Context>().noBackupFilesDir, "clear-mirror-file-${UUID.randomUUID()}.sqlite")
        val tombstones = ChatClearTombstones(ApplicationProvider.getApplicationContext(), file)
        val transferId = "e".repeat(64)
        try {
            val coordinator = ChatClearApply(
                ownerId = { "owner" }, chatForPeer = { db.chatDao().getChatByContactId(it)?.id },
                tombstone = { owner, peer, ids -> tombstones.remember(owner, peer, ids) },
                cleanup = { chat, ids -> cleanup.apply(chat, ids) },
                relatedIds = { chat, ids -> ChatClearFileAliases(db.fileTransferDao()).relatedIds(chat, ids) },
            )
            val guard = ChatClearFileMetaGuard(
                { peer, id -> tombstones.contains("owner", peer, id) },
                { peer, batch -> coordinator.apply(peer, batch) },
            )
            assertFalse(guard.suppress("friend", "", "file.txt", "sender-id", transferId))
            assertTrue(tombstones.remember("owner", "friend", listOf("file-$transferId")))
            db.fileTransferDao().insertTransferIgnore(FileTransferEntity(
                transferId, "sender-id", "chat", "friend", "OUTGOING", "file.txt", "text/plain", 1L, 1, 1,
                "hash", "COMPLETE", createdAtMs = 1, expiresAtMs = 10, updatedAtMs = 1,
            ))
            db.messageDao().insertMessage(MessageEntity("sender-id", "chat", "owner", "file", 1, isFromMe = true))
            assertTrue(guard.suppress("friend", "", "file.txt", "sender-id", transferId))
            assertNull(db.fileTransferDao().getTransfer(transferId))
            assertNull(db.messageDao().getMessageById("sender-id"))
            assertTrue(tombstones.contains("owner", "friend", "sender-id"))
        } finally {
            tombstones.close()
            file.delete()
            File(file.path + "-wal").delete()
            File(file.path + "-shm").delete()
        }
    }

    @Test fun lostAcknowledgementCanBeRetriedWithoutDeletingNewMessages() = runBlocking {
        db.chatDao().insertChat(ChatEntity("first", "friend", "Friend"))
        db.chatDao().insertChat(ChatEntity("other", "other-peer", "Other"))
        db.messageDao().insertMessage(MessageEntity("old", "first", "friend", "old", 1))
        db.messageDao().insertMessage(MessageEntity("unrelated", "other", "other-peer", "other", 2))
        val file = File(ApplicationProvider.getApplicationContext<Context>().noBackupFilesDir, "clear-retry-${UUID.randomUUID()}.sqlite")
        val tombstones = ChatClearTombstones(ApplicationProvider.getApplicationContext(), file)
        try {
            val apply = ChatClearApply(
                ownerId = { "owner" },
                chatForPeer = { db.chatDao().getChatByContactId(it)?.id },
                tombstone = { owner, peer, ids -> tombstones.remember(owner, peer, ids) },
                cleanup = { chatId, ids -> cleanup.apply(chatId, ids) },
            )
            val batch = ChatClearWire.Batch(UUID.randomUUID(), listOf("old", "late"))
            val packet = ChatClearWire.encode(batch)
            val first = ChatClearReceiver(apply) { _, _ -> throw IllegalStateException("simulated lost ACK") }
            var failed = false
            try { first.receive("friend", true, packet) } catch (_: IllegalStateException) { failed = true }
            assertTrue(failed)
            assertNull(db.messageDao().getMessageById("old"))
            assertTrue(tombstones.contains("owner", "friend", "late"))
            db.messageDao().insertMessage(MessageEntity("new", "first", "friend", "new", 3))
            val replies = mutableListOf<String>()
            val retry = ChatClearReceiver(apply) { _, ack -> replies.add(ack) }
            assertTrue(retry.receive("friend", true, packet))
            assertEquals(listOf(ChatClearWire.encodeAck(batch.operation)), replies)
            assertNotNull(db.messageDao().getMessageById("new"))
            assertNotNull(db.messageDao().getMessageById("unrelated"))
            assertFalse(tombstones.contains("owner", "friend", "new"))
        } finally {
            tombstones.close()
            file.delete()
            File(file.path + "-wal").delete()
            File(file.path + "-shm").delete()
        }
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
