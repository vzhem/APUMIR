package com.vladimir.messenger.data.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushTargetsTest {
    private fun route(value: Int) = "%064x".format(value)
    private fun local() = "pk_" + "1".repeat(32)
    private fun peer(value: Int) = "pk_" + "%032x".format(value)


    @Test fun everyPageIsEnumeratedAndDuplicatesCollapse() {
        val first = route(1)
        val second = route(2)
        val page = PushTargets(
            page = { after, _ ->
                if (after == null) PushRegistrationStore.RecoveryPage.Ready(listOf(first), "z")
                else PushRegistrationStore.RecoveryPage.Ready(listOf(second, first), null)
            },
            find = { hint -> PushRegistrationStore.RouteResult.Found(local(), peer(7), 1) },
        )
        val (state, peers) = page.enumerate()
        assertEquals(PushTargets.Outcome.COMPLETED, state)
        assertEquals(1, peers.size)
        assertEquals(peer(7), peers.single().peerId)
        assertEquals(local(), peers.single().localId)
    }

    @Test fun anUnreadableIndexIsNeverAnEmptyList() {
        for (pageResult in listOf(
            PushRegistrationStore.RecoveryPage.Unavailable,
            PushRegistrationStore.RecoveryPage.Invalid,
        )) {
            val targets = PushTargets(page = { _, _ -> pageResult }, find = { PushRegistrationStore.RouteResult.Missing })
            val (state, peers) = targets.enumerate()
            assertEquals(PushTargets.Outcome.RETRY, state)
            assertTrue(peers.isEmpty())
        }

        val throwing = PushTargets(page = { _, _ -> throw IllegalStateException("db") }, find = { PushRegistrationStore.RouteResult.Missing })
        assertEquals(PushTargets.Outcome.RETRY, throwing.enumerate().first)
    }

    @Test fun aVanishedRouteIsSkippedButAnUnreadableOneRetriesTheEnumeration() {
        val skipped = PushTargets(
            page = { _, _ -> PushRegistrationStore.RecoveryPage.Ready(listOf(route(3)), null) },
            find = { PushRegistrationStore.RouteResult.Missing },
        )
        assertEquals(PushTargets.Outcome.COMPLETED, skipped.enumerate().first)

        val unreadable = PushTargets(
            page = { _, _ -> PushRegistrationStore.RecoveryPage.Ready(listOf(route(4)), null) },
            find = { PushRegistrationStore.RouteResult.Unavailable },
        )
        val (state, peers) = unreadable.enumerate()
        assertEquals(PushTargets.Outcome.RETRY, state)
        assertTrue(peers.isEmpty())
    }

    @Test fun thePageBudgetIsABoundAndNotAFinishedList() {
        val endless = PushTargets(
            page = { _, _ -> PushRegistrationStore.RecoveryPage.Ready(emptyList(), "again") },
            find = { PushRegistrationStore.RouteResult.Missing },
            maxPages = 2,
        )
        assertEquals(PushTargets.Outcome.RETRY, endless.enumerate().first)

        val outOfRange = PushTargets(
            page = { _, _ -> error("no read for an invalid budget") },
            find = { PushRegistrationStore.RouteResult.Missing },
            maxPages = 0,
        )
        assertEquals(PushTargets.Outcome.RETRY, outOfRange.enumerate().first)
    }
}
