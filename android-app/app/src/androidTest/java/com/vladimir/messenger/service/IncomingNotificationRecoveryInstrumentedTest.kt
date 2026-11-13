package com.vladimir.messenger.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.vladimir.messenger.MessengerApplication
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.data.local.entity.ChatEntity
import com.vladimir.messenger.data.local.entity.GroupEntity
import com.vladimir.messenger.data.local.entity.GroupTopicEntity
import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.data.repository.IncomingMessageStore
import com.vladimir.messenger.data.repository.IncomingGroupNotificationReads
import com.vladimir.messenger.data.repository.IncomingNotificationReads
import com.vladimir.messenger.worker.IncomingNotificationRecoveryWorker
import com.vladimir.messenger.worker.NotificationRecoveryCheckpoint
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room/SQLite and SharedPreferences; the notification manager and identity are fakes. */
@RunWith(AndroidJUnit4::class)
class IncomingNotificationRecoveryInstrumentedTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var prefsName: String
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val suffix = UUID.randomUUID().toString()
        name = "notification-recovery-$suffix.db"
        prefsName = "notification-recovery-$suffix"
        db = open()
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
        if (::name.isInitialized) context.deleteDatabase(name)
        if (::prefsName.isInitialized) context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
    private fun row(id: String, body: String = "body", recipient: String = "me") = MessageEntity(
        id = id, chatId = "chat", senderId = "friend", content = body, timestamp = 123L,
        recipientId = recipient, notificationPending = true,
    )
    private suspend fun finish(message: MessageEntity): Boolean = db.messageDao().completePendingNotification(
        message.id, message.chatId, message.senderId, message.recipientId,
        message.content, message.timestamp, message.topicId, message.channel,
    ) == 1
    private fun processor(
        active: MutableSet<String>,
        alert: suspend (MessageEntity, IncomingAlert) -> Unit,
        complete: suspend (MessageEntity) -> Boolean = { finish(it) },
    ) = IncomingNotificationProcessor(
        recipientId = "me", load = { db.messageDao().getMessageById(it) }, complete = complete,
        isSuppressed = { false }, isActive = { it.id in active },
        alert = { message, kind -> alert(message, kind); IncomingNotificationPost.POSTED },
        cancel = { active.remove(it.id) }, current = { true },
    )
    private fun sweep(processor: IncomingNotificationProcessor) = IncomingNotificationSweep(
        maxPendingRowId = { db.messageDao().maxPendingNotificationRowId("me") },
        page = { after, through, limit -> db.messageDao().pendingNotificationPage("me", after, through, limit) },
        process = processor::process, current = { true }, recordFailure = {},
    )

    @Test fun snapshotCompletionDoesNotClearChangedOrForeignRows() = runBlocking {
        val original = row("id")
        val variants = listOf(
            original.copy(content = "changed"), original.copy(chatId = "elsewhere"),
            original.copy(senderId = "other"), original.copy(recipientId = "other"),
            original.copy(timestamp = 124L), original.copy(topicId = "topic"), original.copy(channel = "GROUP"),
            original.copy(isFromMe = true), original.copy(notificationPending = false),
        )
        for (changed in variants) {
            db.messageDao().insertMessage(changed)
            assertFalse(finish(original))
            assertEquals(changed.notificationPending, db.messageDao().getMessageById("id")!!.notificationPending)
        }
        db.messageDao().insertMessage(original)
        assertTrue(finish(original))
        assertFalse(db.messageDao().getMessageById("id")!!.notificationPending)
        assertFalse(finish(original))
        val topic = row("topic").copy(topicId = "topic-a")
        db.messageDao().insertMessage(topic)
        assertTrue(finish(topic))
    }

    @Test fun postBeforeCompletionSurvivesReopenAndActivePostAvoidsSecondAlert() = runBlocking {
        db.messageDao().insertMessage(row("id"))
        val active = ConcurrentHashMap.newKeySet<String>()
        val posts = AtomicInteger()
        val first = processor(active, { message, _ -> posts.incrementAndGet(); active.add(message.id) },
            complete = { error("synthetic crash after Android accepted notify") })
        assertTrue(runCatching { first.process("id") }.isFailure)
        assertTrue(db.messageDao().getMessageById("id")!!.notificationPending)
        db.close()
        db = open()
        val retry = processor(active, { message, _ -> posts.incrementAndGet(); active.add(message.id) })
        assertEquals(IncomingNotificationProcessor.Result.DONE, retry.process("id"))
        assertEquals(1, posts.get())
        assertFalse(db.messageDao().getMessageById("id")!!.notificationPending)
    }

    @Test fun simultaneousServiceAndWorkerProcessorsPostOnce() = runBlocking {
        db.messageDao().insertMessage(row("id"))
        val active = ConcurrentHashMap.newKeySet<String>()
        val posts = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = processor(active, { message, _ ->
            posts.incrementAndGet(); active.add(message.id); entered.complete(Unit); release.await()
        })
        val second = processor(active, { message, _ -> posts.incrementAndGet(); active.add(message.id) })
        try {
            coroutineScope {
                val posting = async(Dispatchers.IO) { first.process("id") }
                entered.await()
                val others = (1..12).map { async(Dispatchers.IO) { second.process("id") } }
                release.complete(Unit)
                posting.await()
                others.awaitAll()
            }
            assertEquals(1, posts.get())
            assertFalse(db.messageDao().getMessageById("id")!!.notificationPending)
        } finally { release.complete(Unit) }
    }

    @Test fun boundedCursorSurvivesReopenAndVisitsPastFailuresBeforeWrapping() = runBlocking {
        val dao = db.messageDao()
        (1..4).forEach { dao.insertMessage(row("id-$it")) }
        dao.insertMessage(row("other-recipient", recipient = "other"))
        dao.insertMessage(row("outgoing").copy(isFromMe = true))
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val checkpoints = NotificationRecoveryCheckpoint(prefs)
        val active = ConcurrentHashMap.newKeySet<String>()
        val visited = mutableListOf<String>()
        var failFirst = true
        val p = processor(active, { message, _ ->
            visited += message.id
            if (message.id == "id-1" && failFirst) error("synthetic failure")
            active.add(message.id)
        })
        val first = sweep(p).run(checkpoints.read("me"), 2)!!
        assertTrue(first.retry)
        assertTrue(checkpoints.save("me", first.cursor))
        val through = first.cursor.through
        db.close()
        db = open()
        db.messageDao().insertMessage(row("newer"))
        assertEquals(first.cursor, NotificationRecoveryCheckpoint(prefs).read("me"))
        val second = sweep(p).run(checkpoints.read("me"), 2)!!
        assertEquals(through, second.cursor.through)
        assertEquals(listOf("id-1", "id-2", "id-3", "id-4"), visited)
        val end = sweep(p).run(second.cursor, 2)!!
        assertEquals(IncomingNotificationSweep.Cursor(), end.cursor)
        assertTrue(end.retry)
        failFirst = false
        val retry = sweep(p).run(end.cursor, 2)!!
        assertEquals(listOf("id-1", "newer"), visited.takeLast(2))
        assertTrue(retry.retry)
        assertTrue(db.messageDao().getMessageById("other-recipient")!!.notificationPending)
        assertTrue(db.messageDao().getMessageById("outgoing")!!.notificationPending)
    }

    @Test fun checkpointIsPrivateProfileScopedAndMalformedValuesRescan() {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val checkpoint = NotificationRecoveryCheckpoint(prefs)
        val saved = IncomingNotificationSweep.Cursor(2, 10)
        assertTrue(checkpoint.save("pk-owner-a", saved))
        assertEquals(saved, NotificationRecoveryCheckpoint(prefs).read("pk-owner-a"))
        assertNotEquals("pk-owner-a", prefs.getString("owner", null))
        assertEquals(IncomingNotificationSweep.Cursor(), checkpoint.read("pk-owner-b"))
        assertTrue(prefs.edit().putLong("after", 11).commit())
        assertEquals(IncomingNotificationSweep.Cursor(), checkpoint.read("pk-owner-a"))
    }

    @Test fun hiltCreatesTheActualWorkerAndNoIdentityStopsTheSweep() = runBlocking {
        DisposableNotificationTestProfile.requireEmptyEmulator(context)
        val app = context.applicationContext as MessengerApplication
        val worker = TestListenableWorkerBuilder<IncomingNotificationRecoveryWorker>(context)
            .setWorkerFactory(app.workerFactory).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }

    @Test fun directReadClearsOnlyPriorWorkAndLaterArrivalsRemainPending() = runBlocking {
        db.chatDao().insertChat(ChatEntity(id = "chat", contactId = "friend", contactName = "Friend", unreadCount = 1))
        db.chatDao().insertChat(ChatEntity(id = "other", contactId = "friend-2", contactName = "Other", unreadCount = 1))
        db.messageDao().insertMessage(row("old"))
        db.messageDao().insertMessage(row("other").copy(chatId = "other"))
        db.messageDao().insertMessage(row("outgoing").copy(isFromMe = true))
        val cancelled = mutableListOf<String>()
        val reads = IncomingNotificationReads(db) { cancelled += it }
        reads.markRead("chat")
        assertEquals(0, db.chatDao().getChatById("chat")!!.unreadCount)
        assertFalse(db.messageDao().getMessageById("old")!!.notificationPending)
        assertTrue(db.messageDao().getMessageById("other")!!.notificationPending)
        assertTrue(db.messageDao().getMessageById("outgoing")!!.notificationPending)
        val store = IncomingMessageStore(db)
        assertTrue(store.save(row("after-read"), "body", true) { false })
        assertTrue(db.messageDao().getMessageById("after-read")!!.notificationPending)
        assertEquals(1, db.chatDao().getChatById("chat")!!.unreadCount)
        reads.toggleRead("chat")
        assertFalse(db.messageDao().getMessageById("after-read")!!.notificationPending)
        assertEquals(0, db.chatDao().getChatById("chat")!!.unreadCount)
        reads.toggleRead("chat") // marking unread never re-arms an old alert
        assertEquals(1, db.chatDao().getChatById("chat")!!.unreadCount)
        assertFalse(db.messageDao().getMessageById("after-read")!!.notificationPending)
        assertEquals(listOf("chat", "chat"), cancelled)
    }

    @Test fun directReadWaitsForAnInFlightPostAndCancelsItAfterCompletion() = runBlocking {
        db.chatDao().insertChat(ChatEntity(id = "chat", contactId = "friend", contactName = "Friend", unreadCount = 1))
        db.messageDao().insertMessage(row("id"))
        val active = ConcurrentHashMap.newKeySet<String>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cancelled = mutableListOf<String>()
        val p = processor(active, { message, _ -> active.add(message.id); entered.complete(Unit); release.await() })
        val reads = IncomingNotificationReads(db) { cancelled += it; active.clear() }
        try {
            coroutineScope {
                val post = async(Dispatchers.IO) { p.process("id") }
                entered.await()
                val read = async(Dispatchers.IO) { reads.markRead("chat") }
                // Read cannot clear or cancel while the same-process post owns the gate.
                assertFalse(read.isCompleted)
                release.complete(Unit)
                post.await()
                read.await()
            }
            assertEquals(listOf("chat"), cancelled)
            assertTrue(active.isEmpty())
            assertFalse(db.messageDao().getMessageById("id")!!.notificationPending)
            assertEquals(0, db.chatDao().getChatById("chat")!!.unreadCount)
        } finally { release.complete(Unit) }
    }

    @Test fun groupAndTopicReadsClearOnlyTheirScopedPendingAlerts() = runBlocking {
        val groups = db.groupDao()
        groups.insertGroup(GroupEntity(id = "group", title = "Group", ownerId = "owner", unreadCount = 2))
        groups.insertGroup(GroupEntity(id = "other-group", title = "Other", ownerId = "owner", unreadCount = 1))
        groups.insertTopic(GroupTopicEntity(id = "topic-a", groupId = "group", name = "A", unreadCount = 1))
        groups.insertTopic(GroupTopicEntity(id = "topic-b", groupId = "group", name = "B", unreadCount = 1))
        db.messageDao().insertMessage(row("a").copy(chatId = "group", channel = "GROUP", topicId = "topic-a"))
        db.messageDao().insertMessage(row("b").copy(chatId = "group", channel = "GROUP", topicId = "topic-b"))
        db.messageDao().insertMessage(row("other-group-row").copy(chatId = "other-group", channel = "GROUP", topicId = "topic-a"))
        db.messageDao().insertMessage(row("direct-row").copy(chatId = "group", topicId = "topic-a"))
        groups.markTopicRead("group", "topic-a")
        assertEquals(0, groups.getTopicById("topic-a")!!.unreadCount)
        assertFalse(db.messageDao().getMessageById("a")!!.notificationPending)
        assertTrue(db.messageDao().getMessageById("b")!!.notificationPending)
        assertTrue(db.messageDao().getMessageById("other-group-row")!!.notificationPending)
        assertTrue(db.messageDao().getMessageById("direct-row")!!.notificationPending)
        groups.markGroupRead("group")
        assertEquals(0, groups.getGroupById("group")!!.unreadCount)
        assertFalse(db.messageDao().getMessageById("b")!!.notificationPending)
        assertTrue(db.messageDao().getMessageById("other-group-row")!!.notificationPending)
        assertTrue(db.messageDao().getMessageById("direct-row")!!.notificationPending)
    }

    @Test fun groupReadWaitsForInFlightPostAndCancelsOnlyTheReadTopic() = runBlocking {
        val groups = db.groupDao()
        groups.insertGroup(GroupEntity(id = "group", title = "Group", ownerId = "owner", unreadCount = 2))
        groups.insertTopic(GroupTopicEntity(id = "topic-a", groupId = "group", name = "A", unreadCount = 1))
        groups.insertTopic(GroupTopicEntity(id = "topic-b", groupId = "group", name = "B", unreadCount = 1))
        db.messageDao().insertMessage(row("a").copy(chatId = "group", channel = "GROUP", topicId = "topic-a"))
        db.messageDao().insertMessage(row("b").copy(chatId = "group", channel = "GROUP", topicId = "topic-b"))
        val active = ConcurrentHashMap.newKeySet<String>().apply { add("b") }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cancelled = mutableListOf<Pair<String, String>>()
        val p = processor(active, { message, _ -> active.add(message.id); entered.complete(Unit); release.await() })
        val reads = IncomingGroupNotificationReads(groups, { error("unexpected group cancellation") }, { group, topic ->
            cancelled += group to topic
            if (group == "group" && topic == "topic-a") active.remove("a")
        })
        try {
            coroutineScope {
                val post = async(Dispatchers.IO) { p.process("a") }
                entered.await()
                val read = async(Dispatchers.IO) { reads.topic("group", "topic-a") }
                assertFalse(read.isCompleted)
                release.complete(Unit)
                assertEquals(IncomingNotificationProcessor.Result.DONE, post.await())
                assertTrue(read.await() == true)
            }
            assertEquals(listOf("group" to "topic-a"), cancelled)
            assertFalse("a" in active)
            assertTrue("b" in active)
            assertFalse(db.messageDao().getMessageById("a")!!.notificationPending)
            assertTrue(db.messageDao().getMessageById("b")!!.notificationPending)
            assertNull(reads.topic("another-group", "topic-b"))
            assertEquals(1, groups.getTopicById("topic-b")!!.unreadCount)
        } finally { release.complete(Unit) }
    }

    @Test fun mirroredTopicReadKeepsOtherTopicUnreadAndPending() = runBlocking {
        val groups = db.groupDao()
        groups.insertGroup(GroupEntity(id = "group", title = "Group", ownerId = "owner", unreadCount = 2))
        groups.insertTopic(GroupTopicEntity(id = "a", groupId = "group", name = "A", unreadCount = 1))
        groups.insertTopic(GroupTopicEntity(id = "b", groupId = "group", name = "B", unreadCount = 1))
        db.messageDao().insertMessage(row("msg-a").copy(chatId = "group", channel = "GROUP", topicId = "a"))
        db.messageDao().insertMessage(row("msg-b").copy(chatId = "group", channel = "GROUP", topicId = "b"))
        val cancelled = mutableListOf<Pair<String, String>>()
        val reads = IncomingGroupNotificationReads(groups, { error("unexpected group cancellation") }, { g, t -> cancelled += g to t })
        reads.mirror("group", "a")
        assertEquals(1, groups.getGroupById("group")!!.unreadCount)
        assertEquals(0, groups.getTopicById("a")!!.unreadCount)
        assertEquals(1, groups.getTopicById("b")!!.unreadCount)
        assertFalse(db.messageDao().getMessageById("msg-a")!!.notificationPending)
        assertTrue(db.messageDao().getMessageById("msg-b")!!.notificationPending)
        assertEquals(listOf("group" to "a"), cancelled)
        reads.mirror("unrelated", "b")
        assertTrue(db.messageDao().getMessageById("msg-b")!!.notificationPending)
        assertEquals(1, groups.getTopicById("b")!!.unreadCount)
    }

    @Test fun sqlitePagesIncludeNegativeAndZeroRowidsAfterReopen() = runBlocking {
        db.messageDao().insertMessage(row("negative"))
        db.messageDao().insertMessage(row("zero"))
        db.messageDao().insertMessage(row("positive"))
        db.openHelper.writableDatabase.execSQL("UPDATE messages SET rowid = -5 WHERE id = 'negative'")
        db.openHelper.writableDatabase.execSQL("UPDATE messages SET rowid = 0 WHERE id = 'zero'")
        db.close()
        db = open()
        val max = requireNotNull(db.messageDao().maxPendingNotificationRowId("me"))
        val first = db.messageDao().pendingNotificationPage("me", null, max, 2)
        assertEquals(listOf("negative", "zero"), first.map { it.id })
        assertEquals(listOf(-5L, 0L), first.map { it.sequence })
        assertEquals(listOf("positive"), db.messageDao().pendingNotificationPage("me", first.last().sequence, max, 2).map { it.id })
    }

    @Test fun notificationSchedulingIsAfterCommitAndNeverRearmsDuplicates() = runBlocking {
        db.chatDao().insertChat(ChatEntity(id = "chat", contactId = "friend", contactName = "Friend"))
        var calls = 0
        var inTransaction = true
        val store = IncomingMessageStore(db) { calls++; inTransaction = db.inTransaction() }
        assertTrue(store.save(row("new"), "body", true) { false })
        assertFalse(inTransaction)
        assertEquals(1, calls)
        assertFalse(store.save(row("new"), "body", true) { false })
        assertFalse(store.save(row("suppressed"), "body", true) { true })
        assertTrue(store.save(row("history").copy(notificationPending = false), "body", false) { false })
        assertEquals(1, calls)
        val failing = IncomingMessageStore(db) { error("synthetic scheduler failure") }
        assertTrue(failing.save(row("retained"), "body", true) { false })
        assertTrue(db.messageDao().getMessageById("retained")!!.notificationPending)
    }
}
