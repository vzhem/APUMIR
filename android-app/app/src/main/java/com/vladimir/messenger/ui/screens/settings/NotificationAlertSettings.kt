package com.vladimir.messenger.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeUp
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.R
import com.vladimir.messenger.data.notification.NotificationAlertScope
import com.vladimir.messenger.data.notification.NotificationAlertStore
import com.vladimir.messenger.ui.components.ApuBubbleMutedColor
import com.vladimir.messenger.ui.components.ApuPremiumSwitch
import com.vladimir.messenger.ui.components.ApuSettingsCard
import com.vladimir.messenger.ui.components.ApuSettingsDivider
import com.vladimir.messenger.ui.components.ApuSettingsItem

/**
 * Карточка «Звук и вибрация» в разделе «Настройки → Уведомления».
 *
 * Владелец 2026-11-17: «звуки и вибрации нужно тоже поставить во всех местах,
 * чтобы можно было настроить личные сообщения, группы, каналы… и так же общее
 * на приложение». Здесь четыре области: всё приложение сразу и три раздела.
 * У каждой — два независимых переключателя, потому что «звук» и «вибрация» —
 * разные вещи: на громкой телефон звучит, в режиме вибрации вибрирует.
 *
 * Отдельный файл рядом с `SettingsScreen.kt` — правило god-файлов
 * (`scripts/ci/check-god-files.py`): экран настроек и без того большой.
 */
@Composable
fun NotificationAlertSettingsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Настройки читаются из SharedPreferences: перерисовку после нажатия
    // двигает эта же карточка, поэтому локальной ревизии достаточно.
    var revision by remember { mutableStateOf(0) }
    // Чтение под remember(revision): после нажатия переключателя значения
    // перечитываются, а без ревизии Compose их бы не заметил.
    val sounds = NotificationAlertScope.entries.map { scope ->
        remember(scope, revision) { NotificationAlertStore.soundEnabled(context, scope) }
    }
    val vibrations = NotificationAlertScope.entries.map { scope ->
        remember(scope, revision) { NotificationAlertStore.vibrationEnabled(context, scope) }
    }

    ApuSettingsCard(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        ApuSettingsItem(
            icon = Icons.Default.NotificationsActive,
            title = stringResource(R.string.notification_alert_title),
            subtitle = stringResource(R.string.notification_alert_hint),
        )
        NotificationAlertScope.entries.forEachIndexed { index, scope ->
            ApuSettingsDivider()
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = scopeIcon(scope),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.size(10.dp))
                    Text(
                        text = stringResource(scope.titleRes),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                if (scope == NotificationAlertScope.APP) {
                    Text(
                        text = stringResource(R.string.notification_alert_app_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = ApuBubbleMutedColor,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Звук
                    Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        text = stringResource(R.string.notification_alert_sound),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    ApuPremiumSwitch(
                        checked = sounds[index],
                        onCheckedChange = { enabled ->
                            NotificationAlertStore.setSoundEnabled(context, scope, enabled)
                            revision += 1
                        },
                    )
                    Spacer(modifier = Modifier.size(14.dp))
                    // Вибрация
                    Icon(Icons.Default.Vibration, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        text = stringResource(R.string.notification_alert_vibrate),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    ApuPremiumSwitch(
                        checked = vibrations[index],
                        onCheckedChange = { enabled ->
                            NotificationAlertStore.setVibrationEnabled(context, scope, enabled)
                            revision += 1
                        },
                    )
                }
            }
        }
    }
}

/** Иконка раздела — та же, что у паузы уведомлений, чтобы не путать. */
private fun scopeIcon(scope: NotificationAlertScope): ImageVector = when (scope) {
    NotificationAlertScope.APP -> Icons.Default.Apps
    NotificationAlertScope.PERSONAL_CHATS -> Icons.Default.Person
    NotificationAlertScope.GROUPS -> Icons.Default.Groups
    NotificationAlertScope.CHANNELS -> Icons.Default.Campaign
}
