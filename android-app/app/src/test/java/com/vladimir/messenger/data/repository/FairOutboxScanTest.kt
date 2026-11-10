package com.vladimir.messenger.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FairOutboxScanTest {
    private data class Item(val id: String, val time: Long, val chat: String)

    private fun loader(rows: List<Item>): suspend (OutboxPosition?, Int) -> List<OutboxCandidate<Item>> =
        { after, size ->
            rows.sortedWith(compareBy<Item> { it.time }.thenBy { it.id })
                .filter { after == null || it.time > after.timestamp || (it.time == after.timestamp && it.id > after.id) }
                .take(size)
                .map { OutboxCandidate(OutboxPosition(it.time, it.id), it.chat, it) }
        }

    @Test fun failedOldestChatDoesNotBlockAnotherChat() = runBlocking {
        val rows = listOf(Item("a", 1, "offline"), Item("b", 2, "online"))
        val tried = mutableListOf<String>()
        val result = FairOutboxScan<Item>().run(20, { rows.size }, loader(rows)) {
            tried += it.id
            it.chat == "online"
        }
        assertEquals(listOf("a", "b"), tried)
        assertEquals(1, result.sent)
        assertFalse(result.moreToScan)
    }

    @Test fun blockedChatCannotHideAnOnlineChatBeyondManyPages() = runBlocking {
        val rows = (0 until 515).map { Item("a%04d".format(it), 5, "offline") } + Item("z", 5, "online")
        val scan = FairOutboxScan<Item>()
        val tried = mutableListOf<String>()
        var totalSent = 0
        var cycles = 0
        do {
            val progress = scan.run(20, { rows.size }, loader(rows)) {
                tried += it.id
                it.chat == "online"
            }
            totalSent += progress.sent
            cycles++
            assertTrue("scan must finish", cycles < 40)
        } while (progress.moreToScan)
        assertEquals(listOf("a0000", "z"), tried)
        assertEquals(1, totalSent)
    }

    @Test fun continuouslyGrowingQueueStillEndsEachScanAndRetriesFailures() = runBlocking {
        val rows = mutableListOf(Item("a", 1, "offline"), Item("b", 2, "offline"), Item("z", 3, "online"))
        val scan = FairOutboxScan<Item>()
        val tried = mutableListOf<String>()
        var counts = 0
        var cycles = 0
        do {
            val progress = scan.run(1, { counts++; rows.size }, loader(rows)) {
                tried += it.id
                it.chat == "online"
            }
            // Appending after every page used to keep the scan open forever.
            rows += Item("new$cycles", 100L + cycles, "offline")
            cycles++
            assertTrue("a scan must terminate despite new arrivals", cycles < 10)
        } while (progress.moreToScan)
        assertEquals(3, cycles)
        assertEquals(1, counts)
        assertEquals(listOf("a", "z"), tried)

        // A new scan retries the blocked chat, rotating past its first failure.
        repeat(2) { scan.run(1, { counts++; rows.size }, loader(rows)) { tried += it.id; false } }
        assertEquals(listOf("a", "z", "b"), tried)
        assertEquals(2, counts)
    }

    @Test fun emptyScanDoesNotLoadAndNewRowsCanStartTheNextScan() = runBlocking {
        val rows = mutableListOf<Item>()
        val scan = FairOutboxScan<Item>()
        var loads = 0
        val load: suspend (OutboxPosition?, Int) -> List<OutboxCandidate<Item>> = { after, size ->
            loads++
            loader(rows)(after, size)
        }
        assertFalse(scan.run(20, { rows.size }, load) { true }.moreToScan)
        assertEquals(0, loads)
        rows += Item("new", 1, "chat")
        assertEquals(1, scan.run(20, { rows.size }, load) { true }.sent)
        assertEquals(1, loads)
    }

    @Test fun sameTimestampAndBoundedAttemptsVisitEveryRowOnce() = runBlocking {
        val rows = (0..13).map { Item("m%02d".format(it), 42, "c$it") }
        val scan = FairOutboxScan<Item>(maxAttemptsPerCycle = 2)
        val tried = mutableListOf<String>()
        var cycles = 0
        do {
            val progress = scan.run(5, { rows.size }, loader(rows)) { tried += it.id; true }
            assertTrue(progress.attempted <= 2)
            cycles++
            assertTrue(cycles < 20)
        } while (progress.moreToScan)
        assertEquals(rows.map { it.id }, tried)
    }

    @Test fun failedOldestRowDoesNotPermanentlyHideLaterRowsInTheSameChat() = runBlocking {
        val rows = listOf(Item("a", 1, "chat"), Item("b", 1, "chat"), Item("c", 1, "chat"))
        val scan = FairOutboxScan<Item>()
        val tried = mutableListOf<String>()
        val first = scan.run(20, { rows.size }, loader(rows)) { tried += it.id; it.id != "a" }
        assertFalse(first.moreToScan)
        assertEquals(listOf("a"), tried)
        val second = scan.run(20, { rows.size }, loader(rows)) { tried += it.id; it.id != "a" }
        assertFalse(second.moreToScan)
        assertEquals(listOf("a", "b", "c"), tried)
        assertEquals(2, second.sent)
    }

    @Test fun aSingleFailedRowIsRetriedEverySweep() = runBlocking {
        val rows = listOf(Item("a", 1, "offline"))
        val scan = FairOutboxScan<Item>()
        var attempts = 0
        repeat(3) {
            val progress = scan.run(20, { rows.size }, loader(rows)) { attempts++; false }
            assertFalse(progress.moreToScan)
            assertEquals(1, progress.attempted)
        }
        assertEquals(3, attempts)
    }

    @Test fun failuresRotateAndWrapWithinOneChat() = runBlocking {
        val rows = listOf(Item("a", 1, "chat"), Item("b", 1, "chat"), Item("c", 1, "chat"))
        val scan = FairOutboxScan<Item>()
        val tried = mutableListOf<String>()
        repeat(5) { scan.run(20, { rows.size }, loader(rows)) { tried += it.id; false } }
        assertEquals(listOf("a", "b", "c", "a", "b"), tried)
    }

    @Test fun disappearingLaterRowDoesNotPermanentlySkipEarlierFailure() = runBlocking {
        val rows = mutableListOf(Item("a", 1, "chat"), Item("b", 1, "chat"))
        val scan = FairOutboxScan<Item>()
        val tried = mutableListOf<String>()
        scan.run(20, { rows.size }, loader(rows)) { tried += it.id; false }
        rows.removeAt(1)
        scan.run(20, { rows.size }, loader(rows)) { tried += it.id; false }
        scan.run(20, { rows.size }, loader(rows)) { tried += it.id; false }
        assertEquals(listOf("a", "a"), tried)
    }

    @Test fun cancelledAttemptDoesNotAdvancePastTheMessage() = runBlocking {
        val rows = listOf(Item("a", 1, "chat"), Item("b", 2, "chat"))
        val scan = FairOutboxScan<Item>()
        val error = runCatching {
            scan.run(20, { rows.size }, loader(rows)) { throw kotlinx.coroutines.CancellationException("test") }
        }.exceptionOrNull()
        assertTrue(error is kotlinx.coroutines.CancellationException)
        val tried = mutableListOf<String>()
        scan.run(20, { rows.size }, loader(rows)) { tried += it.id; false }
        assertEquals(listOf("a"), tried)
    }

    @Test fun cursorComparisonMatchesSqliteBinaryForNonAsciiIds() {
        val bmp = OutboxPosition(1, "\uE000")
        val supplementary = OutboxPosition(1, "\uD83D\uDE00")
        assertTrue(bmp.atOrBefore(supplementary))
        assertFalse(supplementary.atOrBefore(bmp))
        assertTrue(bmp.atOrBefore(bmp))
        assertTrue(OutboxPosition(0, "z").atOrBefore(OutboxPosition(1, "a")))
    }

    @Test fun concurrentPumpsAreSerializedAndDoNotRestartAnInProgressScan() = runBlocking {
        val rows = (0..5).map { Item("m$it", 1, "c$it") }
        val scan = FairOutboxScan<Item>(maxAttemptsPerCycle = 1)
        val tried = mutableListOf<String>()
        val results = coroutineScope {
            (1..4).map {
                async(Dispatchers.Default) {
                    scan.run(6, { rows.size }, loader(rows)) { item -> delay(5); tried += item.id; true }
                }
            }.awaitAll()
        }
        assertTrue(results.all { it.attempted == 1 && it.moreToScan })
        assertEquals(listOf("m0", "m1", "m2", "m3"), tried)
    }
}
