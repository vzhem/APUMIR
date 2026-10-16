package com.vladimir.messenger.ui.screens.groups

import java.time.Instant
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses the device's ICU/CLDR translations, not JVM timezone name substitutes. */
class GroupTimeZonesTest {
    private val winter = Instant.parse("2026-01-15T12:00:00Z")
    private val summer = Instant.parse("2026-07-15T12:00:00Z")

    @Test fun russianCountryCityAndOffsetAreSearchable() {
        val entry = GroupTimeZones.entry("Europe/Moscow", Locale.forLanguageTag("ru"), winter)
        assertTrue(entry.label, entry.label.contains("Россия"))
        assertTrue(entry.label, entry.label.contains("Москва"))
        assertTrue(entry.label, entry.label.contains("UTC+03:00"))
        assertTrue(entry.matches("Москва"))
        assertTrue(entry.matches("россия москва"))
        assertTrue(entry.matches("Moscow"))
        assertEquals("Europe/Moscow", entry.id)
    }

    @Test fun englishAndFractionalOffsets() {
        val entry = GroupTimeZones.entry("Europe/Moscow", Locale.ENGLISH, winter)
        assertTrue(entry.label, entry.label.contains("Moscow"))
        assertTrue(GroupTimeZones.entry("Asia/Kathmandu", Locale.ENGLISH, winter).label.contains("UTC+05:45"))
        assertTrue(GroupTimeZones.entry("Asia/Kolkata", Locale.ENGLISH, winter).label.contains("UTC+05:30"))
    }

    @Test fun currentSeasonDeterminesNegativeOffset() {
        val id = "America/New_York"
        assertTrue(GroupTimeZones.entry(id, Locale.ENGLISH, winter).label.contains("UTC−05:00"))
        assertTrue(GroupTimeZones.entry(id, Locale.ENGLISH, summer).label.contains("UTC−04:00"))
    }

    @Test fun canonicalListHasNoDuplicateIdsAndOldAliasesRemainReadable() {
        val rows = GroupTimeZones.entries(Locale.forLanguageTag("ru"), winter)
        assertEquals(rows.size, rows.map { it.id }.toSet().size)
        assertTrue(rows.any { it.id == "Europe/Moscow" })
        val alias = GroupTimeZones.entry("Canada/Atlantic", Locale.forLanguageTag("ru"), winter)
        assertTrue(alias.label, alias.label.contains("Канада"))
        assertTrue(alias.label, alias.label.contains("UTC−04:00"))
        assertEquals("Canada/Atlantic", alias.id)
    }
}
