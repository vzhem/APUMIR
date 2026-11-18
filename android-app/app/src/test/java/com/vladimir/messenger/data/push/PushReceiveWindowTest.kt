package com.vladimir.messenger.data.push

import com.vladimir.messenger.data.security.SealedWire
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushReceiveWindowTest {
    private fun hex(value: Int) = "%032x".format(value)
    private fun wire(value: Int): String {
        val key = ByteArray(204) { 1 }
        key[1] = 0; key[2] = 1
        return SealedWire.encode(key, ByteArray(12) { 2 }, ByteArray(17) { value.toByte() })
    }
    private fun item(value: Int) = PushMailboxItem(hex(value), wire(value), 1_700_000_000_000L, hex(value + 100))
    private fun page(vararg items: PushMailboxItem) =
        PushMailboxHttpClient.PullResult.Page(items.toList(), items.lastOrNull()?.id)

    @Test fun aCompletedPassIsTheOnlyCompletedWindow() = runBlocking {
        val first = item(1)
        var committed = 0
        val window = PushReceiveWindow()
        val outcome = window.run(
            pull = { after -> if (after == "-") page(first) else page() },
            commit = { envelope ->
                assertEquals(first.envelope, envelope)
                committed++
                PushMailboxDrain.Commit.COMMITTED
            },
            ack = { receipts ->
                assertEquals(listOf(first.receipt), receipts.map { it.receipt })
                PushMailboxHttpClient.AckResult.ACCEPTED
            },
            current = { true },
        )
        assertEquals(PushReceiveWindow.Outcome.COMPLETED, outcome)
        assertEquals(1, committed)
    }

    @Test fun anUnprocessedItemNeverCompletesTheWindow() = runBlocking {
        val window = PushReceiveWindow()
        val outcome = window.run(
            pull = { after -> if (after == "-") page(item(1), item(2)) else page() },
            commit = { PushMailboxDrain.Commit.RETRY },
            ack = { error("nothing may be acknowledged") },
            current = { true },
        )
        assertEquals(PushReceiveWindow.Outcome.RETRY, outcome)
    }

    @Test fun aFailedAckOrChangedTrustRetriesTheWholeWindow() = runBlocking {
        val failing = PushReceiveWindow().run(
            pull = { after -> if (after == "-") page(item(1)) else page() },
            commit = { PushMailboxDrain.Commit.COMMITTED },
            ack = { PushMailboxHttpClient.AckResult.THROTTLED },
            current = { true },
        )
        assertEquals(PushReceiveWindow.Outcome.RETRY, failing)

        var checks = 0
        val changed = PushReceiveWindow().run(
            pull = { after -> if (after == "-") page(item(1)) else page() },
            commit = { PushMailboxDrain.Commit.COMMITTED },
            ack = { error("an ACK after a trust change would be a lie") },
            current = { checks++ < 1 },
        )
        assertEquals(PushReceiveWindow.Outcome.RETRY, changed)
    }

    @Test fun aPageBudgetOrTransportFailureNeverLooksLikeSuccess() = runBlocking {
        val looping = PushReceiveWindow()
        val never = looping.run(
            pull = { page(item(1)) },
            commit = { PushMailboxDrain.Commit.COMMITTED },
            ack = { PushMailboxHttpClient.AckResult.ACCEPTED },
            current = { true },
            maxPages = 2,
        )
        assertEquals(PushReceiveWindow.Outcome.RETRY, never)

        var failures = 0
        val broken = PushReceiveWindow(recordFailure = { failures++ }).run(
            pull = { throw IllegalStateException("network") },
            commit = { PushMailboxDrain.Commit.COMMITTED },
            ack = { PushMailboxHttpClient.AckResult.ACCEPTED },
            current = { true },
        )
        assertEquals(PushReceiveWindow.Outcome.RETRY, broken)
        assertTrue("a failed window must be counted", failures >= 1)
    }

    @Test fun cancellationPropagatesInsteadOfBecomingRetry() = runBlocking {
        var cancelled = false
        try {
            PushReceiveWindow().run(
                pull = { throw CancellationException("cancelled") },
                commit = { PushMailboxDrain.Commit.COMMITTED },
                ack = { PushMailboxHttpClient.AckResult.ACCEPTED },
                current = { true },
            )
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }
}
