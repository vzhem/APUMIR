package com.vladimir.messenger.ui.screens.contacts

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Подписи присутствия на выбранном языке. Шаблоны с %1$s подставляют время или дату;
 * шаблоны дат — строки SimpleDateFormat, локализуемые через ресурсы.
 */
internal data class PresenceLabels(
    val online: String,
    val unknown: String,
    val today: String,
    val yesterday: String,
    val date: String,
    val timePattern: String,
    val datePattern: String,
    val dateYearPattern: String,
    val locale: Locale,
) {
    companion object {
        /** Русский набор: используется по умолчанию (в том числе в тестах). */
        val RUSSIAN = PresenceLabels(
            online = "В сети",
            unknown = "Последняя активность неизвестна",
            today = "Был(а) сегодня в %1$s",
            yesterday = "Был(а) вчера в %1$s",
            date = "Был(а) %1$s",
            timePattern = "HH:mm",
            datePattern = "d MMMM 'в' HH:mm",
            dateYearPattern = "d MMMM yyyy 'в' HH:mm",
            locale = Locale("ru"),
        )
    }
}

/** Подписи присутствия на языке приложения (вызывать из composable). */
@Composable
internal fun presenceLabels(): PresenceLabels = PresenceLabels(
    online = stringResource(R.string.presence_online),
    unknown = stringResource(R.string.presence_unknown),
    today = stringResource(R.string.presence_today_tpl),
    yesterday = stringResource(R.string.presence_yesterday_tpl),
    date = stringResource(R.string.presence_date_tpl),
    timePattern = stringResource(R.string.presence_pattern_time),
    datePattern = stringResource(R.string.presence_pattern_date),
    dateYearPattern = stringResource(R.string.presence_pattern_date_year),
    locale = LocalConfiguration.current.locales[0],
)

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
    labels: PresenceLabels = PresenceLabels.RUSSIAN,
    timeZone: TimeZone = TimeZone.getDefault(),
): String {
    val locale = labels.locale
    if (isOnline) return labels.online
    val seenAtMs = lastSeenAtMs?.takeIf { it > 0L }
        ?: return labels.unknown
    // A phone-clock correction must not render a future date as a last visit.
    val displayedAtMs = seenAtMs.coerceAtMost(nowMs)

    val now = Calendar.getInstance(timeZone, locale).apply { timeInMillis = nowMs }
    val seen = Calendar.getInstance(timeZone, locale).apply { timeInMillis = displayedAtMs }
    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }

    val pattern = when {
        sameDay(seen, now) || sameDay(seen, yesterday) -> labels.timePattern
        seen.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> labels.datePattern
        else -> labels.dateYearPattern
    }
    val formatted = SimpleDateFormat(pattern, locale).apply {
        this.timeZone = timeZone
    }.format(Date(displayedAtMs))
    return when {
        sameDay(seen, now) -> labels.today.format(formatted)
        sameDay(seen, yesterday) -> labels.yesterday.format(formatted)
        else -> labels.date.format(formatted)
    }
}

private fun sameDay(left: Calendar, right: Calendar): Boolean =
    left.get(Calendar.ERA) == right.get(Calendar.ERA) &&
        left.get(Calendar.YEAR) == right.get(Calendar.YEAR) &&
        left.get(Calendar.DAY_OF_YEAR) == right.get(Calendar.DAY_OF_YEAR)
