package com.vladimir.messenger.data.story

import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Досылка фотоистории: идентификаторы частей стабильны, а потерянная часть
 * повторяется следующей волной - иначе получатель не соберёт фото никогда.
 */
class StoryOutboxTest {
    @Test
    fun transportIdsAreStableUuidsAndDistinctPerPart() {
        val first = StoryOutbox.transportId("story-1", 0)
        assertEquals(first, StoryOutbox.transportId("story-1", 0))
        assertNotEquals(first, StoryOutbox.transportId("story-1", 1))
        assertNotEquals(first, StoryOutbox.transportId("story-2", 0))
        // Транспорт и релей работают с UUID-идентификаторами сообщений.
        assertEquals(first, UUID.fromString(first).toString())
    }

    @Test
    fun onlyMultiPacketStoriesAreResent() {
        assertFalse(StoryOutbox.canResend("story-1", 1))
        assertTrue(StoryOutbox.canResend("story-1", 2))
        assertFalse(StoryOutbox.canResend("", 2))
        assertFalse(StoryOutbox.canResend("x".repeat(101), 2))
    }

    @Test
    fun singlePacketStoriesAreStillDelivered() {
        assertTrue(StoryOutbox.canDeliver("story-1", 1, 1))
        assertTrue(StoryOutbox.canDeliver("story-1", 2, 2))
        assertFalse(StoryOutbox.canDeliver("story-1", 0, 1))
        assertFalse(StoryOutbox.canDeliver("story-1", 1, 0))
        assertFalse(StoryOutbox.canDeliver("", 1, 1))
        assertFalse(StoryOutbox.canDeliver("x".repeat(101), 1, 1))
    }

    @Test
    fun aRevokePacketIsRetriedAcrossWaves() = runBlocking {
        val packets = StoryOutbox.packets("story-1", listOf("revoke"))
        val sent = mutableListOf<String>()
        StoryOutbox.deliver(
            packets = packets,
            peers = listOf("alice"),
            send = { _, packet -> sent += packet.content; false },
            pause = { },
            rounds = StoryOutbox.MAX_ROUNDS,
        )
        assertEquals(listOf("revoke", "revoke", "revoke"), sent)
    }

    @Test
    fun aSinglePacketStoryIsSentOnceEvenWhenTheTransportFails() = runBlocking {
        val packets = StoryOutbox.packets("story-1", listOf("hello"))
        val sent = mutableListOf<String>()
        val pauses = mutableListOf<Long>()
        StoryOutbox.deliver(
            packets = packets,
            peers = listOf("alice"),
            send = { _, packet -> sent += packet.content; false },
            pause = { pauses += it },
            rounds = 1,
        )
        assertEquals(listOf("hello"), sent)
        assertTrue(pauses.isEmpty())
    }

    @Test
    fun aLostPartRepeatsTheWholeStoryInTheNextWave() = runBlocking {
        val packets = StoryOutbox.packets("story-1", listOf("p0", "p1", "p2"))
        val sent = mutableListOf<String>()
        var failOnce = true
        StoryOutbox.deliver(
            packets = packets,
            peers = listOf("alice"),
            send = { _, packet ->
                sent += packet.content
                if (packet.content == "p1" && failOnce) { failOnce = false; false } else true
            },
            pause = { },
        )
        // Дособрать одну часть нельзя: вторая волна повторяет историю целиком.
        assertEquals(listOf("p0", "p1", "p2", "p0", "p1", "p2"), sent)
    }

    @Test
    fun wavesStopAfterAFullyAcceptedRound() = runBlocking {
        val packets = StoryOutbox.packets("story-1", listOf("p0", "p1"))
        var calls = 0
        val pauses = mutableListOf<Long>()
        StoryOutbox.deliver(
            packets = packets,
            peers = listOf("alice", "bob"),
            send = { _, _ -> calls++; true },
            pause = { pauses += it },
        )
        assertEquals(4, calls)
        assertTrue(pauses.isEmpty())
    }

    @Test
    fun wavesStopAfterTheLastRound() = runBlocking {
        val packets = StoryOutbox.packets("story-1", listOf("p0", "p1"))
        var calls = 0
        val pauses = mutableListOf<Long>()
        StoryOutbox.deliver(
            packets = packets,
            peers = listOf("alice"),
            send = { _, _ -> calls++; false },
            pause = { pauses += it },
        )
        assertEquals(6, calls)
        assertEquals(
            listOf(StoryOutbox.ROUND_DELAY_MS, StoryOutbox.ROUND_DELAY_MS * 2),
            pauses,
        )
    }
    @Test fun revokeTransportIdCannotBeDeduplicatedAsPublishPartZero() {
        assertNotEquals(StoryOutbox.transportId("story-1", 0), StoryOutbox.transportId("revoke:story-1", 0))
    }
}
