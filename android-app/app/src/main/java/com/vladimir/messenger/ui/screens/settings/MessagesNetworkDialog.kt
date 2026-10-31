package com.vladimir.messenger.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
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
import com.vladimir.messenger.data.swarm.BrokerSettings
import com.vladimir.messenger.data.swarm.NetworkPathSettings
import com.vladimir.messenger.ui.components.ApuBubbleMutedColor
import com.vladimir.messenger.ui.components.ApuPremiumSwitch
import com.vladimir.messenger.ui.components.ApuFormTextField
import com.vladimir.messenger.ui.components.ApuSettingsDialog
import com.vladimir.messenger.ui.components.ApuTextAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var ipv6 by remember { mutableStateOf(NetworkPathSettings.ipv6Enabled(context)) }
    // Свой брокер: хранится в prefs, применяется сразу (см. BrokerSettings).
    var ownBroker by remember { mutableStateOf(BrokerSettings.text(context)) }
    var editingBroker by remember { mutableStateOf(false) }

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
                PathSwitchRow(
                    title = stringResource(R.string.settings_network_ipv6_title),
                    subtitle = stringResource(R.string.settings_network_ipv6_sub),
                    checked = ipv6,
                    onCheckedChange = {
                        ipv6 = it
                        NetworkPathSettings.setIpv6Enabled(context, it)
                    },
                )
                BrokerRow(
                    value = ownBroker,
                    onChange = { editingBroker = true },
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

    if (editingBroker) {
        OwnBrokerDialog(
            initial = ownBroker,
            onDismiss = { editingBroker = false },
            onSaved = {
                ownBroker = BrokerSettings.text(context)
                editingBroker = false
            },
        )
    }
}

/**
 * Строка «Свой брокер» + текущее значение.
 *
 * Показываем человеческий текст, а не технику: «публичные брокеры» — это
 * нормальное состояние, и человек не должен думать, что чего-то не хватает.
 */
@Composable
private fun BrokerRow(value: String, onChange: () -> Unit) {
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
            Text(stringResource(R.string.settings_network_broker_title))
            Text(
                text = value.ifBlank { stringResource(R.string.settings_network_broker_public) },
                fontSize = 12.sp,
                color = ApuBubbleMutedColor,
            )
            Text(
                text = stringResource(R.string.settings_network_broker_note),
                fontSize = 11.sp,
                color = ApuBubbleMutedColor,
            )
        }
        ApuTextAction(
            label = stringResource(R.string.settings_network_broker_edit),
            onClick = onChange,
        )
    }
}

/**
 * Ввод своего брокера: адрес, проверка доступности и сохранение.
 *
 * Проверка идёт в фоне (до 6 с): это сетевой вызов, на главной нитке он
 * подвесил бы интерфейс. Сохранение возможно и без проверки — иначе человек в
 * сети, где брокер пока недоступен, не смог бы вписать его заранее; но
 * результат проверки показывается рядом.
 */
@Composable
private fun OwnBrokerDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(initial) }
    var status by remember { mutableStateOf(BrokerStatus.Idle) }

    ApuSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_network_broker_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ApuFormTextField(
                    value = draft,
                    onValueChange = {
                        draft = it
                        status = BrokerStatus.Idle
                    },
                    label = stringResource(R.string.settings_network_broker_title),
                    placeholder = "broker.example.com:1883",
                    isError = status == BrokerStatus.Bad || status == BrokerStatus.Failed,
                )
                Text(
                    text = stringResource(R.string.settings_network_broker_dialog_hint),
                    fontSize = 12.sp,
                    color = ApuBubbleMutedColor,
                )
                when (status) {
                    BrokerStatus.Idle -> Unit
                    BrokerStatus.Checking -> Text(stringResource(R.string.settings_network_broker_check) + "…")
                    BrokerStatus.Ok -> Text(
                        text = stringResource(R.string.settings_network_broker_ok),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    BrokerStatus.Bad -> Text(
                        text = stringResource(R.string.settings_network_broker_bad),
                        color = MaterialTheme.colorScheme.error,
                    )
                    BrokerStatus.Failed -> Text(
                        text = stringResource(R.string.settings_network_broker_fail),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            ApuTextAction(
                label = stringResource(R.string.settings_network_broker_save),
                onClick = {
                    if (BrokerSettings.normalize(draft) == null) {
                        status = BrokerStatus.Bad
                    } else if (BrokerSettings.set(context, draft)) {
                        onSaved()
                    } else {
                        status = BrokerStatus.Bad
                    }
                },
            )
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ApuTextAction(
                    label = stringResource(R.string.settings_network_broker_check),
                    onClick = {
                        status = BrokerStatus.Checking
                        val candidate = draft
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { BrokerSettings.probe(candidate) }
                            status = when {
                                BrokerSettings.normalize(candidate) == null -> BrokerStatus.Bad
                                ok -> BrokerStatus.Ok
                                else -> BrokerStatus.Failed
                            }
                        }
                    },
                )
                ApuTextAction(label = stringResource(R.string.action_close), onClick = onDismiss)
            }
        },
    )
}

/** Состояние проверки адреса брокера в диалоге. */
private enum class BrokerStatus { Idle, Checking, Ok, Bad, Failed }

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
