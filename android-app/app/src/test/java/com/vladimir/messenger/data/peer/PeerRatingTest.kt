package com.vladimir.messenger.data.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверка расчёта рейтинга узлов.
 *
 * Считает чистая логика без Android, поэтому проверяется обычным JVM-тестом:
 * ошибка здесь означала бы, что данные пойдут через мёртвый узел.
 */
class PeerRatingTest {

    private val now = 1_700_000_000_000L

    private fun peer(
        id: String = "pk_a",
        sightings: Long = 0,
        misses: Long = 0,
        lastSeenMs: Long = now,
        bytes: Long = 0,
        millis: Long = 0,
        delivered: Long = 0,
        failed: Long = 0,
        public: Boolean = false,
    ) = PeerStats(
        peerId = id,
        sightings = sightings,
        misses = misses,
        lastSeenMs = lastSeenMs,
        transferredBytes = bytes,
        transferMillis = millis,
        delivered = delivered,
        failed = failed,
        hasPublicAddress = public,
    )

    @Test
    fun alwaysOnlinePeerBeatsRarelySeenOne() {
        val steady = peer(id = "pk_steady", sightings = 100, misses = 2)
        val flaky = peer(id = "pk_flaky", sightings = 5, misses = 95)
        assertTrue(steady.score(now) > flaky.score(now))
    }

    @Test
    fun directlyReachablePeerRanksHigher() {
        val open = peer(sightings = 10, misses = 0, public = true)
        val closed = peer(sightings = 10, misses = 0, public = false)
        assertTrue(open.score(now) > closed.score(now))
    }

    @Test
    fun fasterTransfersRaiseTheScore() {
        val fast = peer(sightings = 10, bytes = 10_000_000, millis = 5_000)
        val slow = peer(sightings = 10, bytes = 10_000, millis = 5_000)
        assertTrue(fast.score(now) > slow.score(now))
    }

    @Test
    fun failedDeliveriesLowerTheScore() {
        val good = peer(sightings = 10, delivered = 20, failed = 0)
        val bad = peer(sightings = 10, delivered = 2, failed = 18)
        assertTrue(good.score(now) > bad.score(now))
    }

    @Test
    fun longAbsenceLowersTheScore() {
        val here = peer(sightings = 10, lastSeenMs = now)
        val gone = peer(sightings = 10, lastSeenMs = now - 3L * 24 * 60 * 60 * 1000)
        assertTrue(here.score(now) > gone.score(now))
    }

    @Test
    fun scoreStaysInRange() {
        val best = peer(sightings = 1000, misses = 0, bytes = 1_000_000_000, millis = 1_000, delivered = 500, public = true)
        val worst = peer(sightings = 0, misses = 500, lastSeenMs = 0)
        assertTrue(best.score(now) in 0..100)
        assertTrue(worst.score(now) in 0..100)
        assertTrue(best.score(now) > worst.score(now))
    }

    @Test
    fun speedIsBytesPerSecond() {
        assertEquals(1000L, peer(bytes = 2000, millis = 2000).bytesPerSecond)
        assertEquals(0L, peer(bytes = 0, millis = 0).bytesPerSecond)
    }

    @Test
    fun homeAddressesAreNotPublic() {
        assertFalse(PeerRatingStore.isPublicAddress("192.168.1.5:7777"))
        assertFalse(PeerRatingStore.isPublicAddress("10.0.0.8:7777"))
        assertFalse(PeerRatingStore.isPublicAddress("172.16.4.2"))
        assertFalse(PeerRatingStore.isPublicAddress("127.0.0.1:7777"))
        assertFalse(PeerRatingStore.isPublicAddress("169.254.3.3"))
        assertFalse(PeerRatingStore.isPublicAddress(""))
        assertFalse(PeerRatingStore.isPublicAddress("не адрес"))
    }

    @Test
    fun outsideAddressesArePublic() {
        assertTrue(PeerRatingStore.isPublicAddress("81.222.4.9:7777"))
        assertTrue(PeerRatingStore.isPublicAddress("8.8.8.8"))
        assertTrue(PeerRatingStore.isPublicAddress("172.32.1.1"))
    }

    @Test
    fun tiersReadInPlainWords() {
        val best = peer(sightings = 1000, bytes = 1_000_000_000, millis = 1_000, delivered = 100, public = true)
        assertEquals("Отличный", best.tier(now))
        assertEquals("Слабый", peer(sightings = 0, misses = 100, lastSeenMs = 0).tier(now))
    }

    @Test
    fun neverMeasuredPeerIsNeutralNotPunished() {
        // Узел, с которым ещё не пробовали обмениваться, не должен считаться
        // ненадёжным: иначе новые собеседники навсегда оставались бы в конце.
        assertEquals(0.5, peer(sightings = 1).reliability, 0.0001)
    }
}
