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
    private fun row(id: String, status: String = "PENDING") = MessageEntity(
        id = id, chatId = "chat", senderId = "self", content = "private $id",
        timestamp = 1L, isFromMe = true, status = status, recipientId = "pk_peer",
    )

    @Test fun targetedWorkIsBoundedAndNeverRetriesTheSameRowTwiceInOnePass() = runBlocking {
        val pending = (0 until 100).map { row("p$it") }
        val recent = pending.take(20) + (0 until 70).map { row("s$it", "SENT") }
        val calls = mutableListOf<String>()
        val marked = mutableListOf<String>()
        val helper = PeerOutboxRetry(
            loadPending = { peer, limit -> assertEquals("pk_peer", peer); assertEquals(20, limit); pending },
            loadRecent = { peer, limit -> assertEquals("pk_peer", peer); assertEquals(50, limit); recent },
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
            loadPending = { _, _ -> listOf(row("p")) },
            loadRecent = { _, _ -> recentLoads++; listOf(row("p"), row("s", "SENT")) },
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
            loadPending = { _, _ -> listOf(row("bad"), row("good")) },
            loadRecent = { _, _ -> listOf(row("later", "SENT")) },
            send = { id, _, _, _ -> calls += id; if (id == "bad") error("secret body must not be logged") else true },
            markSent = { throw IllegalStateException("secret") }, recordFailure = { errors++ }, nowMs = { 0 },
        )
        assertEquals(2, helper.retry("pk_peer"))
        assertEquals(listOf("bad", "good", "later"), calls)
        assertEquals(2, errors) // send and guarded update
    }

    @Test fun cancellationDoesNotLeavePeerBusyOrStartCooldown() = runBlocking {
        var reads = 0
        val helper = PeerOutboxRetry(
            loadPending = { _, _ -> emptyList() },
            loadRecent = { _, _ -> if (++reads == 1) throw CancellationException("cancel") else listOf(row("s", "SENT")) },
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
            loadPending = { peer, _ -> listOf(row(peer)) },
            loadRecent = { _, _ -> emptyList() },
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

    @Test fun concurrentPeerWorkHasAHardLimitAndReleasesItOnCancellation() = runBlocking {
        val blocked = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val helper = PeerOutboxRetry(
            loadPending = { peer, _ -> listOf(row(peer)) }, loadRecent = { _, _ -> emptyList() },
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
