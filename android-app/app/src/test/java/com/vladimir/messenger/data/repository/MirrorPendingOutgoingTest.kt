package com.vladimir.messenger.data.repository

import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.data.reply.DirectReplyWire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorPendingOutgoingTest {
    private val target = DirectReplyWire.Target("quoted", "Alice", "first line\n second line")

    private fun row(id: String = "parent") = MessageEntity(
        id = id, chatId = "shadow-chat", senderId = "self", content = "answer", timestamp = 42,
        isFromMe = true, status = "PENDING", recipientId = "pk_friend123",
        replyToId = target.messageId, replyAuthor = target.author,
        replyText = DirectReplyWire.preview(target.text), textColor = "red", senderNameColor = "blue",
    )

    private fun pending(
        snapshots: List<MessageEntity>,
        find: suspend (String) -> MessageEntity? = { id -> snapshots.firstOrNull { it.id == id } },
        chat: suspend (String) -> String? = { "Friend" },
        personal: suspend (String) -> Boolean = { true },
        cleared: (String, String) -> Boolean = { _, _ -> false },
        preflight: suspend (String, String, String) -> Boolean = { _, _, _ -> true },
        failures: MutableList<Exception> = mutableListOf(),
        requested: MutableList<Int> = mutableListOf(),
    ) = MirrorPendingOutgoing(
        countAtStart = { snapshots.size },
        load = { after, limit ->
            requested += limit
            snapshots.sortedWith(compareBy<MessageEntity> { it.timestamp }.thenBy { it.id })
                .filter { after == null || !OutboxPosition(it.timestamp, it.id).atOrBefore(after) }
                .take(limit)
        },
        find = find, chatName = chat, isPersonalChat = personal, isCleared = cleared, preflight = preflight,
        recordFailure = { failures += it },
    )

    @Test fun pendingFramesKeepStoredReplyColorsAndRoutingFields() = runBlocking {
        val original = row().copy(status = "QUEUED_OFFLINE")
        val frame = pending(listOf(original)).page(50).rows.single()
        assertEquals(original.id, frame.id)
        assertEquals(original.chatId, frame.chatId)
        assertEquals("Friend", frame.contactName)
        assertEquals("self", frame.senderId)
        assertTrue(frame.mine)
        assertEquals(original.recipientId, frame.recipientId)
        assertEquals(original.content, frame.content)
        assertEquals(original.timestamp, frame.timestamp)
        assertEquals("QUEUED_OFFLINE", frame.status)
        assertEquals("quoted", frame.replyToId)
        assertEquals("Alice", frame.replyAuthor)
        assertEquals("first line second line", frame.replyText)
        assertEquals("red", frame.textColor)
        assertEquals("blue", frame.senderNameColor)
        assertFalse(frame.manualRetry)
    }

    @Test fun absentTargetNeverExportsOrphanedQuoteText() = runBlocking {
        for (targetId in listOf(null, "", " \t")) {
            val frame = pending(listOf(row().copy(replyToId = targetId, replyAuthor = "stale", replyText = "private")))
                .page(50).rows.single()
            assertEquals("", frame.replyToId)
            assertEquals("", frame.replyAuthor)
            assertEquals("", frame.replyText)
            assertEquals("red", frame.textColor)
        }
    }

    @Test fun changedOrDeletedSnapshotsNeverProduceFramesAndDoNotBlockOthers() = runBlocking {
        val snapshots = (1..7).map { row("id-$it") }
        val changes = mapOf(
            "id-1" to snapshots[0].copy(content = "edited"),
            "id-2" to snapshots[1].copy(replyText = "edited quote"),
            "id-3" to snapshots[2].copy(status = "DELIVERED"),
            "id-4" to snapshots[3].copy(recipientId = "pk_other"),
            "id-5" to snapshots[4].copy(chatId = "other"),
        )
        val checked = mutableListOf<String>()
        val sender = pending(snapshots, find = { id ->
            if (id == "id-6") null else changes[id] ?: snapshots.first { it.id == id }
        }, preflight = { id, _, _ -> checked += id; true })
        assertEquals(listOf("id-7"), sender.page(50).rows.map { it.id })
        assertEquals(listOf("id-7"), checked)
    }

    @Test fun groupTopicIncomingTerminalMalformedAndMissingChatAreRejected() = runBlocking {
        val rows = listOf(
            row("group-channel").copy(channel = "GROUP"),
            row("topic").copy(topicId = "thread"),
            row("group-chat").copy(chatId = "group"),
            row("incoming").copy(isFromMe = false),
            row("delivered").copy(status = "DELIVERED"),
            row("sent").copy(status = "SENT"),
            row("bad-id|").copy(),
            row("bad-peer").copy(recipientId = "pk_friend\u0000bad"),
            row("missing-chat").copy(chatId = "missing"),
            row("good"),
        )
        val checks = mutableListOf<String>()
        val sender = pending(rows, chat = { if (it == "missing") null else "Friend" },
            personal = { it != "group" }, preflight = { id, _, _ -> checks += id; true })
        assertEquals(listOf("good"), sender.page(50).rows.map { it.id })
        assertEquals(listOf("good"), checks)
    }

    @Test fun rawAndCanonicalTombstonesAndParentPreflightFailClosed() = runBlocking {
        val alias = "https://invite/?node=pk_friend123&x=1"
        val original = row().copy(recipientId = alias)
        for (clearedPeer in listOf(alias, "pk_friend123")) {
            var checks = 0
            val sender = pending(listOf(original), cleared = { peer, id -> peer == clearedPeer && id == original.id },
                preflight = { _, _, _ -> checks++; true })
            assertTrue(sender.page(50).rows.isEmpty())
            assertEquals(0, checks)
        }
        val checks = mutableListOf<Triple<String, String, String>>()
        val denied = pending(listOf(original), preflight = { id, chat, peer -> checks += Triple(id, chat, peer); false })
        assertTrue(denied.page(50).rows.isEmpty())
        assertEquals(listOf(Triple("parent", "shadow-chat", "pk_friend123")), checks)
        val allowed = pending(listOf(original), preflight = { _, _, peer -> peer == "pk_friend123" })
        assertEquals(alias, allowed.page(50).rows.single().recipientId)
    }

    @Test fun perRowFailureIsReportedAndCancellationPropagates() = runBlocking {
        val first = row("first")
        val second = row("second")
        val failures = mutableListOf<Exception>()
        val sender = pending(listOf(first, second), find = { id ->
            if (id == "first") throw IllegalStateException("storage") else second
        }, failures = failures)
        assertEquals(listOf("second"), sender.page(50).rows.map { it.id })
        assertEquals(1, failures.size)
        val cancelled = pending(listOf(first), preflight = { _, _, _ -> throw CancellationException("cancel") }, failures = failures)
        assertTrue(runCatching { cancelled.page(50).rows }.exceptionOrNull() is CancellationException)
        assertEquals(1, failures.size)
    }

    @Test fun queryLimitIsBoundedAndNonPositiveLimitsDoNotRead() = runBlocking {
        val requested = mutableListOf<Int>()
        val sender = pending((0 until 60).map { row("id-%02d".format(it)) }, requested = requested)
        assertTrue(sender.page(-1).rows.isEmpty())
        assertTrue(sender.page(0).rows.isEmpty())
        assertTrue(requested.isEmpty())
        assertEquals(50, sender.page(500).rows.size)
        assertEquals(listOf(50), requested)
    }

    @Test fun blockedOldRowsCannotHideLaterRowsAndAreRevisitedAfterWrap() = runBlocking {
        val rows = (0 until 60).map { row("blocked-%02d".format(it)) } + row("later")
        val attempts = mutableListOf<String>()
        val sender = pending(rows, preflight = { id, _, _ -> attempts += id; id == "later" })
        repeat(3) {
            val page = sender.page(20)
            assertTrue(page.rows.isEmpty())
            assertTrue(page.moreToScan)
        }
        val last = sender.page(20)
        assertEquals(listOf("later"), last.rows.map { it.id })
        assertFalse(last.moreToScan)
        assertEquals(1, attempts.count { it == "blocked-00" })
        assertTrue(sender.page(20).rows.isEmpty())
        assertEquals(2, attempts.count { it == "blocked-00" })
    }

    @Test fun growingQueueCannotStopAnExistingPassFromWrapping() = runBlocking {
        val rows = mutableListOf(row("a"), row("b"), row("c"))
        val sender = pending(rows)
        assertEquals(listOf("a", "b"), sender.page(2).rows.map { it.id })
        rows += ('d'..'z').map { row(it.toString()) }
        // The starting count limits this pass, even though more rows appeared.
        assertEquals(listOf("c"), sender.page(2).rows.map { it.id })
        assertEquals(listOf("a", "b"), sender.page(2).rows.map { it.id })
        val next = mutableListOf<String>()
        repeat(12) { next += sender.page(2).rows.map { it.id } }
        assertTrue("z" in next)
    }

    @Test fun concurrentPageRequestsAdvanceTheSingleScanWithoutDuplicates() = runBlocking {
        val sender = pending((0 until 20).map { row("id-%02d".format(it)) })
        val results = coroutineScope { (1..4).map { async { sender.page(5).rows } }.awaitAll() }
        val ids = results.flatten().map { it.id }
        assertEquals(20, ids.size)
        assertEquals(20, ids.toSet().size)
    }
}
