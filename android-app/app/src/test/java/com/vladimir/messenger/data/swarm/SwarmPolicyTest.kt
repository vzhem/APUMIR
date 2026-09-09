package com.vladimir.messenger.data.swarm

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwarmPolicyTest {

    private val knowledge = PeerKnowledge(
        contacts = setOf("pk_contact"),
        verified = setOf("pk_verified", "pk_contact"),
        privileged = setOf("pk_admin"),
        scores = mapOf(
            "pk_contact" to 5,
            "pk_verified" to 40,
            "pk_admin" to 10,
            "pk_popular" to 80,
            "pk_average" to 30,
        ),
    )

    @Test
    fun tiersFollowOwnerOrder() {
        assertEquals(PeerTier.OWN, SwarmPolicy.tierOf("pk_contact", knowledge))
        assertEquals(PeerTier.VERIFIED, SwarmPolicy.tierOf("pk_verified", knowledge))
        assertEquals(PeerTier.STABLE, SwarmPolicy.tierOf("pk_admin", knowledge))
        assertEquals(PeerTier.STABLE, SwarmPolicy.tierOf("pk_popular", knowledge))
        assertEquals(PeerTier.OTHER, SwarmPolicy.tierOf("pk_average", knowledge))
        assertEquals(PeerTier.OTHER, SwarmPolicy.tierOf("pk_unknown", knowledge))
    }

    @Test
    fun contactStaysOwnEvenWithLowScore() {
        // Свой контакт со слабым рейтингом идёт раньше чужого «отличного» узла.
        val ordered = SwarmPolicy.order(listOf("pk_popular", "pk_contact"), knowledge)
        assertEquals(listOf("pk_contact", "pk_popular"), ordered)
    }

    @Test
    fun orderIsByTierThenScoreThenIdAndKeepsEveryone() {
        val input = listOf("pk_unknown", "pk_average", "pk_popular", "pk_admin", "pk_verified", "pk_contact")
        val ordered = SwarmPolicy.order(input, knowledge)
        assertEquals(
            listOf("pk_contact", "pk_verified", "pk_popular", "pk_admin", "pk_average", "pk_unknown"),
            ordered,
        )
        assertEquals(input.size, ordered.size)
    }

    @Test
    fun orderIsDeterministicForUnknownPeers() {
        val a = SwarmPolicy.order(listOf("pk_b", "pk_a", "pk_c"), PeerKnowledge.EMPTY)
        val b = SwarmPolicy.order(listOf("pk_c", "pk_b", "pk_a"), PeerKnowledge.EMPTY)
        assertEquals(a, b)
        assertEquals(listOf("pk_a", "pk_b", "pk_c"), a)
    }

    @Test
    fun limitsShrinkOnMeteredAndLowPowerButNeverBelowFloor() {
        val normal = SwarmPolicy.limitsFor(SwarmMode.NORMAL, metered = false, lowPower = false)
        assertEquals(8, normal.maxConcurrentSends)
        assertEquals(300, normal.maxPacketsPerMinute)
        assertEquals(100, normal.maxSignalsPerMinute)

        val metered = SwarmPolicy.limitsFor(SwarmMode.NORMAL, metered = true, lowPower = false)
        assertEquals(4, metered.maxConcurrentSends)
        assertEquals(150, metered.maxPacketsPerMinute)

        val both = SwarmPolicy.limitsFor(SwarmMode.NORMAL, metered = true, lowPower = true)
        assertEquals(2, both.maxConcurrentSends)
        assertEquals(75, both.maxPacketsPerMinute)

        val economyWorst = SwarmPolicy.limitsFor(SwarmMode.ECONOMY, metered = true, lowPower = true)
        assertEquals(SwarmPolicy.MIN_CONCURRENT, economyWorst.maxConcurrentSends)
        assertEquals(SwarmPolicy.MIN_PACKETS_PER_MINUTE, economyWorst.maxPacketsPerMinute)
        assertTrue(economyWorst.maxSignalsPerMinute >= 1)
    }

    @Test
    fun storedModeFallsBackToNormal() {
        assertEquals(SwarmMode.ECONOMY, SwarmMode.fromStored("economy"))
        assertEquals(SwarmMode.NORMAL, SwarmMode.fromStored(null))
        assertEquals(SwarmMode.NORMAL, SwarmMode.fromStored("garbage"))
    }

    // ── бюджет ────────────────────────────────────────────────────────────

    private class FakeClock(var nowMs: Long = 1_000_000L)

    private fun budget(
        clock: FakeClock,
        limits: SwarmLimits = SwarmLimits(maxConcurrentSends = 8, maxPacketsPerMinute = 60, maxSignalsPerMinute = 20),
        slept: MutableList<Long> = ArrayList(),
    ): SwarmBudget = SwarmBudget(
        limits = { limits },
        clock = { clock.nowMs },
        // «Сон» двигает часы: так проверяется ожидание без реального времени.
        sleep = { ms ->
            slept.add(ms)
            clock.nowMs += ms
        },
    )

    @Test
    fun firstBurstGoesWithoutWaitingUpToTheCap() = runTest {
        val clock = FakeClock()
        val slept = ArrayList<Long>()
        val budget = budget(clock, slept = slept)
        repeat(60) { assertTrue(budget.acquire(SwarmLane.CONTENT)) }
        assertTrue("burst up to the cap must not sleep", slept.isEmpty())
        assertEquals(0, budget.available(SwarmLane.CONTENT))
    }

    @Test
    fun contentWaitsForRefillInsteadOfDropping() = runTest {
        val clock = FakeClock()
        val slept = ArrayList<Long>()
        val budget = budget(clock, slept = slept)
        repeat(60) { budget.acquire(SwarmLane.CONTENT) }
        // 61-й пакет ждёт ~1 с (60 в минуту), но уходит.
        assertTrue(budget.acquire(SwarmLane.CONTENT))
        assertTrue(slept.isNotEmpty())
        val waited = slept.sum()
        assertTrue("waited $waited ms", waited in 900L..1_200L)
    }

    @Test
    fun signalsAreDroppedWhenTheirLaneIsEmpty() = runTest {
        val clock = FakeClock()
        val budget = budget(clock)
        repeat(20) { assertTrue(budget.tryAcquire(SwarmLane.SIGNAL)) }
        assertFalse(budget.tryAcquire(SwarmLane.SIGNAL))
        assertFalse("acquire on SIGNAL lane must not block", budget.acquire(SwarmLane.SIGNAL))
        // Содержимое от этого не страдает: у него своя полоса.
        assertTrue(budget.tryAcquire(SwarmLane.CONTENT))
        // Через 30 с половина служебной полосы вернулась.
        clock.nowMs += 30_000L
        assertEquals(10, budget.available(SwarmLane.SIGNAL))
    }

    @Test
    fun refillNeverExceedsTheCapAfterLongSilence() = runTest {
        val clock = FakeClock()
        val budget = budget(clock)
        repeat(60) { budget.acquire(SwarmLane.CONTENT) }
        clock.nowMs += 10 * 60_000L
        assertEquals(60, budget.available(SwarmLane.CONTENT))
    }

    @Test
    fun limitsAreReadOnEveryDecision() = runTest {
        val clock = FakeClock()
        var current = SwarmLimits(maxConcurrentSends = 8, maxPacketsPerMinute = 60, maxSignalsPerMinute = 20)
        val budget = SwarmBudget(limits = { current }, clock = { clock.nowMs }, sleep = { clock.nowMs += it })
        repeat(60) { budget.acquire(SwarmLane.CONTENT) }
        // Переключились на «Экономный»: ведро теперь меньше, ждать дольше.
        current = SwarmLimits(maxConcurrentSends = 4, maxPacketsPerMinute = 30, maxSignalsPerMinute = 10)
        val before = clock.nowMs
        assertTrue(budget.acquire(SwarmLane.CONTENT))
        val waited = clock.nowMs - before
        assertTrue("waited $waited ms", waited in 1_900L..2_200L)
    }
}
