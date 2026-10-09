package com.vladimir.messenger.ui.components

import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.data.notification.NotificationMuteDuration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Общий выбор срока отключения уведомлений для настроек, чатов, групп,
 * каналов и отдельных тем. Использует тот же диалог и палитру APU, что и
 * остальные экраны приложения.
 */
@Composable
fun NotificationMuteDialog(
    targetName: String,
    mutedUntilMs: Long,
    onSelectUntil: (Long) -> Unit,
    onTurnOn: () -> Unit,
    onDismiss: () -> Unit,
) {
    val nowMs = System.currentTimeMillis()
    val isMuted = mutedUntilMs > nowMs

    ApuSettingsDialog(
        onDismissRequest = onDismiss,
        icon = {
            ApuPremiumIconTile(
                icon = if (isMuted) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff,
                size = 34.dp,
                corner = 10.dp,
            )
        },
        title = {
            Text(
                stringResource(R.string.menu_mute),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    targetName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ApuBubbleMutedColor,
                )
                Text(
                    stringResource(R.string.nm_duration),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = ApuBubbleTextColor,
                    modifier = Modifier.padding(top = 4.dp),
                )

                NotificationMuteDuration.entries.forEach { duration ->
                    ApuPremiumChoiceRow(
                        title = duration.label,
                        subtitle = if (duration == NotificationMuteDuration.FOREVER) {
                            stringResource(R.string.nm_manual)
                        } else {
                            stringResource(R.string.nm_auto)
                        },
                        selected = false,
                        onClick = { onSelectUntil(duration.deadlineFrom(nowMs)) },
                    )
                }

                if (isMuted) {
                    ApuSettingsChip(
                        text = notificationMuteStatus(mutedUntilMs, nowMs),
                        highlighted = false,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    ApuTextAction(
                        label = stringResource(R.string.menu_unmute),
                        onClick = onTurnOn,
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Default.NotificationsActive,
                    )
                }
            }
        },
        confirmButton = {
            ApuTextAction(
                label = stringResource(R.string.call_cancel),
                onClick = onDismiss,
            )
        },
    )
}

/** Короткий статус для списка настроек и диалога отключения. */
fun notificationMuteStatus(
    mutedUntilMs: Long,
    nowMs: Long = System.currentTimeMillis(),
): String {
    if (mutedUntilMs <= nowMs) return "Уведомления включены"
    if (mutedUntilMs == Long.MAX_VALUE) return "Уведомления отключены навсегда"
    val locale = Locale.getDefault()
    val until = Date(mutedUntilMs)
    val today = SimpleDateFormat("yyyyMMdd", locale).format(Date(nowMs))
    val targetDay = SimpleDateFormat("yyyyMMdd", locale).format(until)
    return if (today == targetDay) {
        "Отключены до ${SimpleDateFormat("HH:mm", locale).format(until)}"
    } else {
        "Отключены до ${SimpleDateFormat("dd.MM, HH:mm", locale).format(until)}"
    }
}
