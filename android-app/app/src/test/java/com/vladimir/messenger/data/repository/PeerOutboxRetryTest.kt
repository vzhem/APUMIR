package com.vladimir.messenger.data.repository

import com.vladimir.messenger.data.local.entity.MessageEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerOutboxRetryTest {
    private fun row(id: String, status: String = "PENDING", recipient: String = "pk_peer") = MessageEntity(
        id = id, chatId = "chat", senderId = "self", content = "private $id",
        timestamp = 1L, isFromMe = true, status = status, recipientId = recipient,
    )

    @Test fun targetedWorkIsBoundedAndNeverRetriesTheSameRowTwiceInOnePass() = runBlocking {
        val pending = (0 until 100).map { row("p$it") }
        val recent = pending.take(20) + (0 until 70).map { row("s$it", "SENT") }
        val calls = mutableListOf<String>()
        val marked = mutableListOf<String>()
        val helper = PeerOutboxRetry(
            loadPending = { peer, _, limit -> assertEquals("pk_peer", peer); assertEquals(20, limit); pending },
            loadRecent = { peer, _, limit -> assertEquals("pk_peer", peer); assertEquals(50, limit); recent },
            loadCurrent = { id -> (pending + recent).firstOrNull { it.id == id } },
            chatContact = { "pk_peer" },
            send = { id, _, peer, _ -> assertEquals("pk_peer", peer); calls += id; true },
            markSent = { marked += it }, recordFailure = { error("unexpected error") }, nowMs = { 0 },
        )
        assertEquals(50, helper.retry("pk_peer"))
        assertEquals(50, calls.size)
        assertEquals(calls.size, calls.distinct().size)
        assertEquals((0 until 20).map { "p$it" }, marked)
    }

    @Test fun cooldownAppliesOnlyToRecentSyncAndUsesMonotonicTime() = runBlocking {
        var clock = 1000L
        var recentLoads = 0
        val calls = mutableListOf<String>()
        val marked = mutableListOf<String>()
        val helper = PeerOutboxRetry(
            loadPending = { _, _, _ -> listOf(row("p")) },
            loadRecent = { _, _, _ -> recentLoads++; listOf(row("p"), row("s", "SENT")) },
            loadCurrent = { id -> row(id, if (id == "s") "SENT" else "PENDING") },
            chatContact = { "pk_peer" },
            send = { id, _, _, _ -> calls += id; true },
            markSent = { marked += it }, recordFailure = { error("unexpected error") }, nowMs = { clock },
        )
        assertEquals(2, helper.retry("pk_peer"))
        clock += 299_999
        assertEquals(1, helper.retry("pk_peer"))
        clock++
        assertEquals(2, helper.retry("pk_peer"))
        assertEquals(2, recentLoads)
        assertEquals(listOf("p", "s", "p", "p", "s"), calls)
        assertEquals(listOf("p", "p", "p"), marked)
        // A reset or wrap of the monotonic source permits a fresh sync.
        clock = 1
        assertEquals(2, helper.retry("pk_peer"))
        assertEquals(3, recentLoads)
    }

    @Test fun failuresAreReportedWithoutStoppingLaterRows() = runBlocking {
        val calls = mutableListOf<String>()
        var errors = 0
        val helper = PeerOutboxRetry(
            loadPending = { _, _, _ -> listOf(row("bad"), row("good")) },
            loadRecent = { _, _, _ -> listOf(row("later", "SENT")) },
            loadCurrent = { id -> row(id, if (id == "later") "SENT" else "PENDING") },
            chatContact = { "pk_peer" },
            send = { id, _, _, _ -> calls += id; if (id == "bad") error("secret body must not be logged") else true },
            markSent = { throw IllegalStateException("secret") }, recordFailure = { errors++ }, nowMs = { 0 },
        )
        assertEquals(2, helper.retry("pk_peer"))
        assertEquals(listOf("bad", "good", "later"), calls)
        assertEquals(2, errors) // send and guarded update
    }

    @Test fun discoveryRereadsRowsAndNeverSendsToADifferentSavedRecipient() = runBlocking {
        val snapshots = listOf(
            row("first"), row("ack"), row("deleted"), row("advanced"), row("incoming"),
            row("wrong", recipient = "pk_other"), row("bad", recipient = "not-a-node"),
            row("legacy-wrong", recipient = "apu://add?node=pk_other&n=x"),
            row("blank", recipient = ""), row("blank-other", recipient = "").copy(chatId = "other"),
            row("legacy", recipient = "apu://add?node=pk_peer&n=x"), row("edited"),
        )
        val current = snapshots.associateBy { it.id }.toMutableMap()
        val sent = mutableListOf<Pair<String, String>>()
        val helper = PeerOutboxRetry(
            loadPending = { _, _, _ -> snapshots }, loadRecent = { _, _, _ -> emptyList() },
            loadCurrent = { current[it] }, chatContact = { if (it == "other") "pk_other" else "pk_peer" },
            send = { id, _, peer, body ->
                assertEquals("pk_peer", peer)
                sent += id to body
                if (id == "first") {
                    current["ack"] = current.getValue("ack").copy(status = "DELIVERED")
                    current.remove("deleted")
                    current["advanced"] = current.getValue("advanced").copy(status = "SENT")
                    current["incoming"] = current.getValue("incoming").copy(isFromMe = false)
                    current["edited"] = current.getValue("edited").copy(content = "latest")
                }
                true
            },
            markSent = {}, recordFailure = { error("unexpected error") }, nowMs = { 0 },
        )
        assertEquals(4, helper.retry("pk_peer"))
        assertEquals(listOf("first", "blank", "legacy", "edited"), sent.map { it.first })
        assertEquals("latest", sent.last().second)
    }

    @Test fun recentSyncAlsoRereadsRowsAndDoesNotRetargetOnDiscovery() = runBlocking {
        val snapshots = listOf(row("first", "SENT"), row("acked", "SENT"), row("deleted", "SENT"),
            row("wrong", "SENT", "pk_other"), row("right", "SENT", "pk_peer"))
        val current = snapshots.associateBy { it.id }.toMutableMap()
        val sent = mutableListOf<String>()
        val helper = PeerOutboxRetry(
            loadPending = { _, _, _ -> emptyList() }, loadRecent = { _, _, _ -> snapshots },
            loadCurrent = { current[it] }, chatContact = { "pk_peer" },
            send = { id, _, _, _ ->
                sent += id
                if (id == "first") { current["acked"] = current.getValue("acked").copy(status = "READ"); current.remove("deleted") }
                true
            },
            markSent = {}, recordFailure = { error("unexpected error") }, nowMs = { 0 },
        )
        assertEquals(2, helper.retry("pk_peer"))
        assertEquals(listOf("first", "right"), sent)
    }

    @Test fun failedCurrentReadIsReportedAndDoesNotStopLaterRows() = runBlocking {
        var errors = 0
        val sent = mutableListOf<String>()
        val helper = PeerOutboxRetry(
            loadPending = { _, _, _ -> listOf(row("bad"), row("good")) }, loadRecent = { _, _, _ -> emptyList() },
            loadCurrent = { if (it == "bad") error("secret") else row(it) }, chatContact = { null },
            send = { id, _, _, _ -> sent += id; true }, markSent = {}, recordFailure = { errors++ }, nowMs = { 0 },
        )
        assertEquals(1, helper.retry("pk_peer"))
        assertEquals(listOf("good"), sent)
        assertEquals(1, errors)
    }

    @Test fun cancellationDoesNotLeavePeerBusyOrStartCooldown() = runBlocking {
        var reads = 0
        val helper = PeerOutboxRetry(
            loadPending = { _, _, _ -> emptyList() },
            loadRecent = { _, _, _ -> if (++reads == 1) throw CancellationException("cancel") else listOf(row("s", "SENT")) },
            loadCurrent = { row(it, "SENT") }, chatContact = { "pk_peer" },
            send = { _, _, _, _ -> true }, markSent = {}, recordFailure = { error("unexpected error") }, nowMs = { 0 },
        )
        val failure = runCatching { helper.retry("pk_peer") }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(1, helper.retry("pk_peer"))
        assertEquals(2, reads)
    }

    @Test fun samePeerCannotRunConcurrentlyButAnotherPeerCanProceed() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val helper = PeerOutboxRetry(
            loadPending = { peer, _, _ -> listOf(row(peer, recipient = peer)) },
            loadRecent = { _, _, _ -> emptyList() },
            loadCurrent = { id -> row(id, recipient = id) }, chatContact = { null },
            send = { id, _, _, _ ->
                calls += id
                if (id == "pk_one") { entered.complete(Unit); release.await() }
                true
            },
            markSent = {}, recordFailure = { error("unexpected error") }, nowMs = { 0 },
        )
        val first = async { helper.retry("pk_one") }
        entered.await()
        assertEquals(0, helper.retry("pk_one"))
        assertEquals(1, helper.retry("pk_two"))
        assertFalse(first.isCompleted)
        release.complete(Unit)
        assertEquals(1, first.await())
        assertEquals(listOf("pk_one", "pk_two"), calls)
    }

    @Test fun clearedPendingWindowsRotateAcrossDiscoveriesAndEventuallyWrap() = runBlocking {
        val rows = (0 until 65).map { row("m%03d".format(it)) }
        val byId = rows.associateBy { it.id }
        val network = mutableListOf<String>()
        var inspected = 0
        val starts = mutableListOf<String?>()
        val helper = PeerOutboxRetry(
            loadPending = { _, after, limit ->
                starts += after?.id
                rows.filter { after == null || !OutboxPosition(it.timestamp, it.id).atOrBefore(after) }.take(limit)
            },
            loadRecent = { _, _, _ -> emptyList() },
            loadCurrent = { inspected++; byId[it] }, chatContact = { "pk_peer" },
            // null represents an automatic-send preflight suppression; no transport call.
            send = { id, _, _, _ -> if (id < "m060") null else { network += id; true } },
            markSent = {}, recordFailure = { error("unexpected error") }, nowMs = { 0 },
        )
        repeat(3) {
            val before = inspected
            assertEquals(0, helper.retry("pk_peer"))
            assertEquals(20, inspected - before)
        }
        assertEquals(5, helper.retry("pk_peer"))
        assertEquals((60 until 65).map { "m%03d".format(it) }, network)
        assertEquals(listOf(null, "m019", "m039", "m059"), starts)
        assertEquals(0, helper.retry("pk_peer"))
        assertEquals(null, starts.last()) // a short last page wraps the next discovery
    }

    @Test fun recentWindowResumesAfterTheSharedVisitBudget() = runBlocking {
        val pending = (0 until 20).map { row("p%03d".format(it)) }
        val recent = (0 until 80).map { row("s%03d".format(it), "SENT") }.reversed()
        val byId = (pending + recent).associateBy { it.id }
        var clock = 0L
        val sent = mutableListOf<String>()
        val helper = PeerOutboxRetry(
            loadPending = { _, _, limit -> pending.take(limit) },
            loadRecent = { _, before, limit ->
                recent.filter { before == null || OutboxPosition(it.timestamp, it.id) != before &&
                    OutboxPosition(it.timestamp, it.id).atOrBefore(before) }.take(limit)
            },
            loadCurrent = { byId[it] }, chatContact = { "pk_peer" },
            send = { id, _, _, _ -> sent += id; true }, markSent = {},
            recordFailure = { error("unexpected error") }, nowMs = { clock },
        )
        assertEquals(50, helper.retry("pk_peer"))
        assertEquals((50 until 80).reversed().map { "s%03d".format(it) }, sent.filter { it.startsWith("s") })
        clock += 300_000
        assertEquals(50, helper.retry("pk_peer"))
        assertEquals((20 until 80).reversed().map { "s%03d".format(it) }, sent.filter { it.startsWith("s") })
        clock += 300_000
        assertEquals(40, helper.retry("pk_peer"))
        assertEquals((0 until 80).reversed().map { "s%03d".format(it) }, sent.filter { it.startsWith("s") })
        clock += 300_000
        assertEquals(50, helper.retry("pk_peer"))
        assertEquals("s079", sent.filter { it.startsWith("s") }[80])
    }

    @Test fun cancelledAttemptDoesNotAdvancePastItsPendingRow() = runBlocking {
        val rows = (0 until 5).map { row("m$it") }
        var cancel = true
        val calls = mutableListOf<String>()
        val starts = mutableListOf<String?>()
        val helper = PeerOutboxRetry(
            loadPending = { _, after, limit ->
                starts += after?.id
                rows.filter { after == null || !OutboxPosition(it.timestamp, it.id).atOrBefore(after) }.take(limit)
            },
            loadRecent = { _, _, _ -> emptyList() }, loadCurrent = { id -> rows.firstOrNull { it.id == id } },
            chatContact = { "pk_peer" },
            send = { id, _, _, _ ->
                calls += id
                if (id == "m1" && cancel) throw CancellationException("cancel")
                true
            },
            markSent = {}, recordFailure = { error("unexpected error") }, nowMs = { 0 },
        )
        assertTrue(runCatching { helper.retry("pk_peer") }.exceptionOrNull() is CancellationException)
        cancel = false
        assertEquals(4, helper.retry("pk_peer"))
        assertEquals(listOf(null, "m0"), starts)
        assertEquals(listOf("m0", "m1", "m1", "m2", "m3", "m4"), calls)
    }

    @Test fun concurrentPeerWorkHasAHardLimitAndReleasesItOnCancellation() = runBlocking {
        val blocked = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val helper = PeerOutboxRetry(
            loadPending = { peer, _, _ -> listOf(row(peer, recipient = peer)) }, loadRecent = { _, _, _ -> emptyList() },
            loadCurrent = { id -> row(id, recipient = id) }, chatContact = { null },
            send = { id, _, _, _ -> started += id; blocked.await(); true },
            markSent = {}, recordFailure = { error("unexpected error") }, nowMs = { 0 },
        )
        val jobs = (0 until 16).map { n -> async { helper.retry("pk_$n") } }
        while (started.size < 16) yield()
        assertEquals(0, helper.retry("pk_extra"))
        jobs.first().cancel()
        runCatching { jobs.first().await() }
        val extra = async { helper.retry("pk_extra") }
        while (started.size < 17) yield()
        blocked.complete(Unit)
        assertEquals(1, extra.await())
        jobs.drop(1).forEach { assertEquals(1, it.await()) }
    }
}
