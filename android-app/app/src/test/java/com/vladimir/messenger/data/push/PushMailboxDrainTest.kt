package com.vladimir.messenger.data.push

import com.vladimir.messenger.data.security.SealedWire
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake provider and commit callbacks; not evidence of Room, FFI or real delivery. */
class PushMailboxDrainTest {
    private fun hex(value: Int) = "%032x".format(value)
    private fun wire(value: Int): String {
        val key = ByteArray(204) { 1 }
        key[1] = 0; key[2] = 1
        return SealedWire.encode(key, ByteArray(12) { 2 }, ByteArray(17) { value.toByte() })
    }
    private fun item(value: Int) = PushMailboxItem(hex(value), wire(value), 1_700_000_000_000L, hex(value + 100))
    private fun page(vararg items: PushMailboxItem) =
        PushMailboxHttpClient.PullResult.Page(items.toList(), items.lastOrNull()?.id)
    private val accepted = PushMailboxHttpClient.AckResult.ACCEPTED

    @Test fun ackFollowsDurableCommitsAndUsesSortedServerReceipts() = runBlocking {
        val first = item(2)
        val second = item(1)
        val events = mutableListOf<String>()
        val drain = PushMailboxDrain(
            pull = { after ->
                events.add("pull:$after")
                if (after == "-") page(first, second) else page()
            },
            commit = { envelope ->
                events.add(if (envelope == first.envelope) "commit:2" else "commit:1")
                PushMailboxDrain.Commit.COMMITTED
            },
            ack = { receipts ->
                events.add("ack:" + receipts.joinToString(",") { it.id })
                assertEquals(listOf(second.receipt, first.receipt), receipts.map { it.receipt })
                accepted
            },
            current = { true }, recordFailure = { error("unexpected failure") },
        )
        assertEquals(PushMailboxDrain.Result.Completed, drain.run())
        assertEquals(listOf("pull:-", "commit:2", "commit:1", "ack:${second.id},${first.id}", "pull:${second.id}"), events)
    }

    @Test fun unprocessedItemsAreNeverAcknowledgedButLaterItemsAreStillVisited() = runBlocking {
        val first = item(1)
        val second = item(2)
        val third = item(3)
        val acked = mutableListOf<String>()
        val drain = PushMailboxDrain(
            pull = { after -> when (after) { "-" -> page(first, second); second.id -> page(third); else -> page() } },
            commit = { envelope -> if (envelope == first.envelope) PushMailboxDrain.Commit.RETRY else PushMailboxDrain.Commit.COMMITTED },
            ack = { receipts -> acked.addAll(receipts.map { it.id }); accepted },
            current = { true }, recordFailure = { error("unexpected failure") },
        )
        assertEquals(PushMailboxDrain.Result.Unprocessed, drain.run())
        assertEquals(listOf(second.id, third.id), acked)
    }

    @Test fun commitExceptionsAreNotAckedAndCancellationNeverAcknowledgesThePage() = runBlocking {
        val first = item(1)
        val second = item(2)
        var failures = 0
        val acked = mutableListOf<String>()
        val drain = PushMailboxDrain(
            pull = { if (it == "-") page(first, second) else page() },
            commit = { if (it == first.envelope) throw IOException("sensitive detail") else PushMailboxDrain.Commit.ALREADY_COMMITTED },
            ack = { receipts -> acked.addAll(receipts.map { it.id }); accepted },
            current = { true }, recordFailure = { failures++ },
        )
        assertEquals(PushMailboxDrain.Result.Unprocessed, drain.run())
        assertEquals(1, failures)
        assertEquals(listOf(second.id), acked)
        var acknowledgements = 0
        val cancelled = PushMailboxDrain(
            pull = { page(first, second) },
            commit = { if (it == second.envelope) throw CancellationException("stop") else PushMailboxDrain.Commit.COMMITTED },
            ack = { acknowledgements++; accepted },
            current = { true }, recordFailure = { error("must not log cancellation") },
        )
        assertTrue(runCatching { cancelled.run() }.exceptionOrNull() is CancellationException)
        assertEquals(0, acknowledgements)
    }

    @Test fun failedAckRetriesTheSameAlreadyCommittedMessageWithoutAdvancingCursor() = runBlocking {
        val first = item(1)
        var commits = 0
        var acknowledgements = 0
        val pulls = mutableListOf<String>()
        val drain = PushMailboxDrain(
            pull = { after -> pulls.add(after); if (after == "-") page(first) else page() },
            commit = { if (commits++ == 0) PushMailboxDrain.Commit.COMMITTED else PushMailboxDrain.Commit.ALREADY_COMMITTED },
            ack = { if (acknowledgements++ == 0) PushMailboxHttpClient.AckResult.UNAVAILABLE else accepted },
            current = { true }, recordFailure = { error("unexpected failure") },
        )
        assertEquals(PushMailboxDrain.Result.Retry, drain.run())
        assertEquals(listOf("-"), pulls)
        assertEquals(PushMailboxDrain.Result.Completed, drain.run())
        assertEquals(listOf("-", "-", first.id), pulls)
        assertEquals(2, commits)
        assertEquals(2, acknowledgements)
    }

    @Test fun changedTrustWithholdsTheAckEvenAfterACommit() = runBlocking {
        var current = false
        var pulls = 0
        var commits = 0
        var acknowledgements = 0
        val drain = PushMailboxDrain(
            pull = { pulls++; page(item(1)) },
            commit = { commits++; current = false; PushMailboxDrain.Commit.COMMITTED },
            ack = { acknowledgements++; accepted },
            current = { current }, recordFailure = { error("unexpected failure") },
        )
        assertEquals(PushMailboxDrain.Result.Retry, drain.run())
        assertEquals(0, pulls)
        current = true
        assertEquals(PushMailboxDrain.Result.Retry, drain.run())
        assertEquals(1, pulls)
        assertEquals(1, commits)
        assertEquals(0, acknowledgements)
    }

    @Test fun malformedPagesAreRejectedBeforeAnyItemIsCommitted() = runBlocking {
        val one = item(1)
        val two = item(2)
        val invalid = PushMailboxItem(one.id, "plaintext", one.expiresAt, one.receipt)
        val invalidReceipt = PushMailboxItem(one.id, one.envelope, one.expiresAt, "not-a-receipt")
        val invalidExpiry = PushMailboxItem(one.id, one.envelope, 0, one.receipt)
        val cases = listOf(
            PushMailboxHttpClient.PullResult.Page(emptyList(), one.id),
            PushMailboxHttpClient.PullResult.Page(listOf(one), two.id),
            page(one, one),
            page(one, two, item(3), item(4), item(5)),
            page(invalid), page(invalidReceipt), page(invalidExpiry),
        )
        for (bad in cases) {
            var commits = 0
            var acknowledgements = 0
            val drain = PushMailboxDrain(
                pull = { bad }, commit = { commits++; PushMailboxDrain.Commit.COMMITTED },
                ack = { acknowledgements++; accepted }, current = { true }, recordFailure = { error("unexpected failure") },
            )
            assertEquals(PushMailboxDrain.Result.Retry, drain.run())
            assertEquals(0, commits)
            assertEquals(0, acknowledgements)
        }
    }

    @Test fun repeatedIdsAcrossPagesAreRejectedWithoutProcessingThemAgain() = runBlocking {
        val one = item(1)
        val two = item(2)
        val processed = mutableListOf<String>()
        val drain = PushMailboxDrain(
            pull = { after -> if (after == "-") page(one, two) else page(one) },
            commit = { processed.add(it); PushMailboxDrain.Commit.COMMITTED },
            ack = { accepted }, current = { true }, recordFailure = { error("unexpected failure") },
        )
        assertEquals(PushMailboxDrain.Result.Retry, drain.run())
        assertEquals(listOf(one.envelope, two.envelope), processed)
    }

    @Test fun boundedCheckpointPreservesUnprocessedStateAndCursorExpiryRestarts() = runBlocking {
        val one = item(1)
        val two = item(2)
        val drain = PushMailboxDrain(
            pull = { after -> when (after) { "-" -> page(one); one.id -> page(two); else -> page() } },
            commit = { if (it == one.envelope) PushMailboxDrain.Commit.RETRY else PushMailboxDrain.Commit.COMMITTED },
            ack = { accepted }, current = { true }, recordFailure = { error("unexpected failure") }, maxPages = 1,
        )
        val first = drain.run() as PushMailboxDrain.Result.Continue
        assertEquals(one.id, first.checkpoint.after)
        assertTrue(first.checkpoint.unprocessed)
        assertFalse(first.toString().contains(one.id))
        assertFalse(first.checkpoint.toString().contains(one.id))
        val second = drain.run(first.checkpoint) as PushMailboxDrain.Result.Continue
        assertEquals(two.id, second.checkpoint.after)
        assertTrue(second.checkpoint.unprocessed)
        assertEquals(PushMailboxDrain.Result.Unprocessed, drain.run(second.checkpoint))
        assertNull(PushMailboxDrain.Checkpoint.resume("invalid", false, 1, listOf(one.id)))
        assertNull(PushMailboxDrain.Checkpoint.resume("0".repeat(32), false, 1, listOf(one.id)))
        val expired = PushMailboxDrain(
            pull = { PushMailboxHttpClient.PullResult.CursorExpired }, commit = { error("not called") },
            ack = { error("not called") }, current = { true }, recordFailure = { error("unexpected failure") },
        )
        assertEquals(PushMailboxDrain.Result.Restart, expired.run(second.checkpoint))
    }

    @Test fun boundedSnapshotSurvivesPageMutationAndRejectsUnboundedViews() = runBlocking {
        val one = item(1)
        val two = item(2)
        val mutable = mutableListOf(one, two)
        val processed = mutableListOf<String>()
        val drain = PushMailboxDrain(
            pull = { after -> if (after == "-") PushMailboxHttpClient.PullResult.Page(mutable, two.id) else page() },
            commit = { envelope -> mutable.clear(); processed.add(envelope); PushMailboxDrain.Commit.COMMITTED },
            ack = { accepted }, current = { true }, recordFailure = { error("unexpected failure") },
        )
        assertEquals(PushMailboxDrain.Result.Completed, drain.run())
        assertEquals(listOf(one.envelope, two.envelope), processed)

        var reads = 0
        var effects = 0
        val huge = object : AbstractList<PushMailboxItem>() {
            override val size: Int get() = Int.MAX_VALUE
            override fun get(index: Int): PushMailboxItem { reads++; return one }
        }
        val malformed = PushMailboxDrain(
            pull = { PushMailboxHttpClient.PullResult.Page(huge, one.id) },
            commit = { effects++; PushMailboxDrain.Commit.COMMITTED },
            ack = { effects++; accepted }, current = { true }, recordFailure = { error("unexpected failure") },
        )
        assertEquals(PushMailboxDrain.Result.Retry, malformed.run())
        assertTrue(reads <= PushMailboxProtocol.MAX_PAGE_ITEMS + 1)
        assertEquals(0, effects)
    }

    @Test fun recentIdsDetectCyclesAcrossDurableContinuationBoundaries() = runBlocking {
        val one = item(1)
        val two = item(2)
        val processed = mutableListOf<String>()
        val acknowledged = mutableListOf<String>()
        val drain = PushMailboxDrain(
            pull = { after -> when (after) { "-" -> page(one); one.id -> page(two); else -> page(one) } },
            commit = { processed.add(it); PushMailboxDrain.Commit.COMMITTED },
            ack = { receipts -> acknowledged.addAll(receipts.map { it.id }); accepted },
            current = { true }, recordFailure = { error("unexpected failure") }, maxPages = 1,
        )
        val first = drain.run() as PushMailboxDrain.Result.Continue
        val second = drain.run(first.checkpoint) as PushMailboxDrain.Result.Continue
        assertEquals(listOf(one.id, two.id), second.checkpoint.recent)
        assertEquals(2, second.checkpoint.pages)
        // A future store must persist and restore every field together.
        val restored = PushMailboxDrain.Checkpoint.resume(second.checkpoint.after, second.checkpoint.unprocessed,
            second.checkpoint.pages, second.checkpoint.recent.toList())!!
        assertEquals(PushMailboxDrain.Result.Retry, drain.run(restored))
        assertEquals(listOf(one.envelope, two.envelope), processed)
        assertEquals(listOf(one.id, two.id), acknowledged)
        assertFalse(second.checkpoint.toString().contains(one.id))
    }

    @Test fun longerCycleIsBoundedEvenAfterOldIdsLeaveTheCheckpoint() = runBlocking {
        val items = (1..65).map(::item)
        var pulls = 0
        var commits = 0
        val drain = PushMailboxDrain(
            pull = { after ->
                pulls++
                val next = if (after == "-") 0 else (items.indexOfFirst { it.id == after } + 1) % items.size
                page(items[next])
            },
            commit = { commits++; PushMailboxDrain.Commit.ALREADY_COMMITTED },
            ack = { accepted }, current = { true }, recordFailure = { error("unexpected failure") },
            maxPages = 1, maxScanPages = 66,
        )
        var checkpoint = PushMailboxDrain.Checkpoint.first()
        repeat(66) {
            val next = drain.run(checkpoint) as PushMailboxDrain.Result.Continue
            checkpoint = PushMailboxDrain.Checkpoint.resume(next.checkpoint.after, next.checkpoint.unprocessed,
                next.checkpoint.pages, next.checkpoint.recent)!!
            assertTrue(checkpoint.recent.size <= PushMailboxDrain.MAX_RECENT_IDS)
        }
        assertEquals(items.first().id, checkpoint.after) // 65-item cycle exceeds the recent history
        assertEquals(66, checkpoint.pages)
        assertEquals(PushMailboxDrain.Result.Restart, drain.run(checkpoint))
        assertEquals(66, pulls)
        assertEquals(66, commits)
    }

    @Test fun checkpointValidationAndTotalPageBudgetFailClosedWithoutMoreEffects() = runBlocking {
        val one = item(1)
        val two = item(2)
        val mutable = mutableListOf(one.id)
        val checkpoint = PushMailboxDrain.Checkpoint.resume(one.id, false, 1, mutable)!!
        mutable.add(two.id)
        assertEquals(listOf(one.id), checkpoint.recent) // persistence input cannot mutate the snapshot
        assertNull(PushMailboxDrain.Checkpoint.resume(one.id, false, 0, listOf(one.id)))
        assertNull(PushMailboxDrain.Checkpoint.resume(one.id, false, PushMailboxDrain.MAX_SCAN_PAGES + 1, listOf(one.id)))
        assertNull(PushMailboxDrain.Checkpoint.resume(one.id, false, 1, listOf(two.id)))
        assertNull(PushMailboxDrain.Checkpoint.resume(one.id, false, 1, listOf(one.id, one.id)))
        assertNull(PushMailboxDrain.Checkpoint.resume(item(5).id, false, 1, (1..5).map { item(it).id }))
        assertNull(PushMailboxDrain.Checkpoint.resume(item(65).id, false, 100, (1..65).map { item(it).id }))
        var pulls = 0
        var commits = 0
        val drain = PushMailboxDrain(
            pull = { after -> pulls++; if (after == "-") page(one) else page(two) },
            commit = { commits++; PushMailboxDrain.Commit.COMMITTED },
            ack = { accepted }, current = { true }, recordFailure = { error("unexpected failure") },
            maxPages = 1, maxScanPages = 2,
        )
        val first = drain.run() as PushMailboxDrain.Result.Continue
        val second = drain.run(first.checkpoint) as PushMailboxDrain.Result.Continue
        assertEquals(PushMailboxDrain.Result.Restart, drain.run(second.checkpoint))
        assertEquals(2, pulls)
        assertEquals(2, commits)
    }

    @Test fun transportFailuresAndCancellationNeverTurnIntoCompletedScans() = runBlocking {
        var failures = 0
        var mode = 0
        val drain = PushMailboxDrain(
            pull = { when (mode++) {
                0 -> PushMailboxHttpClient.PullResult.Throttled
                1 -> PushMailboxHttpClient.PullResult.Rejected
                2 -> PushMailboxHttpClient.PullResult.Unavailable
                3 -> throw IOException("private upstream data")
                else -> throw CancellationException("stop")
            } },
            commit = { error("not called") }, ack = { error("not called") },
            current = { true }, recordFailure = { failures++ },
        )
        repeat(4) { assertEquals(PushMailboxDrain.Result.Retry, drain.run()) }
        assertEquals(1, failures)
        assertTrue(runCatching { drain.run() }.exceptionOrNull() is CancellationException)
        assertEquals(1, failures)
    }
}
