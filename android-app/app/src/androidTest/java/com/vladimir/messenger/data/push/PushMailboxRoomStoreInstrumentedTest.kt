package com.vladimir.messenger.data.push

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vladimir.messenger.data.chatclear.ChatClearTombstones
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.data.local.entity.ChatEntity
import com.vladimir.messenger.data.local.entity.DirectPollEntity
import com.vladimir.messenger.data.local.entity.FileExchangePeerEntity
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import com.vladimir.messenger.data.local.entity.GroupEntity
import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.data.repository.IncomingMessageStore
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Room/SQLite, but synthetic already-decoded messages and fake binding
 * bytes: authentication, lifecycle/restore, network and notification display
 * are NOT exercised here. Real FFI is covered separately by the drain test.
 */
@RunWith(AndroidJUnit4::class)
class PushMailboxRoomStoreInstrumentedTest {
    private val sender = "pk_" + "1".repeat(32)
    private val recipient = "pk_" + "2".repeat(32)
    private val other = "pk_" + "3".repeat(32)
    private val id = "01234567-89ab-4cde-8f01-23456789abcd"
    private val pin = byteArrayOf(1, 3, 5, 7)
    private val sentAt = 1_700_000_000_000L
    private val now = sentAt + 1_000L
    private val body = "Привет, спящий получатель 😀"
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        name = "push-room-${UUID.randomUUID()}.db"
        db = open()
        runBlocking {
            db.chatDao().insertChat(ChatEntity(id = "chat", contactId = sender, contactName = "Friend"))
            db.fileExchangePeerDao().insertFirstSeen(pinRow(pin))
        }
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
        if (::name.isInitialized) context.deleteDatabase(name)
    }

    private fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
    private fun pinRow(bytes: ByteArray): FileExchangePeerEntity {
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        return FileExchangePeerEntity(sender, Base64.getEncoder().encodeToString(bytes), hash, "00".repeat(32), "TOFU", 1, 1)
    }
    private fun message(
        id: String = this.id, content: String = body, at: Long = sentAt,
    ): PushMailboxMessageCodec.Message = PushMailboxMessageCodec.parse(
        PushMailboxMessageCodec.encode(id, sender, recipient, at, content)!!, sender, recipient,
    )!!
    private fun store(suppressed: (String, String) -> Boolean = { _, _ -> false }, clock: () -> Long = { now }) =
        PushMailboxRoomStore(db, suppressed, clock)
    private suspend fun commit(
        message: PushMailboxMessageCodec.Message = message(),
        pin: ByteArray = this.pin,
        current: () -> Boolean = { true },
        store: PushMailboxRoomStore = store(),
    ) = store.commit(message, pin, current)
    private fun ordinaryRow() = MessageEntity(
        id = id, chatId = "chat", senderId = sender, recipientId = recipient, content = body,
        timestamp = sentAt + 20, status = "DELIVERED", notificationPending = true,
    )
    private suspend fun assertNothingCommitted() {
        assertNull(db.messageDao().getMessageById(id))
        val chat = db.chatDao().getChatById("chat")!!
        assertNull(chat.lastMessage)
        assertEquals(0, chat.unreadCount)
        assertTrue(db.messageDao().observePendingNotifications(recipient).first().isEmpty())
    }

    @Test fun rowPreviewUnreadAndAlertCommitOnceAcrossReopenEvenWhenArrivalTimeDiffers() = runBlocking {
        assertEquals(PushMailboxDrain.Commit.COMMITTED, commit())
        val row = db.messageDao().getMessageById(id)!!
        assertEquals(body, row.content)
        assertEquals(sentAt, row.timestamp)
        assertEquals(recipient, row.recipientId)
        assertTrue(row.notificationPending)
        assertEquals(body, db.chatDao().getChatById("chat")!!.lastMessage)
        assertEquals(1, db.chatDao().getChatById("chat")!!.unreadCount)
        assertEquals(1, db.messageDao().claimPendingNotification(id))
        db.close()
        db = open()
        // Native transports often record their arrival time, not sender time.
        assertEquals(PushMailboxDrain.Commit.ALREADY_COMMITTED, commit(message(at = sentAt + 42)))
        assertFalse(db.messageDao().getMessageById(id)!!.notificationPending)
        assertEquals(1, db.chatDao().getChatById("chat")!!.unreadCount)
        assertEquals(sentAt, db.messageDao().getMessageById(id)!!.timestamp)
    }

    @Test fun missingChangedPinsOrAmbiguousChatsNeverMutateRoom() = runBlocking {
        db.fileExchangePeerDao().deleteForContact(sender)
        assertEquals(PushMailboxDrain.Commit.RETRY, commit())
        db.fileExchangePeerDao().insertFirstSeen(pinRow(byteArrayOf(9, 8, 7)))
        assertEquals(PushMailboxDrain.Commit.RETRY, commit())
        db.fileExchangePeerDao().deleteForContact(sender)
        db.fileExchangePeerDao().insertFirstSeen(pinRow(pin).copy(bindingSha256 = "ab".repeat(32)))
        assertEquals(PushMailboxDrain.Commit.RETRY, commit())
        db.fileExchangePeerDao().deleteForContact(sender)
        db.fileExchangePeerDao().insertFirstSeen(pinRow(pin).copy(bindingBase64 = pinRow(pin).bindingBase64.trimEnd('=')))
        assertEquals(PushMailboxDrain.Commit.RETRY, commit())
        db.fileExchangePeerDao().deleteForContact(sender)
        db.fileExchangePeerDao().insertFirstSeen(pinRow(pin))
        db.chatDao().insertChat(ChatEntity(id = "other-chat", contactId = sender, contactName = "Duplicate"))
        assertEquals(PushMailboxDrain.Commit.RETRY, commit())
        db.chatDao().deleteChatById("other-chat")
        db.groupDao().insertGroup(GroupEntity(id = "chat", title = "Group", ownerId = sender))
        assertEquals(PushMailboxDrain.Commit.RETRY, commit())
        db.groupDao().deleteGroup("chat")
        assertNothingCommitted()
        db.chatDao().deleteChatById("chat")
        assertEquals(PushMailboxDrain.Commit.RETRY, commit())
        assertNull(db.messageDao().getMessageById(id))
    }

    @Test fun conflictingRowsOrRichMetadataAreNeverTreatedAsDuplicates() = runBlocking {
        val row = ordinaryRow()
        val conflicts = listOf(
            row.copy(chatId = "elsewhere"), row.copy(senderId = other), row.copy(recipientId = other),
            row.copy(recipientId = ""), row.copy(content = "Different"), row.copy(isFromMe = true),
            row.copy(topicId = "topic"), row.copy(channel = "GROUP"), row.copy(channel = "group"),
            row.copy(channel = "LOCAL\u0000tail"), row.copy(channel = "future"), row.copy(channel = ""),
            row.copy(status = "PENDING"), row.copy(status = "RECEIVED"), row.copy(replyToId = "parent"),
            row.copy(replyAuthor = "Name"), row.copy(replyText = "Quote"), row.copy(editedAtMs = 5),
            row.copy(textColor = "red"), row.copy(senderNameColor = "red"),
        )
        for (candidate in conflicts) {
            db.messageDao().insertMessageIgnore(candidate)
            assertEquals(PushMailboxDrain.Commit.RETRY, commit())
            assertEquals(candidate, db.messageDao().getMessageById(id))
            db.messageDao().deleteById(id)
        }
        for (channel in listOf("UNKNOWN", "MQTT", "CF", "LOCAL", "STORE_FORWARD")) {
            db.messageDao().insertMessageIgnore(row.copy(channel = channel))
            assertEquals(PushMailboxDrain.Commit.ALREADY_COMMITTED, commit())
            db.messageDao().deleteById(id)
        }
        assertNothingCommitted()
        assertEquals(PushMailboxDrain.Commit.RETRY, commit(message(content = "APUQUIET1:quiet")))
        assertNothingCommitted()
    }

    @Test fun existingFileOrPollMetadataPreventsFalseTextCommit() = runBlocking {
        db.directPollDao().insertPoll(DirectPollEntity("poll", "chat", id, body, "one,two"))
        assertEquals(PushMailboxDrain.Commit.RETRY, commit())
        db.directPollDao().deleteByMessage(id)
        db.fileTransferDao().insertTransferIgnore(FileTransferEntity(
            transferId = "file", messageId = id, chatId = "other-chat", peerNodeId = sender,
            direction = "INCOMING", displayName = "photo.jpg", mediaType = "image/jpeg",
            totalBytes = 1, chunkSize = 1, chunkCount = 1, fileSha256 = "ab".repeat(32), state = "OFFERED",
            createdAtMs = sentAt, expiresAtMs = now, updatedAtMs = sentAt,
        ))
        assertEquals(PushMailboxDrain.Commit.RETRY, commit())
        assertNothingCommitted()
    }

    @Test fun staleSuppressionCancellationAndMutatedPinRollBackAllChanges() = runBlocking {
        var checks = 0
        assertEquals(PushMailboxDrain.Commit.RETRY, commit(current = { ++checks < 3 }))
        assertEquals(3, checks)
        assertNothingCommitted()

        var suppressChecks = 0
        val suppressed = store(suppressed = { _, _ -> ++suppressChecks == 3 })
        assertEquals(PushMailboxDrain.Commit.RETRY, commit(store = suppressed))
        assertEquals(3, suppressChecks)
        assertNothingCommitted()

        checks = 0
        val failure = runCatching {
            commit(current = { if (++checks == 3) throw CancellationException("test") else true })
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertNothingCommitted()

        val changing = pin.copyOf()
        checks = 0
        assertEquals(PushMailboxDrain.Commit.RETRY, commit(pin = changing, current = {
            if (++checks == 2) changing[0] = 42
            true
        }))
        assertEquals(42.toByte(), changing[0]) // caller-owned memory was not wiped
        assertNothingCommitted()
        assertEquals(PushMailboxDrain.Commit.COMMITTED, commit())
    }

    @Test fun clearRecordedDuringInsertionIsObservedBeforeRoomCommit() = runBlocking {
        val file = File(context.noBackupFilesDir, "push-room-clear-${UUID.randomUUID()}.sqlite")
        val tombstones = ChatClearTombstones(context, file)
        val checking = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            var checks = 0
            val checkedStore = store(suppressed = { peer, messageId ->
                if (++checks == 2) {
                    checking.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                }
                tombstones.contains(recipient, peer, messageId)
            })
            val pending = async(Dispatchers.IO) { commit(store = checkedStore) }
            assertTrue(checking.await(15, TimeUnit.SECONDS))
            assertTrue(tombstones.remember(recipient, sender, listOf(id)))
            release.countDown()
            assertEquals(PushMailboxDrain.Commit.RETRY, pending.await())
            assertNothingCommitted()
        } finally {
            release.countDown()
            tombstones.close()
            context.deleteDatabase(file.absolutePath)
            listOf("-wal", "-shm", "-journal").forEach { File(file.absolutePath + it).delete() }
        }
    }

    @Test fun sqliteFailureRollsBackAndTheSameMessageCanBeRetried() = runBlocking {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER refuse_mailbox BEFORE UPDATE ON chats BEGIN SELECT RAISE(ABORT, 'test'); END",
        )
        assertTrue(runCatching { commit() }.isFailure)
        assertNothingCommitted()
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER refuse_mailbox")
        assertEquals(PushMailboxDrain.Commit.COMMITTED, commit())
        assertEquals(1, db.chatDao().getChatById("chat")!!.unreadCount)
    }

    @Test fun delayedTextKeepsNewerPreviewAndFutureTimeIsCapped() = runBlocking {
        db.chatDao().updateLastMessage("chat", "Newer preview", sentAt + 500)
        assertEquals(PushMailboxDrain.Commit.COMMITTED, commit())
        assertEquals("Newer preview", db.chatDao().getChatById("chat")!!.lastMessage)
        assertEquals(sentAt + 500, db.chatDao().getChatById("chat")!!.lastMessageTime)
        assertEquals(1, db.chatDao().getChatById("chat")!!.unreadCount)
        val second = "11234567-89ab-4cde-8f01-23456789abcd"
        assertEquals(PushMailboxDrain.Commit.COMMITTED, commit(message(second, "Later", now + 10_000)))
        assertEquals(now, db.messageDao().getMessageById(second)!!.timestamp)
        assertEquals(now, db.chatDao().getChatById("chat")!!.lastMessageTime)
        assertEquals("Later", db.chatDao().getChatById("chat")!!.lastMessage)
        assertEquals(2, db.chatDao().getChatById("chat")!!.unreadCount)
        assertEquals(PushMailboxDrain.Commit.RETRY, commit(message(second, "Later"), store = store(clock = { 0 })))
    }

    @Test fun concurrentCopiesAndOrdinaryIngressCannotRearmOrReplaceTheWinner() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val outcomes = coroutineScope {
            val ordinary = async(Dispatchers.IO) {
                gate.await()
                IncomingMessageStore(db).save(ordinaryRow(), body, true) { false }
            }
            val mailbox = (1..12).map { async(Dispatchers.IO) { gate.await(); commit() } }
            gate.complete(Unit)
            ordinary.await() to mailbox.awaitAll()
        }
        val (ordinaryWon, mailbox) = outcomes
        assertEquals(if (ordinaryWon) 0 else 1, mailbox.count { it == PushMailboxDrain.Commit.COMMITTED })
        assertEquals(if (ordinaryWon) 12 else 11, mailbox.count { it == PushMailboxDrain.Commit.ALREADY_COMMITTED })
        assertEquals(1, db.chatDao().getChatById("chat")!!.unreadCount)
        assertEquals(1, db.messageDao().observePendingNotifications(recipient).first().size)
        assertEquals(PushMailboxDrain.Commit.RETRY, commit(message(content = "Different")))
        assertEquals(body, db.messageDao().getMessageById(id)!!.content)
    }
}
