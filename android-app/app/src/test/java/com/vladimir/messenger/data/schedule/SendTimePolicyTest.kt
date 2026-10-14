package com.vladimir.messenger.data.schedule

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class SendTimePolicyTest {
    private val moscow = ZoneId.of("Europe/Moscow")

    @Test fun arbitraryDateAndMinuteUseLocalZone() {
        assertEquals(Instant.parse("2027-02-19T20:47:00Z").toEpochMilli(),
            SendTimePolicy.resolve("19.02.2027", "23:47", moscow))
    }

    @Test fun leapDayIsStrict() {
        assertNotNull(SendTimePolicy.resolve("29.02.2028", "00:00", moscow))
        assertNull(SendTimePolicy.resolve("29.02.2027", "00:00", moscow))
        assertNull(SendTimePolicy.resolve("31.04.2027", "12:00", moscow))
    }

    @Test fun malformedDatesAndTimesAreRejected() {
        for ((date, time) in listOf("" to "", "14.10.2026" to "24:00",
            "14.10.2026" to "12:60", "garbage" to "12:00", "01.01.0000" to "12:00")) {
            assertNull(SendTimePolicy.resolve(date, time, moscow))
        }
    }

    @Test fun futureCheckUsesConfirmationClock() {
        val selected = requireNotNull(SendTimePolicy.resolve("14.10.2026", "09:00", moscow))
        assertTrue(SendTimePolicy.isFuture(selected, selected - 1))
        assertFalse(SendTimePolicy.isFuture(selected, selected))
        assertFalse(SendTimePolicy.isFuture(selected, selected + 60_000))
        assertFalse(SendTimePolicy.isFuture(null, selected))
    }

    @Test fun nonexistentDstTimeIsNotSilentlyMoved() {
        assertNull(SendTimePolicy.resolve("29.03.2026", "02:30", ZoneId.of("Europe/Berlin")))
    }

    @Test fun repeatedDstHourUsesEarlierOccurrence() {
        assertEquals(Instant.parse("2026-10-25T00:30:00Z").toEpochMilli(),
            SendTimePolicy.resolve("25.10.2026", "02:30", ZoneId.of("Europe/Berlin")))
    }

    @Test fun noPresetHorizonAndYearBoundary() {
        assertNotNull(SendTimePolicy.resolve("31.12.2099", "23:59", moscow))
        assertNotNull(SendTimePolicy.resolve("01.01.2101", "00:00", moscow))
    }
}
