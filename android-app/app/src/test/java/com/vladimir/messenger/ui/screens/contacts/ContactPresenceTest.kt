package com.vladimir.messenger.ui.screens.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class ContactPresenceTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val now = at(2026, Calendar.OCTOBER, 6, 12, 0)

    @Test
    fun onlineContactIsShownAsOnlineRegardlessOfTheSavedTimestamp() {
        assertEquals(
            "В сети",
            contactPresenceLabel(
                isOnline = true,
                lastSeenAtMs = now - 60_000L,
                nowMs = now,
                timeZone = utc,
            ),
        )
    }

    @Test
    fun offlineContactShowsTodayAndYesterdayWithExactTime() {
        assertEquals(
            "Был(а) сегодня в 05:26",
            contactPresenceLabel(
                isOnline = false,
                lastSeenAtMs = at(2026, Calendar.OCTOBER, 6, 5, 26),
                nowMs = now,
                timeZone = utc,
            ),
        )
        assertEquals(
            "Был(а) вчера в 23:59",
            contactPresenceLabel(
                isOnline = false,
                lastSeenAtMs = at(2026, Calendar.OCTOBER, 5, 23, 59),
                nowMs = now,
                timeZone = utc,
            ),
        )
    }

    @Test
    fun unknownOrFutureTimestampDoesNotInventActivity() {
        assertEquals(
            "Последняя активность неизвестна",
            contactPresenceLabel(
                isOnline = false,
                lastSeenAtMs = null,
                nowMs = now,
                timeZone = utc,
            ),
        )
        assertEquals(
            "Был(а) сегодня в 12:00",
            contactPresenceLabel(
                isOnline = false,
                lastSeenAtMs = now + 60_000L,
                nowMs = now,
                timeZone = utc,
            ),
        )
    }

    @Test
    fun anOlderYearIncludesTheYearInsteadOfAnAmbiguousDate() {
        val label = contactPresenceLabel(
            isOnline = false,
            lastSeenAtMs = at(2025, Calendar.DECEMBER, 31, 18, 40),
            nowMs = now,
            timeZone = utc,
        )

        assertTrue(label.startsWith("Был(а) "))
        assertTrue(label.contains("2025"))
        assertTrue(label.endsWith("18:40"))
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(utc, Locale("ru")).apply {
            clear()
            set(year, month, day, hour, minute)
        }.timeInMillis
}
