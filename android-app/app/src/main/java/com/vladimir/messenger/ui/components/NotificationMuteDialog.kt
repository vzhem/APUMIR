package com.vladimir.messenger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.vladimir.messenger.data.notification.NotificationMuteDuration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Общий выбор срока отключения уведомлений для настроек, чатов, групп,
 * каналов и отдельных тем.
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
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 380.dp),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Отключить уведомления",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    targetName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(5.dp))
                NotificationMuteDuration.entries.forEach { duration ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onSelectUntil(duration.deadlineFrom(nowMs)) }
                            .padding(horizontal = 12.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            duration.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (duration == NotificationMuteDuration.FOREVER) {
                                FontWeight.SemiBold
                            } else {
                                FontWeight.Medium
                            },
                            color = if (duration == NotificationMuteDuration.FOREVER) {
                                Color(0xFFE53950)
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }

                if (mutedUntilMs > nowMs) {
                    Text(
                        notificationMuteStatus(mutedUntilMs, nowMs),
                        modifier = Modifier.padding(start = 12.dp, top = 2.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ApuTextAction(
                        label = "Включить уведомления",
                        onClick = onTurnOn,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ApuTextAction(
                    label = "Отменить",
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
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
