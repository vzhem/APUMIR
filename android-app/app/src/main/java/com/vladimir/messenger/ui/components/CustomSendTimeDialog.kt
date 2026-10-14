package com.vladimir.messenger.ui.components

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.R
import com.vladimir.messenger.data.schedule.SendTimePolicy
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Shared by personal chats, groups (including topics) and channels. */
@Composable
fun CustomSendTimeDialog(onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val initial = remember { ZonedDateTime.now(zone).plusMinutes(15) }
    var date by rememberSaveable { mutableStateOf(initial.format(SendTimePolicy.dateFormat)) }
    var time by rememberSaveable { mutableStateOf(initial.format(SendTimePolicy.timeFormat)) }
    var picker by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf(false) }
    val instant = SendTimePolicy.resolve(date, time, zone)

    if (picker == "date") {
        DisposableEffect(context) {
            val selected = runCatching { LocalDate.parse(date, SendTimePolicy.dateFormat) }
                .getOrDefault(initial.toLocalDate())
            val dialog = DatePickerDialog(context, { _, year, month, day ->
                date = LocalDate.of(year, month + 1, day).format(SendTimePolicy.dateFormat)
                error = false
            }, selected.year, selected.monthValue - 1, selected.dayOfMonth)
            dialog.setOnDismissListener { picker = "" }
            dialog.show()
            onDispose { dialog.setOnDismissListener(null); dialog.dismiss() }
        }
    }
    if (picker == "time") {
        DisposableEffect(context) {
            val selected = runCatching { LocalTime.parse(time, SendTimePolicy.timeFormat) }
                .getOrDefault(initial.toLocalTime())
            val dialog = TimePickerDialog(context, { _, hour, minute ->
                time = LocalTime.of(hour, minute).format(SendTimePolicy.timeFormat)
                error = false
            }, selected.hour, selected.minute, true)
            dialog.setOnDismissListener { picker = "" }
            dialog.show()
            onDispose { dialog.setOnDismissListener(null); dialog.dismiss() }
        }
    }

    ApuSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.send_custom_time)) },
        confirmButton = {
            TextButton(onClick = {
                // Recheck the clock on confirmation: the dialog may have stayed open for hours.
                val at = SendTimePolicy.resolve(date, time, zone)
                if (SendTimePolicy.isFuture(at, System.currentTimeMillis())) onConfirm(requireNotNull(at))
                else error = true
            }) { Text(stringResource(R.string.send_schedule_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.voice_cancel)) }
        },
        text = {
            Column {
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it; error = false },
                    label = { Text(stringResource(R.string.send_date_label)) },
                    singleLine = true,
                    isError = error,
                )
                ApuActionBubble(stringResource(R.string.send_open_calendar), Icons.Filled.DateRange) {
                    picker = "date"
                }
                OutlinedTextField(
                    value = time,
                    onValueChange = { time = it; error = false },
                    label = { Text(stringResource(R.string.send_time_label)) },
                    singleLine = true,
                    isError = error,
                )
                ApuActionBubble(stringResource(R.string.send_open_clock), Icons.Filled.Schedule) {
                    picker = "time"
                }
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.send_time_zone, zone.id))
                if (instant != null) Text(stringResource(R.string.send_scheduled_at, formatSendTime(instant)))
                if (error) Text(stringResource(R.string.send_time_invalid), color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.send_schedule_delivery_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}
