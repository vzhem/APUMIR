package com.vladimir.messenger.service

import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.util.QuietMessage
import com.vladimir.messenger.util.SosMessage
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingNotificationProcessorTest {
    private fun row(id: String, content: String, recipient: String = "me", timestamp: Long = 1L) = MessageEntity(
        id = id, chatId = "chat", senderId = "friend", content = content,
        timestamp = timestamp, recipientId = recipient, notificationPending = true,
    )

    private class Fixture {
        val rows = ConcurrentHashMap<String, MessageEntity>()
        val active = ConcurrentHashMap<String, String>()
        val alerts = Collections.synchronizedList(mutableListOf<Pair<String, IncomingAlert>>())
        val cancelled = Collections.synchronizedList(mutableListOf<String>())
        val current = AtomicBoolean(true)
        var onAlert: suspend (MessageEntity, IncomingAlert) -> IncomingNotificationPost = { message, kind ->
            alerts += message.id to kind
            active[message.id] = IncomingNotificationSnapshot.of("me", message)
            IncomingNotificationPost.POSTED
        }
        var onComplete: suspend (MessageEntity) -> Boolean = { message ->
            rows.replace(message.id, message, message.copy(notificationPending = false))
        }

        fun processor() = IncomingNotificationProcessor(
            recipientId = "me",
            load = { rows[it] },
            complete = { onComplete(it) },
            isSuppressed = { it.id == "deferred" || it.id == "cleared" },
            isActive = { active[it.id] == IncomingNotificationSnapshot.of("me", it) },
            alert = { message, kind -> onAlert(message, kind) },
            cancel = { message ->
                if (active.remove(message.id, IncomingNotificationSnapshot.of("me", message))) cancelled += message.id
            },
            current = { current.get() },
        )
    }

    @Test fun delayedQuietSosAndOrdinaryMessagesUseTheCorrectPathsOnce() = runBlocking {
        val f = Fixture()
        listOf(row("old", "hello", timestamp = 1), row("future", "hello", timestamp = Long.MAX_VALUE),
            row("quiet", QuietMessage.wrap("shh")), row("sos", SosMessage.wrap("help")))
            .forEach { f.rows[it.id] = it }
        val processor = f.processor()
        listOf("old", "old", "future", "quiet", "sos").forEach { processor.process(it) }
        assertEquals(listOf("old" to IncomingAlert.NORMAL, "future" to IncomingAlert.NORMAL,
            "quiet" to IncomingAlert.QUIET, "sos" to IncomingAlert.SOS), f.alerts)
        assertTrue(f.rows.values.none { it.notificationPending })
    }

    @Test fun deferredClearedAndServiceOrMediaPartsAreConsumedWithoutAlerts() = runBlocking {
        val f = Fixture()
        listOf(row("deferred", "not yet"), row("cleared", "deleted"),
            row("part", "APUIMGP1:1/1/1:abc"), row("service", "APUGIF1|ask"))
            .forEach { f.rows[it.id] = it }
        val processor = f.processor()
        f.rows.keys.toList().forEach { processor.process(it) }
        assertTrue(f.rows.values.none { it.notificationPending })
        assertTrue(f.alerts.isEmpty())
    }

    @Test fun systemBlockedLeavesPendingWhileIntentionalMuteIsCompleted() = runBlocking {
        val f = Fixture()
        f.rows["blocked"] = row("blocked", "hello")
        f.rows["muted"] = row("muted", "hello")
        f.onAlert = { _, _ -> IncomingNotificationPost.BLOCKED }
        assertEquals(IncomingNotificationProcessor.Result.BLOCKED, f.processor().process("blocked"))
        assertTrue(f.rows["blocked"]!!.notificationPending)
        f.onAlert = { _, _ -> IncomingNotificationPost.SUPPRESSED }
        assertEquals(IncomingNotificationProcessor.Result.DONE, f.processor().process("muted"))
        assertFalse(f.rows["muted"]!!.notificationPending)
        assertTrue(f.active.isEmpty())
        f.onAlert = { message, kind ->
            f.alerts += message.id to kind
            f.active[message.id] = IncomingNotificationSnapshot.of("me", message)
            IncomingNotificationPost.POSTED
        }
        assertEquals(IncomingNotificationProcessor.Result.DONE, f.processor().process("blocked"))
        assertFalse(f.rows["blocked"]!!.notificationPending)
        assertEquals(listOf("blocked" to IncomingAlert.NORMAL), f.alerts)
    }

    @Test fun failureBeforePostingLeavesWorkForRetry() = runBlocking {
        val f = Fixture()
        f.rows["id"] = row("id", "hello")
        val good = f.onAlert
        f.onAlert = { _, _ -> error("synthetic failure before notify") }
        assertTrue(runCatching { f.processor().process("id") }.isFailure)
        assertTrue(f.rows["id"]!!.notificationPending)
        f.onAlert = good
        assertEquals(IncomingNotificationProcessor.Result.DONE, f.processor().process("id"))
        assertFalse(f.rows["id"]!!.notificationPending)
        assertEquals(listOf("id" to IncomingAlert.NORMAL), f.alerts)
    }

    @Test fun activePostRepairsCrashBeforeRoomCompletionWithoutAlertingTwice() = runBlocking {
        val f = Fixture()
        f.rows["id"] = row("id", "hello")
        val finish = f.onComplete
        f.onComplete = { error("synthetic crash after notify") }
        assertTrue(runCatching { f.processor().process("id") }.isFailure)
        assertTrue(f.rows["id"]!!.notificationPending)
        assertTrue(f.active.containsKey("id"))
        f.onComplete = finish
        assertEquals(IncomingNotificationProcessor.Result.DONE, f.processor().process("id"))
        assertEquals(1, f.alerts.size)
        assertFalse(f.rows["id"]!!.notificationPending)
    }

    @Test fun dismissedPostInCrashWindowCanBePostedAgainRatherThanLost() = runBlocking {
        val f = Fixture()
        f.rows["id"] = row("id", "hello")
        val finish = f.onComplete
        f.onComplete = { error("crash") }
        assertTrue(runCatching { f.processor().process("id") }.isFailure)
        f.active.clear() // Android or the user dismissed it before recovery.
        f.onComplete = finish
        assertEquals(IncomingNotificationProcessor.Result.DONE, f.processor().process("id"))
        assertEquals(2, f.alerts.size)
    }

    @Test fun activeOldBodyOrDifferentProfileCannotCompleteANewSnapshot() = runBlocking {
        val f = Fixture()
        val changed = row("id", "after")
        f.rows["id"] = changed
        f.active["id"] = IncomingNotificationSnapshot.of("me", changed.copy(content = "before"))
        assertEquals(IncomingNotificationProcessor.Result.DONE, f.processor().process("id"))
        assertEquals(listOf("id" to IncomingAlert.NORMAL), f.alerts)
        f.rows["id"] = changed
        f.active["id"] = IncomingNotificationSnapshot.of("another-profile", changed)
        assertEquals(IncomingNotificationProcessor.Result.DONE, f.processor().process("id"))
        assertEquals(2, f.alerts.size)
    }

    @Test fun snapshotSeparatesEveryIdentityAndShownFieldIncludingNullTopics() {
        val original = row("id", "a|b")
        val token = IncomingNotificationSnapshot.of("me", original)
        assertEquals(token, IncomingNotificationSnapshot.of("me", original.copy()))
        assertEquals(64, token.length)
        assertNotEquals(token, IncomingNotificationSnapshot.of("other", original))
        listOf(original.copy(id = "other"), original.copy(chatId = "other"),
            original.copy(senderId = "other"), original.copy(recipientId = "other"),
            original.copy(content = "a|c"), original.copy(timestamp = 2), original.copy(topicId = ""), original.copy(channel = "GROUP"),
        ).forEach { assertNotEquals(token, IncomingNotificationSnapshot.of("me", it)) }
        assertNotEquals(IncomingNotificationSnapshot.of("me", original.copy(chatId = "a|b", senderId = "c")),
            IncomingNotificationSnapshot.of("me", original.copy(chatId = "a", senderId = "b|c")))
    }

    @Test fun cancelledAfterNotifyKeepsFlagAndASecondProcessorRepairsIt() = runBlocking {
        val f = Fixture()
        f.rows["id"] = row("id", "hello")
        f.onAlert = { message, kind ->
            f.alerts += message.id to kind
            f.active[message.id] = IncomingNotificationSnapshot.of("me", message)
            currentCoroutineContext().cancel(CancellationException("synthetic process stop"))
            IncomingNotificationPost.POSTED
        }
        val cancelled = async { f.processor().process("id") }
        try { cancelled.await() } catch (_: CancellationException) { /* expected stop */ }
        assertTrue(cancelled.isCancelled)
        assertTrue(f.rows["id"]!!.notificationPending)
        assertEquals(IncomingNotificationProcessor.Result.DONE, f.processor().process("id"))
        assertEquals(1, f.alerts.size)
        assertFalse(f.rows["id"]!!.notificationPending)
    }

    @Test fun serviceAndWorkerCannotBothPostTheSamePendingRowInOneProcess() = runBlocking {
        val f = Fixture()
        f.rows["id"] = row("id", "hello")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.onAlert = { message, kind ->
            f.alerts += message.id to kind
            f.active[message.id] = IncomingNotificationSnapshot.of("me", message)
            entered.complete(Unit)
            release.await()
            IncomingNotificationPost.POSTED
        }
        coroutineScope {
            val first = async(Dispatchers.Default) { f.processor().process("id") }
            entered.await()
            val rest = (1..15).map { async(Dispatchers.Default) { f.processor().process("id") } }
            release.complete(Unit)
            first.await()
            rest.awaitAll()
        }
        assertEquals(listOf("id" to IncomingAlert.NORMAL), f.alerts)
        assertFalse(f.rows["id"]!!.notificationPending)
    }

    @Test fun changedMessageCancelsOnlyItsPostAndStaysPending() = runBlocking {
        val f = Fixture()
        f.rows["id"] = row("id", "before")
        val good = f.onAlert
        f.onAlert = { message, kind ->
            f.alerts += message.id to kind
            f.active[message.id] = IncomingNotificationSnapshot.of("me", message)
            f.rows[message.id] = message.copy(content = "after")
            IncomingNotificationPost.POSTED
        }
        assertEquals(IncomingNotificationProcessor.Result.RETRY, f.processor().process("id"))
        assertEquals(listOf("id"), f.cancelled)
        assertEquals("after", f.rows["id"]!!.content)
        assertTrue(f.rows["id"]!!.notificationPending)
        f.onAlert = good
        assertEquals(IncomingNotificationProcessor.Result.DONE, f.processor().process("id"))
        assertFalse(f.rows["id"]!!.notificationPending)
        assertEquals(2, f.alerts.size)
    }

    @Test fun staleCompletionDoesNotCancelANewerSnapshotOfTheSameId() = runBlocking {
        val f = Fixture()
        f.rows["id"] = row("id", "before")
        f.onAlert = { message, _ ->
            val newer = message.copy(content = "after")
            f.rows[message.id] = newer
            f.active[message.id] = IncomingNotificationSnapshot.of("me", newer)
            IncomingNotificationPost.POSTED
        }
        assertEquals(IncomingNotificationProcessor.Result.RETRY, f.processor().process("id"))
        assertTrue(f.cancelled.isEmpty())
        assertTrue(f.active.containsKey("id"))
        assertTrue(f.rows["id"]!!.notificationPending)
        assertEquals(IncomingNotificationProcessor.Result.DONE, f.processor().process("id"))
        assertFalse(f.rows["id"]!!.notificationPending)
    }

    @Test fun profileChangeDuringPostCancelsAndDoesNotComplete() = runBlocking {
        val f = Fixture()
        f.rows["id"] = row("id", "hello")
        val good = f.onAlert
        f.onAlert = { message, kind ->
            f.alerts += message.id to kind
            f.active[message.id] = IncomingNotificationSnapshot.of("me", message)
            f.current.set(false)
            IncomingNotificationPost.POSTED
        }
        assertEquals(IncomingNotificationProcessor.Result.RETRY, f.processor().process("id"))
        assertTrue(f.rows["id"]!!.notificationPending)
        assertEquals(listOf("id"), f.cancelled)
        f.current.set(true)
        f.onAlert = good
        assertEquals(IncomingNotificationProcessor.Result.DONE, f.processor().process("id"))
    }

    @Test fun otherRecipientsOutgoingAndMissingRowsAreNeverPostedOrClaimed() = runBlocking {
        val f = Fixture()
        f.rows["other"] = row("other", "hello", recipient = "other")
        f.rows["outgoing"] = row("outgoing", "hello").copy(isFromMe = true)
        listOf("other", "outgoing", "missing").forEach {
            assertEquals(IncomingNotificationProcessor.Result.DONE, f.processor().process(it))
        }
        assertTrue(f.rows.values.all { it.notificationPending })
        assertTrue(f.alerts.isEmpty())
        f.current.set(false)
        assertEquals(IncomingNotificationProcessor.Result.RETRY, f.processor().process("other"))
    }
}
