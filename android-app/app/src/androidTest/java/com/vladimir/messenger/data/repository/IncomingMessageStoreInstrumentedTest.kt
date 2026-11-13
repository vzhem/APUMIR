package com.vladimir.messenger.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vladimir.messenger.data.chatclear.ChatClearLocalCleanup
import com.vladimir.messenger.data.chatclear.ChatClearTombstones
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.data.local.entity.ChatEntity
import com.vladimir.messenger.data.local.entity.MessageEntity
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

/** Real Room/SQLite; no network, FCM, identity lifecycle or notification display. */
@RunWith(AndroidJUnit4::class)
class IncomingMessageStoreInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "incoming_message_atomic_test.db"
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        context.deleteDatabase(databaseName)
        db = open()
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
        context.deleteDatabase(databaseName)
    }

    private fun open(): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
    private fun row(id: String = "message-1") = MessageEntity(
        id = id, chatId = "chat", senderId = "friend", content = "private text", timestamp = 1_700_000_000_000L,
        isFromMe = false, status = "DELIVERED", recipientId = "me", notificationPending = true,
    )
    private suspend fun createChat() = db.chatDao().insertChat(ChatEntity(id = "chat", contactId = "friend", contactName = "Friend"))
    private suspend fun save(message: MessageEntity = row(), countUnread: Boolean = true, suppressed: () -> Boolean = { false }) =
        IncomingMessageStore(db).save(message, "preview", countUnread, suppressed)
    private fun clearFile(): File = File(context.noBackupFilesDir, "incoming-clear-test-${UUID.randomUUID()}.sqlite")
    private fun deleteClearFile(file: File) {
        context.deleteDatabase(file.absolutePath)
        listOf("-wal", "-shm", "-journal").forEach { File(file.absolutePath + it).delete() }
    }

    @Test fun rowPreviewUnreadAndNotificationCommitOnceAcrossReopen() = runBlocking {
        createChat()
        assertTrue(save())
        assertFalse(save())
        db.close()
        db = open()
        assertFalse(save())
        val stored = db.messageDao().getMessageById("message-1")!!
        val chat = db.chatDao().getChatById("chat")!!
        assertEquals("private text", stored.content)
        assertTrue(stored.notificationPending)
        assertEquals("preview", chat.lastMessage)
        assertEquals(row().timestamp, chat.lastMessageTime)
        assertEquals(1, chat.unreadCount)
        assertEquals(listOf("message-1"), db.messageDao().observePendingNotifications("me").first().map { it.id })
    }

    @Test fun concurrentDuplicatesAndConflictingSenderNeverModifyTheWinner() = runBlocking {
        createChat()
        val outcomes = coroutineScope {
            (1..16).map { async(Dispatchers.IO) { save() } }.awaitAll()
        }
        assertEquals(1, outcomes.count { it })
        assertFalse(save(row().copy(senderId = "other", content = "different")))
        assertEquals("friend", db.messageDao().getMessageById("message-1")!!.senderId)
        assertEquals("private text", db.messageDao().getMessageById("message-1")!!.content)
        assertEquals(1, db.chatDao().getChatById("chat")!!.unreadCount)
        assertEquals(1, db.messageDao().observePendingNotifications("me").first().size)
    }

    @Test fun suppressedOrMissingChatDoesNotLeaveAnOrphanOrNotification() = runBlocking {
        assertFalse(save()) // the chat was deleted before this transaction
        assertNull(db.messageDao().getMessageById("message-1"))
        createChat()
        assertFalse(save(suppressed = { true }))
        assertNull(db.messageDao().getMessageById("message-1"))
        assertTrue(db.messageDao().observePendingNotifications("me").first().isEmpty())
        val untouched = db.chatDao().getChatById("chat")!!
        assertNull(untouched.lastMessage)
        assertEquals(0, untouched.unreadCount)
        assertTrue(save())
    }

    @Test fun suppressionArrivingDuringTheTransactionLeavesNoPartialState() = runBlocking {
        createChat()
        val checkStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val suppressed = AtomicBoolean(false)
        try {
            val pending = async(Dispatchers.IO) {
                save(suppressed = {
                    checkStarted.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                    suppressed.get()
                })
            }
            assertTrue(checkStarted.await(15, TimeUnit.SECONDS))
            suppressed.set(true)
            release.countDown()
            assertFalse(pending.await())
        } finally {
            release.countDown()
        }
        assertNull(db.messageDao().getMessageById("message-1"))
        assertNull(db.chatDao().getChatById("chat")!!.lastMessage)
        assertEquals(0, db.chatDao().getChatById("chat")!!.unreadCount)
        assertTrue(db.messageDao().observePendingNotifications("me").first().isEmpty())
    }

    @Test fun clearTombstoneWrittenDuringInsertionIsObservedBeforeCommit() = runBlocking {
        createChat()
        val file = clearFile()
        val tombstones = ChatClearTombstones(context, file)
        val checkStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val incoming = async(Dispatchers.IO) {
                save(suppressed = {
                    checkStarted.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                    tombstones.contains("me", "friend", "message-1")
                })
            }
            assertTrue(checkStarted.await(15, TimeUnit.SECONDS))
            assertTrue(tombstones.remember("me", "friend", listOf("message-1")))
            release.countDown()
            assertFalse(incoming.await())
            assertNull(db.messageDao().getMessageById("message-1"))
            assertEquals(0, db.chatDao().getChatById("chat")!!.unreadCount)
            assertTrue(db.messageDao().observePendingNotifications("me").first().isEmpty())
        } finally {
            release.countDown()
            tombstones.close()
            deleteClearFile(file)
        }
    }

    @Test fun clearAfterAnOldCheckEventuallyRemovesTheCommittedRowAndMetadata() = runBlocking {
        createChat()
        val file = clearFile()
        val tombstones = ChatClearTombstones(context, file)
        val checkStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleanupStarted = CountDownLatch(1)
        try {
            val incoming = async(Dispatchers.IO) {
                save(suppressed = {
                    val old = tombstones.contains("me", "friend", "message-1")
                    checkStarted.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                    old
                })
            }
            assertTrue(checkStarted.await(15, TimeUnit.SECONDS))
            assertTrue(tombstones.remember("me", "friend", listOf("message-1")))
            val cleanup = ChatClearLocalCleanup(db, rememberFiles = { _, _ -> true }, removePrivateFiles = { true })
            val cleared = async(Dispatchers.IO) {
                cleanupStarted.countDown()
                cleanup.apply("chat", listOf("message-1"))
            }
            assertTrue(cleanupStarted.await(15, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(incoming.await())
            assertTrue(cleared.await())
            assertNull(db.messageDao().getMessageById("message-1"))
            val chat = db.chatDao().getChatById("chat")!!
            assertEquals("", chat.lastMessage)
            assertEquals(0, chat.unreadCount)
            assertTrue(db.messageDao().observePendingNotifications("me").first().isEmpty())
            assertTrue(tombstones.contains("me", "friend", "message-1"))
        } finally {
            release.countDown()
            tombstones.close()
            deleteClearFile(file)
        }
    }

    @Test fun sqliteFailureRollsBackEverythingAndTheSameIdCanBeRetried() = runBlocking {
        createChat()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER refuse_incoming_preview BEFORE UPDATE ON chats BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END",
        )
        var failed = false
        try { save() } catch (_: Exception) { failed = true }
        assertTrue(failed)
        assertNull(db.messageDao().getMessageById("message-1"))
        val chat = db.chatDao().getChatById("chat")!!
        assertNull(chat.lastMessage)
        assertEquals(0, chat.unreadCount)
        assertTrue(db.messageDao().observePendingNotifications("me").first().isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER refuse_incoming_preview")
        assertTrue(save())
        assertEquals(1, db.chatDao().getChatById("chat")!!.unreadCount)
    }

    @Test fun cancellationRollsBackAndNonAlertingServiceRowsKeepTheirPolicy() = runBlocking {
        createChat()
        var cancelled = false
        try { save(suppressed = { throw CancellationException("synthetic cancellation") }) }
        catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertNull(db.messageDao().getMessageById("message-1"))
        assertTrue(save(row().copy(notificationPending = false), countUnread = false))
        val chat = db.chatDao().getChatById("chat")!!
        assertEquals("preview", chat.lastMessage)
        assertEquals(0, chat.unreadCount)
        assertFalse(db.messageDao().getMessageById("message-1")!!.notificationPending)
        assertTrue(db.messageDao().observePendingNotifications("me").first().isEmpty())
    }
}
