package com.vladimir.messenger.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vladimir.messenger.R
import com.vladimir.messenger.data.swarm.NetworkPathSettings
import com.vladimir.messenger.ui.components.ApuBubbleMutedColor
import com.vladimir.messenger.ui.components.ApuPremiumSwitch
import com.vladimir.messenger.ui.components.ApuSettingsDialog
import com.vladimir.messenger.ui.components.ApuTextAction

/**
 * «Сеть сообщений»: состояние линии и выбор пути доставки.
 *
 * Вынесено из `SettingsScreen.kt` отдельным файлом по правилу AGENTS.md п. 8:
 * тот файл на потолке god-файлов, и новый код туда не пишется. Здесь же
 * живут переключатели части 2 задачи IPv6 — «только через relay» и «экономия
 * трафика» (см. [NetworkPathSettings]).
 *
 * Показываем словами, без техники: сначала состояние линии (MqttLinkText и
 * DirectChannelText уже собрали человеческий текст), затем два
 * переключателя, затем одна строка пояснения.
 */
@Composable
fun MessagesNetworkDialog(
    mqttHuman: String,
    mqttLink: String,
    clipboard: ClipboardManager,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // Значения читаем один раз при открытии: диалог короткий, а запись идёт
    // по нажатию, поэтому поток состояния здесь не нужен.
    var relayOnly by remember { mutableStateOf(NetworkPathSettings.relayOnly(context)) }
    var saveTraffic by remember { mutableStateOf(NetworkPathSettings.saveTraffic(context)) }

    // Раунд 190: в «Скопировать» идёт и сырая строка ядра — по ней видно
    // режим, ConnAck и точный текст ошибки.
    val humanText = buildString {
        if (mqttHuman.isNotBlank()) {
            append(mqttHuman)
            append("\n\n")
            append(stringResource(R.string.mqtt_path_auto))
            // Маркер разбора `MqttLinkText` (не текст интерфейса): по нему
            // решаем, добавлять ли пояснение про брокеров.
            if (mqttLink.contains(MQTT_ERROR_MARKER)) {
                append("\n\n")
                append(stringResource(R.string.mqtt_broker_note))
            }
        } else {
            append(mqttLink)
        }
    }

    ApuSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_network_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(humanText)
                Text(
                    text = stringResource(R.string.settings_network_path_section),
                    fontSize = 12.sp,
                    color = ApuBubbleMutedColor,
                )
                PathSwitchRow(
                    title = stringResource(R.string.settings_network_relay_only_title),
                    subtitle = stringResource(R.string.settings_network_relay_only_sub),
                    checked = relayOnly,
                    onCheckedChange = {
                        relayOnly = it
                        NetworkPathSettings.setRelayOnly(context, it)
                    },
                )
                PathSwitchRow(
                    title = stringResource(R.string.settings_network_save_traffic_title),
                    subtitle = stringResource(R.string.settings_network_save_traffic_sub),
                    checked = saveTraffic,
                    onCheckedChange = {
                        saveTraffic = it
                        NetworkPathSettings.setSaveTraffic(context, it)
                    },
                )
                Text(
                    text = stringResource(R.string.settings_network_path_note),
                    fontSize = 12.sp,
                    color = ApuBubbleMutedColor,
                )
            }
        },
        confirmButton = {
            ApuTextAction(
                label = stringResource(R.string.action_copy),
                onClick = {
                    val forDiagnostics = listOf(mqttHuman, mqttLink)
                        .filter { it.isNotBlank() }
                        .joinToString("\n\n")
                    clipboard.setText(AnnotatedString(forDiagnostics))
                    onDismiss()
                },
            )
        },
        dismissButton = {
            ApuTextAction(label = stringResource(R.string.action_close), onClick = onDismiss)
        },
    )
}

/**
 * Маркер ошибки в строке MQTT ядра («, ошибка N с назад: …»).
 *
 * Это ДАННЫЕ разбора, а не подпись: строка приходит из ядра и должна
 * распознаваться одинаково в любой локали, поэтому её нельзя переносить в
 * `res/values -strings.xml`. Тот же формат разбирает `MqttLinkText`.
 */
private const val MQTT_ERROR_MARKER = ", ошибка "

/** Строка «название + пояснение + переключатель». */
@Composable
private fun PathSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title)
            Text(text = subtitle, fontSize = 12.sp, color = ApuBubbleMutedColor)
        }
        ApuPremiumSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
