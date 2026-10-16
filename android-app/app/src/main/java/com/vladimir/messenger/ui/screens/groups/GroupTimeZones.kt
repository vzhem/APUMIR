package com.vladimir.messenger.ui.screens.groups

import android.icu.text.TimeZoneNames
import android.icu.util.TimeZone
import java.text.Collator
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Display-only localization: persisted/wire timezone remains the IANA ID. */
internal object GroupTimeZones {
    data class Entry(val id: String, val label: String) {
        fun matches(query: String): Boolean {
            val words = query.trim().split(Regex("\\s+"))
            return words.all { label.contains(it, ignoreCase = true) || id.contains(it, ignoreCase = true) }
        }
    }

    fun entry(id: String, locale: Locale, at: Instant): Entry {
        val canonical = TimeZone.getCanonicalID(id) ?: id
        val names = TimeZoneNames.getInstance(locale)
        val city = names.getExemplarLocationName(canonical)
            ?: TimeZone.getTimeZone(canonical).getDisplayName(false, TimeZone.LONG, locale)
        val region = runCatching { TimeZone.getRegion(canonical) }.getOrNull()
        val country = region?.takeIf { it != "001" && it != "ZZ" }
            ?.let { Locale("", it).getDisplayCountry(locale) }.orEmpty()
        val place = if (country.isBlank() || country == city) city else "$country — $city"
        val seconds = ZoneId.of(id).rules.getOffset(at).totalSeconds
        val absoluteMinutes = kotlin.math.abs(seconds) / 60
        val offset = String.format(Locale.ROOT, "UTC%s%02d:%02d", if (seconds < 0) "−" else "+",
            absoluteMinutes / 60, absoluteMinutes % 60)
        return Entry(id, "$place · $offset")
    }

    fun entries(locale: Locale, at: Instant): List<Entry> {
        val available = ZoneId.getAvailableZoneIds()
        // Hide legacy duplicates such as Canada/Atlantic, retaining canonical zones.
        val ids = available.map { id ->
            val canonical = TimeZone.getCanonicalID(id)
            if (canonical != null && canonical in available) canonical else id
        }.distinct()
        val collator = Collator.getInstance(locale)
        return ids.map { entry(it, locale, at) }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
    }
}
