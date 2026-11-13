package com.vladimir.messenger.data.repository

import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.data.mirror.MirrorPendingFlusher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real scanner and scheduling policy with in-memory rows and a fake paired transport. */
class MirrorPendingScheduleIntegrationTest {
    private fun row(id: String) = MessageEntity(
        id = id, chatId = "chat", senderId = "self", content = "answer", timestamp = 42,
        isFromMe = true, status = "PENDING", recipientId = "pk_friend123",
        replyToId = "quoted", replyAuthor = "Alice", replyText = "quote",
    )

    private fun scanner(rows: MutableMap<String, MessageEntity>, cleared: (String) -> Boolean) = MirrorPendingOutgoing(
        countAtStart = { rows.size },
        load = { after, limit -> rows.values.sortedBy { it.id }
            .filter { after == null || !OutboxPosition(it.timestamp, it.id).atOrBefore(after) }.take(limit) },
        find = { rows[it] }, chatName = { "Friend" }, isPersonalChat = { true },
        isCleared = { _, id -> cleared(id) }, preflight = { _, _, _ -> true },
        recordFailure = { throw AssertionError("unexpected", it) },
    )

    @Test fun periodicPagesReachAQuoteBeyondBlockedRowsAndRetryALostOffer() = runBlocking {
        val rows = ((0 until 160).map { row("blocked-%03d".format(it)) } + row("later"))
            .associateByTo(linkedMapOf()) { it.id }
        val pending = scanner(rows) { it.startsWith("blocked-") }
        var now = 0L
        val offered = mutableListOf<String>()
        val flusher = MirrorPendingFlusher(this, { true }, pending::page,
            { frame -> offered += "${frame.id}:${frame.replyToId}:${frame.replyText}"; false },
            { throw AssertionError("unexpected", it) }, { now })
        repeat(3) { flusher.request(); yield(); now += 20_000 }
        assertTrue(offered.isEmpty())
        flusher.request(); yield()
        assertEquals(listOf("later:quoted:quote"), offered)
        repeat(4) { now += 20_000; flusher.request(); yield() }
        assertEquals(listOf("later:quoted:quote", "later:quoted:quote"), offered)
        assertEquals("PENDING", rows.getValue("later").status)
    }

    @Test fun newLocalWorkInterruptsIdleBackoffWithoutPretendingItWasDelivered() = runBlocking {
        val rows = linkedMapOf<String, MessageEntity>()
        val pending = scanner(rows) { false }
        var now = 0L
        val offered = mutableListOf<String>()
        val flusher = MirrorPendingFlusher(this, { true }, pending::page,
            { offered += it.id; true }, { throw AssertionError("unexpected", it) }, { now })
        flusher.request(); yield()
        now = 20_000; flusher.request(); yield()
        now = 60_000; flusher.request(); yield()
        rows["new"] = row("new")
        now = 80_000; flusher.request(); yield()
        assertTrue(offered.isEmpty())
        flusher.noteNewWork()
        flusher.request(); yield()
        assertEquals(listOf("new"), offered)
        assertEquals("PENDING", rows.getValue("new").status)
    }
}
