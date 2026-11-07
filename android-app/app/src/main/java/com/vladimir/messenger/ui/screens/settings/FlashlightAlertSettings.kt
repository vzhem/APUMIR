package com.vladimir.messenger.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.R
import com.vladimir.messenger.data.notification.FlashlightAlertStore
import com.vladimir.messenger.data.notification.FlashlightMessageMode
import com.vladimir.messenger.service.FlashlightBlinker
import com.vladimir.messenger.ui.components.ApuBubbleMutedColor
import com.vladimir.messenger.ui.components.ApuPremiumChoiceRow
import com.vladimir.messenger.ui.components.ApuPremiumDialog
import com.vladimir.messenger.ui.components.ApuPremiumSwitch
import com.vladimir.messenger.ui.components.ApuSettingsCard
import com.vladimir.messenger.ui.components.ApuSettingsDivider
import com.vladimir.messenger.ui.components.ApuSettingsItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Карточка «Вспышка фонарика» в разделе «Настройки → Уведомления».
 *
 * Вынесена рядом с SettingsScreen.kt (правило god-файлов AGENTS.md п. 8):
 * 1) отдельный переключатель для тревоги SOS из группы (получатель может
 *    включить или выключить мигание фонарика при SOS);
 * 2) выбор режима для обычных входящих сообщений: выключено, все сообщения,
 *    только личные чаты, только группы или только каналы.
 */
@Composable
fun FlashlightAlertSettingsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val config by FlashlightAlertStore.config.collectAsStateWithLifecycle()
    var showModeDialog by remember { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        FlashlightAlertStore.initialize(context)
        hasFlash = withContext(Dispatchers.IO) {
            FlashlightBlinker.hasFlash(context)
        }
    }

    ApuSettingsCard(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        ApuSettingsItem(
            icon = if (config.sosEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
            title = stringResource(R.string.flashlight_sos_title),
            subtitle = stringResource(R.string.flashlight_sos_hint),
            trailingContent = {
                ApuPremiumSwitch(
                    checked = config.sosEnabled,
                    onCheckedChange = { enabled ->
                        FlashlightAlertStore.setSosEnabled(context, enabled)
                    },
                )
            },
        )
        ApuSettingsDivider()
        ApuSettingsItem(
            icon = if (config.messageMode == FlashlightMessageMode.OFF) {
                Icons.Default.FlashOff
            } else {
                Icons.Default.Bolt
            },
            title = stringResource(R.string.flashlight_messages_title),
            subtitle = flashlightModeLabel(config.messageMode),
            onClick = { showModeDialog = true },
        )
        if (hasFlash == false) {
            ApuSettingsDivider()
            Text(
                text = stringResource(R.string.flashlight_unavailable_hint),
                style = MaterialTheme.typography.bodySmall,
                color = ApuBubbleMutedColor,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }

    if (showModeDialog) {
        ApuPremiumDialog(
            title = stringResource(R.string.flashlight_messages_title),
            onDismiss = { showModeDialog = false },
            dismissLabel = stringResource(R.string.action_close),
            onDismissClick = { showModeDialog = false },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.flashlight_messages_dialog_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlashlightMessageMode.entries.forEach { mode ->
                    ApuPremiumChoiceRow(
                        title = flashlightModeLabel(mode),
                        subtitle = flashlightModeHint(mode),
                        selected = config.messageMode == mode,
                        icon = flashlightModeIcon(mode),
                        onClick = {
                            FlashlightAlertStore.setMessageMode(context, mode)
                            showModeDialog = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun flashlightModeLabel(mode: FlashlightMessageMode): String = when (mode) {
    FlashlightMessageMode.OFF -> stringResource(R.string.flashlight_mode_off)
    FlashlightMessageMode.ALL -> stringResource(R.string.flashlight_mode_all)
    FlashlightMessageMode.PERSONAL -> stringResource(R.string.flashlight_mode_personal)
    FlashlightMessageMode.GROUPS -> stringResource(R.string.flashlight_mode_groups)
    FlashlightMessageMode.CHANNELS -> stringResource(R.string.flashlight_mode_channels)
}

@Composable
private fun flashlightModeHint(mode: FlashlightMessageMode): String = when (mode) {
    FlashlightMessageMode.OFF -> stringResource(R.string.flashlight_mode_off_hint)
    FlashlightMessageMode.ALL -> stringResource(R.string.flashlight_mode_all_hint)
    FlashlightMessageMode.PERSONAL -> stringResource(R.string.flashlight_mode_personal_hint)
    FlashlightMessageMode.GROUPS -> stringResource(R.string.flashlight_mode_groups_hint)
    FlashlightMessageMode.CHANNELS -> stringResource(R.string.flashlight_mode_channels_hint)
}

private fun flashlightModeIcon(mode: FlashlightMessageMode): ImageVector = when (mode) {
    FlashlightMessageMode.OFF -> Icons.Default.FlashOff
    FlashlightMessageMode.ALL -> Icons.Default.NotificationsActive
    FlashlightMessageMode.PERSONAL -> Icons.Default.Person
    FlashlightMessageMode.GROUPS -> Icons.Default.Groups
    FlashlightMessageMode.CHANNELS -> Icons.Default.Campaign
}
