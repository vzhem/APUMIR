package com.vladimir.messenger.ui.screens.settings

import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.R
import com.vladimir.messenger.ui.components.ApuBubbleField
import com.vladimir.messenger.ui.components.ApuSettingsDialog

// =============================================================================
// PROFILESYNCDIALOG.KT — окно разового переноса профиля
// =============================================================================
// Раунд 225: два пути - «через сеть APU» (любые сети, включая мобильные;
// профили находят себя сами по нику, код выводится из пароля, авто-опрос,
// авто-скачивание) и «по Wi-Fi напрямую» (без интернета, раунд 224).
// Фирменный стиль: пузыри ApuActionBubble, QR белым боксом (р213).
// =============================================================================

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.data.backup.ProfileSyncNet
import com.vladimir.messenger.ui.components.ApuActionBubble
import com.vladimir.messenger.ui.components.ApuSettingsDivider
import com.vladimir.messenger.ui.components.ApuTextAction
import com.vladimir.messenger.util.QrCodeGenerator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.vladimir.messenger.ui.components.ApuPremiumSwitch

@Composable
fun ProfileSyncDialog(
    onDismiss: () -> Unit,
    viewModel: ProfileSyncViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    ApuSettingsDialog(
        onDismissRequest = { if (!ui.busy && !ui.restarting) {
            viewModel.stopShare()
            onDismiss()
        } },
        title = { Text(stringResource(R.string.settings_profile_transfer)) },
        text = {
            Column(
                // ApuSettingsDialog уже ограничивает высоту и прокручивает текст.
                // Вложенный verticalScroll здесь измерялся бы с бесконечной высотой
                // и ронял Compose при открытии окна на некоторых версиях Android.
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Уже настроенные телефоны с одним профилем синхронизируются " +
                        "сами в фоне — ничего нажимать не нужно. Это окно только " +
                        "для разового переноса или восстановления нового телефона. " +
                        "Данные не заменяются без подтверждения.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                ApuBubbleField(
                    value = ui.password,
                    onValueChange = viewModel::onPasswordChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.sync_pw_hint)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                // Раунд 256: пароль подставлен из входа - вводить ничего не надо.
                if (ui.passwordAuto) {
                    Text(
                        stringResource(R.string.sync_pw_auto),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                // ── Через сеть APU: любые сети, находят себя сами ───────
                Text(
                    stringResource(R.string.sync_via_apu),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Устройства находят себя сами: и отправителю, и приёмнику " +
                        "достаточно одного ника и пароля - вводить адреса не нужно.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ApuActionBubble(
                    label = if (ui.busy) stringResource(R.string.sync_syncing) else stringResource(R.string.sync_send_copy),
                    icon = Icons.Default.CloudSync,
                    onClick = { viewModel.netUpload() },
                )
                if (ui.netSent) {
                    Text(
                        stringResource(R.string.sync_wait_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                ui.netMeta?.let { meta ->
                    Text(
                        "В сети APU есть копия от " +
                            SimpleDateFormat("d.MM HH:mm", Locale.getDefault()).format(Date(meta.timeMs)) +
                            ", ${humanBytes(meta.sizeBytes)}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val mine = ProfileSyncNet.isOwnDevice(meta, ui.myDeviceId, ui.myAccountNodeId)
                    if (!mine) {
                        ApuTextAction(
                            label = stringResource(R.string.sync_fetch_pw),
                            onClick = viewModel::netFetchAndStage,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.sync_auto_search),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Раз в ~6 часов ищет копию в фоне. При первом переносе " +
                                "спросит подтверждение перед заменой данных",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ApuPremiumSwitch(
                        checked = ui.autoEnabled,
                        onCheckedChange = { viewModel.setAutoEnabled(it) },
                    )
                }
                ui.netMessage?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ui.netFailed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                ApuSettingsDivider(startPadding = 16.dp)

                // ── Wi-Fi напрямую: без интернета ────────────────────────
                Text(
                    stringResource(R.string.sync_wifi_direct),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!ui.sharing) {
                    ApuActionBubble(
                        label = if (ui.busy) stringResource(R.string.sync_preparing) else stringResource(R.string.sync_transfer),
                        icon = Icons.Default.Wifi,
                        onClick = { viewModel.startShare() },
                    )
                } else {
                    ui.shareAddress?.let { address ->
                        val qrBitmap = remember(address.qrPayload) {
                            QrCodeGenerator.generateQrCode(address.qrPayload, 512)
                        }
                        qrBitmap?.let {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        modifier = Modifier
                                            .background(Color.White, RoundedCornerShape(12.dp))
                                            .padding(8.dp),
                                    ) {
                                        Image(
                                            bitmap = it.asImageBitmap(),
                                            contentDescription = stringResource(R.string.sync_qr),
                                            modifier = Modifier.size(200.dp),
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            stringResource(R.string.sync_or_enter),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Адрес: ${address.humanAddress}\nКод: ${address.token}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(R.string.sync_waiting),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ApuTextAction(label = stringResource(R.string.sync_cancel_transfer), onClick = viewModel::stopShare)
                    }
                }
                ApuBubbleField(
                    value = ui.pullAddress,
                    onValueChange = viewModel::onPullAddressChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.sync_addr_hint)) },
                    singleLine = true,
                )
                ApuBubbleField(
                    value = ui.pullToken,
                    onValueChange = viewModel::onPullTokenChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.sync_code)) },
                    singleLine = true,
                )
                ApuActionBubble(
                    label = if (ui.busy) stringResource(R.string.sync_fetching) else stringResource(R.string.sync_fetch),
                    icon = Icons.Default.CloudDownload,
                    onClick = { viewModel.pullAndStage() },
                )

                // ── Подготовленная копия: подтверждение ─────────────────
                ui.staged?.let { manifest ->
                    Spacer(Modifier.height(4.dp))
                    ApuSettingsDivider(startPadding = 16.dp)
                    Spacer(Modifier.height(4.dp))
                    val created = SimpleDateFormat("d.MM.yyyy HH:mm", Locale.getDefault())
                        .format(Date(manifest.createdAtMs))
                    Text(
                        "Копия готова: ${manifest.displayName.ifBlank { "без имени" }} от $created, " +
                            "версия APU ${manifest.appVersionName}. Применить на этом устройстве? " +
                            stringResource(R.string.sync_replace_warn),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    ApuActionBubble(
                        label = if (ui.restarting) stringResource(R.string.sync_restarting) else stringResource(R.string.sync_apply),
                        icon = Icons.Default.RestartAlt,
                        onClick = {
                            viewModel.confirmAndExit {
                                (context as? android.app.Activity)?.finishAffinity()
                            }
                        },
                    )
                    ApuTextAction(label = stringResource(R.string.sync_dont_apply), onClick = viewModel::discardStaged)
                }

                ui.message?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ui.failed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
        confirmButton = {
            ApuTextAction(
                label = stringResource(R.string.action_close),
                onClick = {
                    viewModel.stopShare()
                    onDismiss()
                },
                enabled = !ui.busy && !ui.restarting,
            )
        },
    )
}

private fun humanBytes(bytes: Long): String = when {
    bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f МБ", bytes / (1024.0 * 1024))
    else -> String.format(java.util.Locale.US, "%.0f КБ", bytes / 1024.0)
}
