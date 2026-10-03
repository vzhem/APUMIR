package com.vladimir.messenger.data.group

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupStatsCalculatorTest {
    private val now = Instant.parse("2026-10-02T12:00:00Z").toEpochMilli()

    @Test
    fun periodContainsSevenCalendarDaysIncludingToday() {
        assertEquals(
            listOf("2026-09-26", "2026-09-27", "2026-09-28", "2026-09-29", "2026-09-30", "2026-10-01", "2026-10-02"),
            GroupStatsCalculator.dayKeys(now),
        )
    }

    @Test
    fun missingDaysAreZeroAndOutOfRangeRowsAreIgnored() {
        val keys = GroupStatsCalculator.dayKeys(now)
        val result = GroupStatsCalculator.completeDays(keys, listOf(
            GroupStatDay("2026-09-25", 100, 5),
            GroupStatDay("2026-09-27", 3, 2),
            GroupStatDay("2026-10-03", 200, 8),
        ))
        assertEquals(keys, result.map { it.dayKey })
        assertEquals(3, result.sumOf { it.messageCount })
        assertEquals(GroupStatDay("2026-09-27", 3, 2), result[1])
        assertEquals(6, result.count { it.messageCount == 0 })
        // Average is over seven days, not just the one active day.
        assertEquals(3.0 / 7, result.sumOf { it.messageCount }.toDouble() / result.size, 0.000001)
    }

    @Test
    fun emptyHistoryStillProducesSevenZeroDays() {
        val result = GroupStatsCalculator.completeDays(GroupStatsCalculator.dayKeys(now), emptyList())
        assertEquals(7, result.size)
        assertTrue(result.all { it.messageCount == 0 && it.senderCount == 0 })
    }

    @Test
    fun uniqueAuthorsAreNotSummedAcrossDays() {
        assertEquals(3, GroupStatsCalculator.uniqueSenders(listOf("a,b", "b,c", " a, b ,, ")))
        assertEquals(0, GroupStatsCalculator.uniqueSenders(listOf("", " , , ")))
    }

    @Test
    fun channelActivityCountsPostsNotCommentsOrViews() {
        fun post(id: String, date: String, author: String) = ChannelPostStat(
            topicId = id,
            publishedAtMs = Instant.parse(date).toEpochMilli(),
            authorId = author,
            commentCount = 200,
            viewCount = 500,
        )
        val result = GroupStatsCalculator.publicationDays(GroupStatsCalculator.dayKeys(now), listOf(
            post("old", "2026-09-25T12:00:00Z", "a"),
            post("p1", "2026-10-01T10:00:00Z", "a"),
            post("p2", "2026-10-01T11:00:00Z", "a"),
            post("p3", "2026-10-01T12:00:00Z", "b"),
            post("future", "2026-10-03T12:00:00Z", "a"),
        ))
        assertEquals(7, result.size)
        assertEquals(GroupStatDay("2026-10-01", 3, 2), result[5])
        assertEquals(3, result.sumOf { it.messageCount })
    }

    @Test
    fun usesUtcEvenAtDayBoundary() {
        assertEquals("2026-10-01", GroupStatsCalculator.dayKey(Instant.parse("2026-10-02T00:30:00+03:00").toEpochMilli()))
        val keys = GroupStatsCalculator.dayKeys(Instant.parse("2026-10-02T00:00:00Z").toEpochMilli(), 1)
        assertEquals(listOf("2026-10-02"), keys)
    }

    @Test
    fun calendarRangeHandlesLeapDay() {
        val leapDay = Instant.parse("2024-03-01T00:01:00Z").toEpochMilli()
        assertEquals(listOf("2024-02-28", "2024-02-29", "2024-03-01"), GroupStatsCalculator.dayKeys(leapDay, 3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyPeriodIsRejected() {
        GroupStatsCalculator.dayKeys(now, 0)
    }
}
