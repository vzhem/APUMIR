package com.vladimir.messenger.ui.screens.contacts

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Text shown under a contact's name.
 *
 * Presence is observed locally, so the timestamp means "this phone last saw
 * this contact online". It deliberately does not claim to know when the other
 * person opened their app while no route to them existed.
 */
internal fun contactPresenceLabel(
    isOnline: Boolean,
    lastSeenAtMs: Long?,
    nowMs: Long = System.currentTimeMillis(),
    locale: Locale = RUSSIAN_LOCALE,
    timeZone: TimeZone = TimeZone.getDefault(),
): String {
    if (isOnline) return "В сети"
    val seenAtMs = lastSeenAtMs?.takeIf { it > 0L }
        ?: return "Последняя активность неизвестна"
    // A phone-clock correction must not render a future date as a last visit.
    val displayedAtMs = seenAtMs.coerceAtMost(nowMs)

    val now = Calendar.getInstance(timeZone, locale).apply { timeInMillis = nowMs }
    val seen = Calendar.getInstance(timeZone, locale).apply { timeInMillis = displayedAtMs }
    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }

    val prefix = when {
        sameDay(seen, now) -> "Был(а) сегодня в "
        sameDay(seen, yesterday) -> "Был(а) вчера в "
        seen.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> "Был(а) "
        else -> "Был(а) "
    }
    val pattern = when {
        sameDay(seen, now) || sameDay(seen, yesterday) -> "HH:mm"
        seen.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> "d MMMM 'в' HH:mm"
        else -> "d MMMM yyyy 'в' HH:mm"
    }
    return prefix + SimpleDateFormat(pattern, locale).apply {
        this.timeZone = timeZone
    }.format(Date(displayedAtMs))
}

private fun sameDay(left: Calendar, right: Calendar): Boolean =
    left.get(Calendar.ERA) == right.get(Calendar.ERA) &&
        left.get(Calendar.YEAR) == right.get(Calendar.YEAR) &&
        left.get(Calendar.DAY_OF_YEAR) == right.get(Calendar.DAY_OF_YEAR)

private val RUSSIAN_LOCALE = Locale("ru")
