package com.vladimir.messenger.data.mirror

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorPendingFlusherTest {
    private fun row(id: String) = MirrorRow(
        id = id, chatId = "chat", contactName = "Friend", senderId = "self", content = "body",
        timestamp = 42, mine = true, recipientId = "pk_friend", status = "PENDING",
    )

    @Test fun pairingCanCarryAPageBeforeTheSocketOpensButNotAfterShutdown() {
        var pairingChecks = 0
        fun pairing() = true.also { pairingChecks++ }
        assertTrue(mirrorPendingAvailable(true, false, true, ::pairing))
        assertEquals(0, pairingChecks) // Open WSS does not need a key lookup.
        assertTrue(mirrorPendingAvailable(true, false, false, ::pairing))
        assertEquals(1, pairingChecks)
        assertTrue(!mirrorPendingAvailable(false, false, false, ::pairing))
        assertTrue(!mirrorPendingAvailable(true, true, false, ::pairing))
        assertTrue(!mirrorPendingAvailable(true, false, false) { false })
        assertEquals(1, pairingChecks)
    }

    @Test fun freshPartnerBecomingActiveWakesWithoutEveryHeartbeatDoingSo() = runBlocking {
        assertTrue(mirrorPendingPartnerWake(wasFresh = false, wasActive = false, isActive = false))
        assertTrue(!mirrorPendingPartnerWake(wasFresh = true, wasActive = false, isActive = false))
        assertTrue(mirrorPendingPartnerWake(wasFresh = true, wasActive = false, isActive = true))
        assertTrue(!mirrorPendingPartnerWake(wasFresh = true, wasActive = true, isActive = true))
        assertTrue(!mirrorPendingPartnerWake(wasFresh = true, wasActive = true, isActive = false))
        var now = 0L
        var calls = 0
        val flusher = MirrorPendingFlusher(this, { true }, { calls++; MirrorPendingBatch(emptyList(), false) },
            { true }, { throw AssertionError("unexpected", it) }, { now })
        flusher.request(); yield()
        now = 20_000; flusher.request(); yield()
        now = 60_000; flusher.request(); yield() // idle delay is now 80 seconds
        now = 80_000
        if (mirrorPendingPartnerWake(wasFresh = true, wasActive = false, isActive = true)) flusher.wake()
        yield()
        assertEquals(4, calls)
    }

    @Test fun wakeShortensIdleBackoffButStillRespectsCooldown() = runBlocking {
        var now = 0L
        var calls = 0
        val flusher = MirrorPendingFlusher(this, { true }, { calls++; MirrorPendingBatch(emptyList(), false) },
            { true }, { throw AssertionError("unexpected", it) }, { now })
        flusher.request(); yield()
        now = 20_000; flusher.request(); yield() // next completed sweep would wait 40 seconds
        now = 39_999; flusher.wake(); yield()
        assertEquals(2, calls)
        now = 40_000; flusher.request(); yield()
        assertEquals(3, calls)
    }

    @Test fun availabilityAndCooldownBoundPeriodicRequests() = runBlocking {
        var now = 1_000L
        var available = false
        val pages = mutableListOf<Int>()
        val offered = mutableListOf<String>()
        val flusher = MirrorPendingFlusher(this, { available }, { size -> pages += size; MirrorPendingBatch(listOf(row("one")), false) },
            { offered += it.id; true }, { throw AssertionError("unexpected", it) }, { now })
        flusher.request()
        yield()
        assertTrue(pages.isEmpty())
        available = true
        flusher.request()
        yield()
        assertEquals(listOf(50), pages)
        assertEquals(listOf("one"), offered)
        now += 19_999
        flusher.request()
        yield()
        assertEquals(1, pages.size)
        now += 1
        flusher.request()
        yield()
        assertEquals(listOf(50, 50), pages)
        assertEquals(listOf("one", "one"), offered)
    }

    @Test fun concurrentTriggersNeverOverlapAnInflightPage() = runBlocking {
        val now = AtomicLong(0)
        val calls = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val flusher = MirrorPendingFlusher(this, { true }, {
            if (calls.incrementAndGet() == 1) { started.complete(Unit); release.await() }
            MirrorPendingBatch(emptyList(), false)
        }, { true }, { throw AssertionError("unexpected", it) }, { now.get() })
        flusher.request()
        started.await()
        now.set(40_000)
        coroutineScope { (1..24).map { index ->
            async(Dispatchers.Default) { if (index % 2 == 0) flusher.wake() else flusher.request() }
        }.awaitAll() }
        assertEquals(1, calls.get())
        release.complete(Unit)
        yield()
        flusher.request()
        yield()
        assertEquals(2, calls.get())
    }

    @Test fun disconnectDuringLoadOrOffersStopsTheRemainingFrames() = runBlocking {
        var now = 0L
        var available = true
        var dropDuringLoad = true
        val offered = mutableListOf<String>()
        val flusher = MirrorPendingFlusher(this, { available }, {
            if (dropDuringLoad) available = false
            MirrorPendingBatch(listOf(row("one"), row("two")), false)
        }, { row -> offered += row.id; available = false; true },
            { throw AssertionError("unexpected", it) }, { now })
        flusher.request()
        yield()
        assertTrue(offered.isEmpty())
        dropDuringLoad = false
        available = true
        now += 20_000
        flusher.request()
        yield()
        assertEquals(listOf("one"), offered)
    }

    @Test fun aFailedOfferDoesNotBecomeAnAckAndCanBeOfferedAgain() = runBlocking {
        var now = 0L
        val offered = mutableListOf<String>()
        val flusher = MirrorPendingFlusher(this, { true }, { MirrorPendingBatch(listOf(row("one")), false) },
            { offered += it.id; false }, { throw AssertionError("unexpected", it) }, { now })
        flusher.request()
        yield()
        now += 20_000
        flusher.request()
        yield()
        assertEquals(listOf("one", "one"), offered)
    }

    @Test fun cancellationAndFailureReleaseGateWithoutLeakingAnErrorAsSuccess() = runBlocking {
        var now = 0L
        var mode = 0
        val failures = mutableListOf<Exception>()
        val offered = mutableListOf<String>()
        val flusher = MirrorPendingFlusher(this, { true }, {
            when (mode) {
                0 -> throw CancellationException("cancel")
                1 -> error("storage")
                else -> MirrorPendingBatch(listOf(row("one")), false)
            }
        }, { offered += it.id; true }, { failures += it }, { now })
        flusher.request()
        yield()
        assertTrue(failures.isEmpty())
        mode = 1
        now += 20_000
        flusher.request()
        yield()
        assertEquals(1, failures.size)
        mode = 2
        now += 20_000
        flusher.request()
        yield()
        assertEquals(listOf("one"), offered)
    }

    @Test fun cancellationAfterLoadingDoesNotStartIdleBackoff() = runBlocking {
        var now = 0L
        var calls = 0
        var cancelOffer = false
        val flusher = MirrorPendingFlusher(this, { true }, {
            calls++
            MirrorPendingBatch(if (cancelOffer) listOf(row("one")) else emptyList(), false)
        }, { if (cancelOffer) throw CancellationException("cancel after load") else true },
            { throw AssertionError("unexpected", it) }, { now })
        flusher.request(); yield()
        now = 20_000; flusher.request(); yield()
        now = 60_000; cancelOffer = true; flusher.request(); yield()
        cancelOffer = false
        now = 80_000; flusher.request(); yield()
        assertEquals(4, calls)
    }

    @Test fun clockRollbackDoesNotDisableTheGateForever() = runBlocking {
        var now = 50_000L
        var calls = 0
        val flusher = MirrorPendingFlusher(this, { true }, { calls++; MirrorPendingBatch(emptyList(), false) },
            { true }, { throw AssertionError("unexpected", it) }, { now })
        flusher.request()
        yield()
        now = 1_000
        flusher.request()
        yield()
        assertEquals(2, calls)
    }

    @Test fun reorderedConcurrentClockSamplesCannotBypassCooldown() {
        val clockEntered = CountDownLatch(1)
        val releaseClock = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val secondDone = CountDownLatch(1)
        val samples = AtomicInteger()
        val calls = AtomicInteger()
        val error = AtomicReference<Throwable?>()
        val parent = Job()
        val flusher = MirrorPendingFlusher(CoroutineScope(parent + Dispatchers.Unconfined), { true }, {
            calls.incrementAndGet(); MirrorPendingBatch(emptyList(), false)
        }, { true }, { throw AssertionError("unexpected", it) }, {
            if (samples.getAndIncrement() == 0) {
                clockEntered.countDown()
                releaseClock.await()
                0L
            } else 1L
        })
        fun start(block: () -> Unit) = Thread {
            try { block() } catch (e: Throwable) { error.compareAndSet(null, e) }
        }.also { it.start() }
        val first = start { flusher.request() }
        var second: Thread? = null
        try {
            assertTrue(clockEntered.await(5, TimeUnit.SECONDS))
            second = start { secondEntered.countDown(); flusher.request(); secondDone.countDown() }
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS))
            // With the old outside-lock sample the second page completes here,
            // and the stale first sample then looks like a clock rollback.
            secondDone.await(250, TimeUnit.MILLISECONDS)
        } finally {
            releaseClock.countDown()
            first.join(5_000)
            second?.join(5_000)
            parent.cancel()
        }
        assertTrue(!first.isAlive && second?.isAlive != true)
        assertEquals(null, error.get())
        assertEquals(1, calls.get())
    }

    @Test fun completedSweepsBackOffButNewWorkRestoresTheShortDelay() = runBlocking {
        var now = 0L
        var calls = 0
        val flusher = MirrorPendingFlusher(this, { true }, { calls++; MirrorPendingBatch(emptyList(), false) },
            { true }, { throw AssertionError("unexpected", it) }, { now })
        flusher.request(); yield()
        now = 20_000; flusher.request(); yield()
        now = 40_000; flusher.request(); yield()
        assertEquals(2, calls) // second completed sweep imposed 40 seconds
        now = 60_000; flusher.request(); yield()
        now = 80_000; flusher.request(); yield()
        assertEquals(3, calls) // next sweep would wait 80 seconds
        flusher.noteNewWork()
        flusher.request(); yield()
        assertEquals(4, calls)
    }

    @Test fun idleSweepBackoffIsCappedAtTenMinutes() = runBlocking {
        var now = 0L
        var calls = 0
        val flusher = MirrorPendingFlusher(this, { true }, { calls++; MirrorPendingBatch(emptyList(), false) },
            { true }, { throw AssertionError("unexpected", it) }, { now })
        flusher.request(); yield()
        var delay = 20_000L
        repeat(9) {
            now += delay - 1
            flusher.request(); yield()
            assertEquals(it + 1, calls)
            now++
            flusher.request(); yield()
            assertEquals(it + 2, calls)
            delay = (delay * 2).coerceAtMost(600_000L)
        }
    }

    @Test fun unfinishedPagesUseTheShortDelayEvenAfterIdleBackoff() = runBlocking {
        var now = 0L
        var calls = 0
        var more = false
        val flusher = MirrorPendingFlusher(this, { true }, { calls++; MirrorPendingBatch(emptyList(), more) },
            { true }, { throw AssertionError("unexpected", it) }, { now })
        flusher.request(); yield()
        now = 20_000; flusher.request(); yield()
        now = 60_000; more = true; flusher.request(); yield()
        now = 80_000; flusher.request(); yield()
        assertEquals(4, calls)
    }

    @Test fun newWorkDuringADeferredPageIsNotLostAtCompletion() = runBlocking {
        var now = 0L
        var calls = 0
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val flusher = MirrorPendingFlusher(this, { true }, {
            calls++
            if (calls == 3) { started.complete(Unit); release.await() }
            MirrorPendingBatch(emptyList(), false)
        }, { true }, { throw AssertionError("unexpected", it) }, { now })
        flusher.request(); yield()
        now = 20_000; flusher.request(); yield()
        now = 60_000; flusher.request(); started.await()
        flusher.noteNewWork()
        release.complete(Unit)
        yield()
        now = 80_000; flusher.request(); yield()
        assertEquals(4, calls)
    }

    @Test fun alreadyCancelledScopeDoesNotStartWork() = runBlocking {
        val parent = Job()
        val stopped = CoroutineScope(parent + Dispatchers.Default)
        stopped.cancel()
        var calls = 0
        val flusher = MirrorPendingFlusher(stopped, { true }, { calls++; MirrorPendingBatch(emptyList(), false) },
            { true }, { throw AssertionError("unexpected", it) }, { 0L })
        flusher.request()
        yield()
        assertEquals(0, calls)
    }
}
