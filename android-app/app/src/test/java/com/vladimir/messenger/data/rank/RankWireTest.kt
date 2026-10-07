package com.vladimir.messenger.data.rank

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор конверта ранга — чистая логика без Android, поэтому проверяется здесь,
 * а не только контрактом исходников в CI.
 */
class RankWireTest {

    private val nodeId = "pk_" + "ab".repeat(16)
    private val now = 1_700_000_000_000L

    @Test
    fun buildAndParseRoundTrip() {
        val text = RankWire.build(nodeId, 20, now)
        assertEquals("APURANK1|1|$nodeId|20|$now", text)
        val parsed = RankWire.parse(text!!, nowMs = now)
        assertEquals(RankWire.PeerRank(nodeId, 20, now), parsed)
    }

    @Test
    fun packetIsRecognisedByPrefixOnly() {
        assertTrue(RankWire.isRankPacket("APURANK1|1|$nodeId|20|$now"))
        assertFalse(RankWire.isRankPacket("APUREF1|2|x"))
        assertFalse(RankWire.isRankPacket("привет"))
        // Похожий, но чужой префикс не должен приниматься за наш конверт.
        assertFalse(RankWire.isRankPacket("APURANK2|1|$nodeId|20|$now"))
    }

    @Test
    fun malformedEnvelopesAreRejected() {
        assertNull(RankWire.parse("APURANK1|1|$nodeId|20"))
        assertNull(RankWire.parse("APURANK1|1|$nodeId|20|$now|лишнее"))
        assertNull(RankWire.parse("APURANK1|2|$nodeId|20|$now"))
        assertNull(RankWire.parse("APURANK1|1|не-узел|20|$now"))
        // Слишком короткий и слишком длинный узел одинаково не годятся.
        assertNull(RankWire.parse("APURANK1|1|pk_abc|20|$now"))
        assertNull(RankWire.parse("APURANK1|1|pk_${"a".repeat(200)}|20|$now"))
        assertNull(RankWire.parse("APURANK1|1|$nodeId|двадцать|$now"))
        assertNull(RankWire.parse("APURANK1|1|$nodeId|-1|$now"))
        assertNull(RankWire.parse(""))
    }

    @Test
    fun absurdCountsAndDatesAreRejected() {
        // Опорное время задаём явно: «год вперёд» - это год вперёд от него, а не
        // от часов того, кто запускает тест (иначе проверка была бы плавающей).
        // Выше потолка: не ранг, а попытка нарисовать себе ступень.
        assertNull(RankWire.parse("APURANK1|1|$nodeId|${RankWire.MAX_COUNT + 1}|$now", nowMs = now))
        // Сбитые часы на год вперёд.
        assertNull(
            RankWire.parse(
                "APURANK1|1|$nodeId|20|${now + 365L * 24 * 60 * 60 * 1000}",
                nowMs = now,
            ),
        )
        // Не дата вовсе.
        assertNull(RankWire.parse("APURANK1|1|$nodeId|20|0", nowMs = now))
        // Небольшой запас на разъезд часов допускается.
        assertEquals(
            RankWire.PeerRank(nodeId, 20, now + 60_000),
            RankWire.parse("APURANK1|1|$nodeId|20|${now + 60_000}", nowMs = now),
        )
    }

    @Test
    fun buildRejectsBadInput() {
        assertNull(RankWire.build("не-узел", 20, now))
        assertNull(RankWire.build(nodeId, -1, now))
        assertNull(RankWire.build(nodeId, 20, 0))
        // Свой узел в верхнем регистре приводится к каноническому виду.
        assertEquals("APURANK1|1|$nodeId|3|$now", RankWire.build(nodeId.uppercase(), 3, now))
        // Число выше потолка обрезается, а не уходит в сеть как есть.
        assertEquals(
            "APURANK1|1|$nodeId|${RankWire.MAX_COUNT}|$now",
            RankWire.build(nodeId, RankWire.MAX_COUNT + 500, now),
        )
    }
}
