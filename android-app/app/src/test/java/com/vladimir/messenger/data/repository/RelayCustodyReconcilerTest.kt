package com.vladimir.messenger.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayCustodyReconcilerTest {
    @Test fun onlyTerminalOwnedIdsAreConfirmedAndRestartCanRepairAnEarlierCrash() = runBlocking {
        val held = listOf("delivered", "incoming", "missing", "read", "sent")
        val confirmed = mutableListOf<String>()
        val snapshot = { CustodySnapshot(held, confirmed::add) }
        val room: suspend (List<String>) -> List<String> = { ids ->
            ids.filter { it == "delivered" || it == "read" } + "unrelated"
        }
        // A new reconciler models restarting after Room committed but before
        // native cleanup. The snapshot callback is tied to its engine lifetime.
        assertFalse(RelayCustodyReconciler(snapshot, room).reconcile())
        assertEquals(listOf("delivered", "read"), confirmed)
    }

    @Test fun largeQueueIsPagedAndDoesNotSkipAStillPendingId() = runBlocking {
        val held = (0 until 80).map { "m%03d".format(it) }
        val pages = mutableListOf<List<String>>()
        val confirmed = mutableListOf<String>()
        val scanner = RelayCustodyReconciler(
            { CustodySnapshot(held, confirmed::add) },
            { ids -> pages += ids; ids.filter { it.endsWith("9") } },
        )
        assertTrue(scanner.reconcile())
        assertTrue(scanner.reconcile())
        assertFalse(scanner.reconcile())
        assertEquals(listOf(32, 32, 16), pages.map { it.size })
        assertEquals(held, pages.flatten())
        assertEquals(held.filter { it.endsWith("9") }, confirmed)
        // The next sweep starts again, so a later ACK is eventually noticed.
        assertTrue(scanner.reconcile())
        assertEquals(held.take(32), pages.last())
    }

    @Test fun failedDatabaseDoesNotAdvanceTheCursorOrReleaseAnything() = runBlocking {
        val held = (0 until 50).map { "m%03d".format(it) }
        val confirmed = mutableListOf<String>()
        var fail = true
        val pages = mutableListOf<List<String>>()
        val scanner = RelayCustodyReconciler({ CustodySnapshot(held, confirmed::add) }) { ids ->
            pages += ids
            if (fail) { fail = false; throw IllegalStateException("database") }
            ids.take(1)
        }
        assertTrue(runCatching { scanner.reconcile() }.exceptionOrNull() is IllegalStateException)
        assertTrue(confirmed.isEmpty())
        assertTrue(scanner.reconcile())
        assertEquals(pages.first(), pages.last())
        assertEquals(listOf("m000"), confirmed)
    }

    @Test fun disappearingTailWrapsAndUtf8CursorMatchesNativeSort() = runBlocking {
        var held = (0 until 32).map { "a%02d".format(it) } + listOf("\uE000", "\uD83D\uDE00")
        val pages = mutableListOf<List<String>>()
        val scanner = RelayCustodyReconciler({ CustodySnapshot(held) {} }, { ids -> pages += ids; emptyList() })
        assertTrue(scanner.reconcile())
        assertFalse(scanner.reconcile())
        assertEquals(listOf("\uE000", "\uD83D\uDE00"), pages.last())
        assertTrue(scanner.reconcile())
        held = held.take(2)
        assertFalse(scanner.reconcile()) // the tail vanished; reset the cursor
        assertFalse(scanner.reconcile())
        assertEquals(held, pages.last())
    }
}
