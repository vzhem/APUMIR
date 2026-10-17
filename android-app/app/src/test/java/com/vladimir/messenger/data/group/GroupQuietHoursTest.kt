package com.vladimir.messenger.data.group

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class GroupQuietHoursTest {
    private fun at(value: String) = Instant.parse(value).toEpochMilli()

    @Test fun overnightBelongsToStartingWeekdayAndEndsExclusively() {
        val schedule = GroupQuietHours(setOf(1), 23 * 60, 8 * 60, "Europe/Moscow")
        assertFalse(schedule.blocks(at("2026-10-12T19:59:59Z")))
        assertTrue(schedule.blocks(at("2026-10-12T20:00:00Z")))
        assertTrue(schedule.blocks(at("2026-10-13T04:59:59Z")))
        assertFalse(schedule.blocks(at("2026-10-13T05:00:00Z")))
        assertFalse(schedule.blocks(at("2026-10-13T20:00:00Z")))
    }

    @Test fun sundayWrapsIntoMonday() {
        val schedule = GroupQuietHours(setOf(7), 1380, 480, "UTC")
        assertTrue(schedule.blocks(at("2026-10-12T07:59:59Z")))
        assertFalse(schedule.blocks(at("2026-10-12T08:00:00Z")))
    }

    @Test fun daytimeAndRoundTrip() {
        val schedule = GroupQuietHours(setOf(1, 3, 5), 600, 660, "UTC")
        assertEquals(schedule, GroupQuietHours.decode(schedule.encode()))
        assertTrue(schedule.blocks(at("2026-10-12T10:00:00Z")))
        assertFalse(schedule.blocks(at("2026-10-12T11:00:00Z")))
        assertFalse(schedule.blocks(at("2026-10-13T10:30:00Z")))
    }

    @Test fun repeatedHourUsesGroupsZoneRatherThanPhoneZone() {
        val schedule = GroupQuietHours(setOf(7), 120, 180, "Europe/Berlin")
        assertTrue(schedule.blocks(at("2026-10-25T00:30:00Z")))
        assertTrue(schedule.blocks(at("2026-10-25T01:30:00Z")))
        assertFalse(schedule.blocks(at("2026-10-25T02:00:00Z")))
    }

    @Test fun rejectsInvalidSettings() {
        listOf("", "1;60;60;UTC", "8;0;60;UTC", "1;-1;60;UTC", "1;0;1440;UTC", "1;0;60;Bad/Zone").forEach {
            assertNull(GroupQuietHours.decode(it))
        }
        assertEquals(1439, GroupQuietHours.minute("23:59"))
        assertEquals("08:00", GroupQuietHours.time(480))
        assertTrue(runCatching { GroupQuietHours.minute("24:00") }.isFailure)
    }

    @Test fun wireRoundTripAndDisable() {
        val raw = GroupQuietHours(setOf(1), 1380, 480, "Europe/Moscow").encode()
        assertEquals(GroupWire.Packet.QuietHours("group", raw), GroupWire.parse(GroupWire.buildQuietHours("group", raw)))
        assertEquals(GroupWire.Packet.QuietHours("group", ""), GroupWire.parse(GroupWire.buildQuietHours("group", "")))
        assertEquals(GroupWire.Packet.QuietHoursRequest("group"), GroupWire.parse(GroupWire.buildQuietHoursRequest("group")))
    }

    @Test fun multipleIntervalsHaveIndependentDaysAndCanOverlap() {
        val first = GroupQuietHours(setOf(1), 600, 720, "UTC")
        val overlap = GroupQuietHours(setOf(1), 660, 780, "UTC")
        val weekend = GroupQuietHours(setOf(7), 60, 600, "UTC")
        val schedule = GroupQuietSchedule(listOf(first, overlap, weekend))
        assertTrue(schedule.blocks(at("2026-10-12T12:59:59Z")))
        assertFalse(schedule.blocks(at("2026-10-12T13:00:00Z")))
        assertFalse(schedule.blocks(at("2026-10-13T11:00:00Z")))
        assertTrue(schedule.blocks(at("2026-10-18T09:59:59Z")))
        assertEquals(schedule, GroupQuietSchedule.decode(schedule.encode()))
        assertEquals(GroupQuietSchedule(listOf(first)), GroupQuietSchedule.decode(first.encode()))
        assertEquals(GroupWire.Packet.QuietHours("group", schedule.encode()),
            GroupWire.parse(GroupWire.buildQuietHours("group", schedule.encode())))
        assertNull(GroupQuietSchedule.decode(first.encode() + "~bad"))
        assertTrue(runCatching { GroupQuietSchedule(emptyList()) }.isFailure)
    }

    @Test fun participantsSeeSameEndInTheirOwnLocalTimes() {
        val schedule = GroupQuietSchedule(listOf(GroupQuietHours(setOf(1), 1380, 480, "Europe/Moscow")))
        val now = at("2026-10-12T21:00:00Z")
        assertEquals(at("2026-10-13T05:00:00Z"), schedule.nextChangeAtMs(now))
        val ru = java.util.Locale.forLanguageTag("ru")
        assertTrue(schedule.notice(now, java.time.ZoneId.of("Europe/Moscow"), ru).contains("13.10.2026 08:00"))
        assertTrue(schedule.notice(now, java.time.ZoneId.of("Asia/Yekaterinburg"), ru).contains("13.10.2026 10:00"))
        assertTrue(schedule.notice(now, java.time.ZoneId.of("America/Los_Angeles"), ru).contains("12.10.2026 22:00"))
    }

    @Test fun noticeWaitsForLastOverlappingOrTouchingInterval() {
        val schedule = GroupQuietSchedule(listOf(
            GroupQuietHours(setOf(1), 600, 720, "UTC"),
            GroupQuietHours(setOf(1), 690, 780, "UTC"),
            GroupQuietHours(setOf(1), 780, 840, "UTC"),
        ))
        assertEquals(at("2026-10-12T14:00:00Z"), schedule.nextChangeAtMs(at("2026-10-12T10:00:00Z")))
        assertEquals(at("2026-10-12T10:00:00Z"), schedule.nextChangeAtMs(at("2026-10-12T09:00:00Z")))
    }

    @Test fun repeatedHourAndSpringJumpHaveActualTimelineBoundaries() {
        val schedule = GroupQuietSchedule(listOf(GroupQuietHours(setOf(7), 120, 150, "Europe/Berlin")))
        assertEquals(at("2026-10-25T00:30:00Z"), schedule.nextChangeAtMs(at("2026-10-25T00:10:00Z")))
        assertEquals(at("2026-10-25T01:00:00Z"), schedule.nextChangeAtMs(at("2026-10-25T00:40:00Z")))
        assertEquals(at("2026-10-25T01:30:00Z"), schedule.nextChangeAtMs(at("2026-10-25T01:10:00Z")))
        val spring = GroupQuietSchedule(listOf(GroupQuietHours(setOf(7), 90, 150, "Europe/Berlin")))
        assertEquals(at("2026-03-29T01:00:00Z"), spring.nextChangeAtMs(at("2026-03-29T00:40:00Z")))
    }

    @Test fun continuouslyBlockedWeekHasNoInventedEnd() {
        val all = (1..7).toSet()
        val schedule = GroupQuietSchedule(listOf(GroupQuietHours(all, 0, 720, "UTC"), GroupQuietHours(all, 720, 0, "UTC")))
        assertNull(schedule.nextChangeAtMs(at("2026-10-12T12:00:00Z")))
        assertTrue(schedule.notice(at("2026-10-12T12:00:00Z"), java.time.ZoneId.of("UTC"), java.util.Locale.forLanguageTag("ru")).contains("непрерывно"))
    }
}
