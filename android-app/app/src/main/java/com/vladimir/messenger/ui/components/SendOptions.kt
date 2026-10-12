package com.vladimir.messenger.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.R
import com.vladimir.messenger.data.schedule.ScheduledSendStore
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** Выбор отправки: сейчас или в назначенное время, со звуком или без. */
data class SendChoice(val quiet: Boolean, val sendAtMs: Long?)

/** Диалог «Ещё» рядом с кнопкой «Отправить». */
@Composable
fun SendOptionsDialog(
    onDismiss: () -> Unit,
    onPick: (SendChoice) -> Unit,
) {
    var quiet by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    ApuSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.send_options_title)) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.voice_cancel)) }
        },
        text = {
            Column {
                ApuActionBubble(
                    label = if (quiet) stringResource(R.string.send_quiet_on) else stringResource(R.string.send_quiet_off),
                    icon = Icons.Filled.NotificationsOff,
                ) { quiet = !quiet }
                Spacer(Modifier.height(6.dp))
                ApuActionBubble(stringResource(R.string.send_now), Icons.AutoMirrored.Filled.Send) {
                    onPick(SendChoice(quiet, null))
                }
                ApuActionBubble(stringResource(R.string.send_in_15), Icons.Filled.Schedule) {
                    onPick(SendChoice(quiet, now + 15 * 60_000L))
                }
                ApuActionBubble(stringResource(R.string.send_in_hour), Icons.Filled.Schedule) {
                    onPick(SendChoice(quiet, now + 60 * 60_000L))
                }
                ApuActionBubble(stringResource(R.string.send_tomorrow), Icons.Filled.Schedule) {
                    onPick(SendChoice(quiet, tomorrowNineMs(now)))
                }
            }
        },
    )
}

/** Список отложенных сообщений над полем ввода: время и «Отменить». */
@Composable
fun ScheduledSendBanner(
    items: List<ScheduledSendStore.Item>,
    onCancel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        items.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.send_scheduled_at, formatSendTime(item.sendAtMs)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onCancel(item.id) }) {
                    Text(stringResource(R.string.send_scheduled_cancel))
                }
            }
        }
    }
}

fun formatSendTime(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

/** Завтра в 9:00 по местному времени. */
fun tomorrowNineMs(nowMs: Long): Long {
    val cal = Calendar.getInstance().apply {
        timeInMillis = nowMs
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 9)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}
