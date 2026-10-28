package com.vladimir.messenger.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.vladimir.messenger.R
import com.vladimir.messenger.service.DeliveryAssurance
import com.vladimir.messenger.ui.components.ApuBubbleMutedColor
import com.vladimir.messenger.ui.components.ApuPremiumSwitch
import com.vladimir.messenger.ui.components.ApuSettingsCard
import com.vladimir.messenger.ui.components.ApuSettingsDivider
import com.vladimir.messenger.ui.components.ApuSettingsItem
import com.vladimir.messenger.ui.components.ApuTextAction

/**
 * «Уведомления приходят вовремя» — то же, что у Telegram экран «Фоновое
 * соединение»: одно место, где видно, какие системные разрешения мешают
 * письму разбудить телефон, и куда нажать, чтобы это исправить.
 *
 * Почему так, а не «просто галочка». На Android письмо после убийства
 * приложения доставляет не сам мессенджер, а система — но только если ей
 * разрешено: уведомления включены, приложение не в оптимизации батареи,
 * будильники точные, на части прошивок ещё и автозапуск разрешён. Раньше
 * человек узнавал об этом, только не получая сообщений, и причин было
 * не видно. Здесь каждая причина названа и ведёт в свой системный экран.
 *
 * Заголовка раздела здесь нет намеренно: карточка стоит первой в разделе
 * «Уведомления», и второй золотой заголовок подряд читался бы как дубль.
 *
 * Компонент живёт в отдельном файле: `SettingsScreen.kt` уже за потолком
 * god-файла (AGENTS.md п. 8), поэтому туда добавлен только вызов.
 */
@Composable
fun DeliveryAssuranceCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var status by remember { mutableStateOf(DeliveryAssurance.status(context)) }
    // Разрешения меняются в системных настройках, а не в приложении:
    // перечитываем при каждом возвращении на экран.
    LifecycleResumeEffect(Unit) {
        status = DeliveryAssurance.status(context)
        onPauseOrDispose { }
    }

    ApuSettingsCard(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.delivery_switch_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                ApuPremiumSwitch(
                    checked = status.enabled,
                    onCheckedChange = { value ->
                        DeliveryAssurance.setEnabled(context, value)
                        status = DeliveryAssurance.status(context)
                    },
                    enabled = true,
                )
            }
            Text(
                text = stringResource(R.string.delivery_switch_hint),
                style = MaterialTheme.typography.bodySmall,
                color = ApuBubbleMutedColor,
            )
            Text(
                text = if (status.onTime) {
                    stringResource(R.string.delivery_state_ready)
                } else {
                    stringResource(R.string.delivery_state_needs, status.blocking.size)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (status.onTime) ApuBubbleMutedColor else MaterialTheme.colorScheme.error,
            )
        }
        ApuSettingsDivider()
        // Пункты-разрешения. Порядок — по важности: без уведомлений остальное
        // бессмысленно, без батареи система отложит пробуждение.
        PermissionRow(
            icon = Icons.Default.NotificationsActive,
            titleRes = R.string.delivery_item_notifications,
            granted = status.notificationsAllowed,
            fixLabelRes = R.string.delivery_action_allow,
            onFix = { DeliveryAssurance.openSystemSettings(context, DeliveryAssurance.Item.NOTIFICATIONS) },
        )
        ApuSettingsDivider()
        PermissionRow(
            icon = Icons.Default.BatteryAlert,
            titleRes = R.string.delivery_item_battery,
            granted = status.batteryUnrestricted,
            fixLabelRes = R.string.delivery_action_allow,
            onFix = { DeliveryAssurance.openSystemSettings(context, DeliveryAssurance.Item.BATTERY) },
        )
        ApuSettingsDivider()
        PermissionRow(
            icon = Icons.Default.Schedule,
            titleRes = R.string.delivery_item_exact_alarms,
            granted = status.exactAlarmsAllowed,
            fixLabelRes = R.string.delivery_action_open,
            onFix = { DeliveryAssurance.openSystemSettings(context, DeliveryAssurance.Item.EXACT_ALARMS) },
        )
        ApuSettingsDivider()
        // Автозапуск нельзя проверить из кода: показываем подсказку и кнопку
        // «настройки приложения», где он живёт на большинстве прошивок.
        PermissionRow(
            icon = Icons.Default.WifiTethering,
            titleRes = R.string.delivery_item_background,
            granted = null,
            fixLabelRes = R.string.delivery_action_open,
            onFix = { DeliveryAssurance.openSystemSettings(context, DeliveryAssurance.Item.BACKGROUND) },
        )
        ApuSettingsDivider()
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.delivery_wake_count, status.wakes),
                style = MaterialTheme.typography.bodySmall,
                color = ApuBubbleMutedColor,
            )
            Text(
                text = stringResource(R.string.delivery_footer),
                style = MaterialTheme.typography.bodySmall,
                color = ApuBubbleMutedColor,
            )
        }
    }
}

/**
 * Один пункт-разрешение.
 *
 * [granted] = null означает «проверить нельзя» (автозапуск на прошивке):
 * тогда показывается только кнопка «Открыть настройки», без приговора
 * «не разрешено» — иначе экран вечно выглядел бы сломанным.
 */
@Composable
private fun PermissionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    titleRes: Int,
    granted: Boolean?,
    fixLabelRes: Int,
    onFix: () -> Unit,
) {
    ApuSettingsItem(
        icon = icon,
        title = stringResource(titleRes),
        subtitle = when (granted) {
            true -> stringResource(R.string.delivery_item_granted)
            false -> stringResource(R.string.delivery_item_denied)
            null -> stringResource(R.string.delivery_item_unknown)
        },
        onClick = null,
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (granted == true) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                if (granted != true) {
                    ApuTextAction(label = stringResource(fixLabelRes), onClick = onFix)
                }
            }
        },
    )
}
