package com.vladimir.messenger.ui.screens.groups

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.R
import com.vladimir.messenger.ui.i18n.AppLanguageHolder
import com.vladimir.messenger.data.group.GroupQuietHours
import com.vladimir.messenger.data.group.GroupQuietSchedule
import com.vladimir.messenger.ui.components.ApuBubble
import com.vladimir.messenger.ui.components.ApuSettingsDialog
import com.vladimir.messenger.ui.components.ApuFormTextField
import com.vladimir.messenger.ui.components.ApuTextAction
import com.vladimir.messenger.ui.components.ApuPremiumSwitch
import com.vladimir.messenger.ui.components.ApuPremiumChip
import com.vladimir.messenger.ui.components.ApuPremiumButton
import com.vladimir.messenger.ui.components.DiagnosticsActionStyle

private data class QuietIntervalDraft(
    val days: Set<Int> = (1..7).toSet(),
    val start: String = "23:00",
    val end: String = "08:00",
)

/** All intervals use the group's explicit timezone, not each viewer's phone timezone. */
@Composable
internal fun GroupQuietHoursSettings(raw: String, canEdit: Boolean, onSave: (String) -> Unit) {
    val language by AppLanguageHolder.language.collectAsState()
    val locale = remember(language) { java.util.Locale.forLanguageTag(language.code) }
    val stored = remember(raw) { GroupQuietSchedule.decode(raw) }
    val localScheduleNotice = rememberGroupScheduleNotice(stored)
    var enabled by remember(raw) { mutableStateOf(stored != null) }
    var drafts by remember(raw) {
        mutableStateOf(stored?.intervals?.map {
            QuietIntervalDraft(it.days, GroupQuietHours.time(it.startMinute), GroupQuietHours.time(it.endMinute))
        } ?: listOf(QuietIntervalDraft()))
    }
    var zone by remember(raw) { mutableStateOf(stored?.intervals?.first()?.zoneId ?: "Europe/Moscow") }
    var error by remember { mutableStateOf<String?>(null) }
    var chooseZone by remember { mutableStateOf(false) }
    var zoneSearch by remember { mutableStateOf("") }
    val zoneDisplayTime = remember(chooseZone, locale) { java.time.Instant.now() }
    val zoneLabel = remember(zone, locale, zoneDisplayTime) { GroupTimeZones.entry(zone, locale, zoneDisplayTime).label }
    val dayNames = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
    fun update(index: Int, draft: QuietIntervalDraft) {
        drafts = drafts.mapIndexed { i, value -> if (i == index) draft else value }
    }
    if (chooseZone) {
        val zones = remember(locale, zoneDisplayTime) { GroupTimeZones.entries(locale, zoneDisplayTime) }
        ApuSettingsDialog(
            onDismissRequest = { chooseZone = false },
            title = { Text(stringResource(R.string.group_timezone_title)) },
            text = {
                Column {
                    ApuFormTextField(value = zoneSearch, onValueChange = { zoneSearch = it }, label = stringResource(R.string.group_timezone_search))
                    Text(stringResource(R.string.group_timezone_offset_hint))
                    if (zones.none { it.matches(zoneSearch) }) Text(stringResource(R.string.group_timezone_not_found))
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(zones.filter { it.matches(zoneSearch) }, key = { it.id }) { entry ->
                            ApuTextAction(label = entry.label, onClick = { zone = entry.id; chooseZone = false })
                        }
                    }
                }
            },
            confirmButton = { ApuTextAction(label = stringResource(R.string.action_close), onClick = { chooseZone = false }) },
        )
    }
    ApuBubble {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Расписание запрета сообщений")
            Text("Для обычных участников во всех темах. Владелец и администраторы могут писать.")
            if (!canEdit) {
                if (stored == null) Text("Выключено")
                if (localScheduleNotice.isNotBlank()) Text(localScheduleNotice)
                if (stored != null) Text(if (language.code == "en") "Original schedule in the group time zone:" else "Исходное расписание по часовому поясу группы:")
                stored?.intervals?.forEach { interval ->
                    Text("${interval.days.sorted().joinToString { dayNames[it - 1] }}: ${GroupQuietHours.time(interval.startMinute)}–${GroupQuietHours.time(interval.endMinute)} (${GroupTimeZones.entry(interval.zoneId, locale, zoneDisplayTime).label})")
                }
            } else {
                Row {
                    Text("Включено", Modifier.weight(1f))
                    ApuPremiumSwitch(checked = enabled, onCheckedChange = { enabled = it })
                }
                if (enabled) {
                    ApuTextAction(label = stringResource(R.string.group_timezone_selected, zoneLabel), onClick = { chooseZone = true })
                    Text("Каждому интервалу можно задать свои дни и любое время с точностью до минуты. Ночной интервал относится к дню начала: Пн 23:00–08:00 — до утра вторника.")
                    drafts.forEachIndexed { index, draft ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row {
                                Text("Интервал ${index + 1}", Modifier.weight(1f))
                                ApuTextAction(label = "Удалить", onClick = {
                                    drafts = drafts.filterIndexed { i, _ -> i != index }
                                })
                            }
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                dayNames.forEachIndexed { i, label ->
                                    val day = i + 1
                                    ApuPremiumChip(label = label, selected = day in draft.days,
                                        onClick = { update(index, draft.copy(days = if (day in draft.days) draft.days - day else draft.days + day)) })
                                }
                            }
                            ApuFormTextField(value = draft.start, onValueChange = { update(index, draft.copy(start = it)) }, label = "Нельзя писать с (ЧЧ:ММ)")
                            ApuFormTextField(value = draft.end, onValueChange = { update(index, draft.copy(end = it)) }, label = "Окончание запрета (ЧЧ:ММ)")
                        }
                    }
                    ApuTextAction(label = "Добавить интервал", enabled = drafts.size < 32,
                        onClick = { drafts = drafts + QuietIntervalDraft() })
                    Text("Пересекающиеся интервалы объединяются: писать можно, когда ни один запрет не действует.")
                }
                error?.let { Text(it) }
                ApuPremiumButton(label = "Сохранить расписание", style = DiagnosticsActionStyle.PRIMARY, onClick = {
                    val result = runCatching {
                        if (!enabled) "" else GroupQuietSchedule(drafts.map { draft ->
                            GroupQuietHours(draft.days, GroupQuietHours.minute(draft.start.trim()), GroupQuietHours.minute(draft.end.trim()), zone)
                        }).encode()
                    }
                    result.onSuccess { error = null; onSave(it) }
                        .onFailure { error = "Проверьте дни и время. ${it.message.orEmpty()}" }
                })
            }
        }
    }
}
