package com.vladimir.messenger.data.heart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Конверт сердечка — рейтинг профиля.
 *
 * Главное свойство: в теле НЕТ того, кто голосует. Голосующий берётся из
 * адреса отправителя, иначе один узел прислал бы пачку голосов от вымышленных
 * имён и накрутил себе рейтинг.
 */
class HeartWireTest {

    @Test
    fun `round trip keeps owner and state`() {
        val added = HeartWire.parse(HeartWire.build("pk_abc", true, 1700)!!)!!
        assertEquals("pk_abc", added.ownerId)
        assertTrue(added.added)
        assertEquals(1700L, added.atMs)

        val removed = HeartWire.parse(HeartWire.build("pk_abc", false, 1701)!!)!!
        assertFalse(removed.added)
    }

    @Test
    fun `envelope carries no voter identity`() {
        // Голосующего подделать нельзя: его просто нет в конверте.
        val envelope = HeartWire.build("pk_owner", true, 1)!!
        assertFalse(envelope.contains("voter"))
        assertEquals(4, envelope.split("|").size)
    }

    @Test
    fun `plain text and other protocols are not hearts`() {
        assertFalse(HeartWire.isHeartPacket("Привет"))
        assertFalse(HeartWire.isHeartPacket("APUREACT1|c|m|x|1|1"))
        assertFalse(HeartWire.isHeartPacket("APUREAD1|c|m|1"))
        assertFalse(HeartWire.isHeartPacket(null))
    }

    @Test
    fun `malformed envelopes are rejected`() {
        assertNull(HeartWire.parse("APUHEART1|owner|2|1"))
        assertNull(HeartWire.parse("APUHEART1|owner|1|notanumber"))
        assertNull(HeartWire.parse("APUHEART1||1|1"))
        assertNull(HeartWire.parse("APUHEART1|owner|1"))
    }

    @Test
    fun `owner with separator is refused`() {
        assertNull(HeartWire.build("pk_a|bc", true, 1))
        assertNull(HeartWire.build("", true, 1))
        assertNull(HeartWire.buildAntiRating("pk_a|bc", true, 1))
        assertNull(HeartWire.buildAntiRating("", true, 1))
    }

    @Test
    fun `anti rating round trip and storage key`() {
        val raw = HeartWire.buildAntiRating("pk_spammer", true, 1800)!!
        assertTrue(HeartWire.isHeartPacket(raw))
        assertTrue(HeartWire.isAntiRatingPacket(raw))

        val parsed = HeartWire.parse(raw)!!
        assertEquals("pk_spammer", parsed.ownerId)
        assertTrue(parsed.added)
        assertTrue(parsed.isAnti)
        assertEquals(1800L, parsed.atMs)

        val storageKey = HeartWire.antiStorageKey("pk_spammer")
        assertEquals("anti|pk_spammer", storageKey)
        assertEquals("pk_spammer", HeartWire.ownerFromAntiStorageKey(storageKey))
        assertNull(HeartWire.ownerFromAntiStorageKey("pk_spammer"))
    }

    @Test
    fun `warning needs a burst of complaints not a slow accumulation`() {
        val now = 1_700_000_000_000L
        val hour = 60 * 60 * 1000L

        // Три жалобы за пару часов - резкое падение рейтинга: предупреждаем.
        val burst = listOf(now - hour, now - 2 * hour, now - 3 * hour)
        assertTrue(HeartWire.isAntiWarningActive(burst, now))
        assertEquals(
            (now - hour) + HeartWire.ANTI_BURST_TTL_MS,
            HeartWire.antiWarningUntilMs(burst, now),
        )

        // Те же три жалобы по одной в разные дни - это не залп: не предупреждаем.
        val slow = listOf(now - 3 * 24 * hour, now - 10 * 24 * hour, now - 30 * 24 * hour)
        assertFalse(HeartWire.isAntiWarningActive(slow, now))
        assertEquals(0L, HeartWire.antiWarningUntilMs(slow, now))

        // Две жалобы подряд - ещё не залп: порог тот же, что был у «3».
        val two = listOf(now - hour, now - 2 * hour)
        assertFalse(HeartWire.isAntiWarningActive(two, now))
    }

    @Test
    fun `warning fades by itself after its term`() {
        val now = 2_000_000_000_000L
        val hour = 60 * 60 * 1000L
        val burst = listOf(now - hour, now - 2 * hour, now - 3 * hour)
        val until = HeartWire.antiWarningUntilMs(burst, now)
        assertTrue(until > now)
        assertTrue(HeartWire.isAntiWarningActive(burst, now))
        // Временная мера: после срока предупреждение снимается само,
        // даже если жалобы никто не отменял.
        assertFalse(HeartWire.isAntiWarningActive(burst, until + 1))
        assertEquals(0L, HeartWire.antiWarningUntilMs(burst, until + 1))
    }

    @Test
    fun `burst window counts only recent complaints`() {
        val now = 3_000_000_000_000L
        val hour = 60 * 60 * 1000L
        val old = listOf(now - 10 * hour, now - 20 * hour)
        val fresh = listOf(now - hour, now - 2 * hour)
        assertEquals(2, HeartWire.antiVotesInWindow(old + fresh, now))
        assertEquals(0, HeartWire.antiVotesInWindow(old, now))
    }
}
