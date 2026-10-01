package com.vladimir.messenger.ui.screens.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.vladimir.messenger.util.QrCodeGenerator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ProfileSyncDialog(
    onDismiss: () -> Unit,
    viewModel: ProfileSyncViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = { if (!ui.busy && !ui.restarting) {
            viewModel.stopShare()
            onDismiss()
        } },
        title = { Text("Перенос профиля на новое устройство") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
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

                androidx.compose.material3.OutlinedTextField(
                    value = ui.password,
                    onValueChange = viewModel::onPasswordChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Пароль из «Защиты личности» (одинаковый на обоих)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                // Раунд 256: пароль подставлен из входа - вводить ничего не надо.
                if (ui.passwordAuto) {
                    Text(
                        "Пароль подставлен автоматически из входа в аккаунт.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                // ── Через сеть APU: любые сети, находят себя сами ───────
                Text(
                    "Через сеть APU - любые сети (Wi-Fi и мобильный интернет)",
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
                    label = if (ui.busy) "Синхронизируем…" else "Отправить копию в сеть APU",
                    icon = Icons.Default.CloudSync,
                    onClick = { viewModel.netUpload() },
                )
                if (ui.netSent) {
                    Text(
                        "Копия ждёт второе устройство и исчезнет после забора.",
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
                        TextButton(onClick = viewModel::netFetchAndStage) {
                            Text("Забрать копию по паролю")
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Автоматически искать копию",
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
                    Switch(
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

                HorizontalDivider()

                // ── Wi-Fi напрямую: без интернета ────────────────────────
                Text(
                    "По Wi-Fi напрямую - без интернета",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!ui.sharing) {
                    ApuActionBubble(
                        label = if (ui.busy) "Готовим копию…" else "Передать на другое устройство",
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
                                            contentDescription = "QR передачи копии",
                                            modifier = Modifier.size(200.dp),
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            "Или введите на другом устройстве:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Адрес: ${address.humanAddress}\nКод: ${address.token}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Ожидаем приёмник… копия отдаётся один раз.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = viewModel::stopShare) {
                            Text("Отменить передачу")
                        }
                    }
                }
                androidx.compose.material3.OutlinedTextField(
                    value = ui.pullAddress,
                    onValueChange = viewModel::onPullAddressChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Адрес с экрана телефона (192.168.х.х:48126)") },
                    singleLine = true,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = ui.pullToken,
                    onValueChange = viewModel::onPullTokenChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Код (8 знаков)") },
                    singleLine = true,
                )
                ApuActionBubble(
                    label = if (ui.busy) "Забираем копию…" else "Забрать копию с телефона",
                    icon = Icons.Default.CloudDownload,
                    onClick = { viewModel.pullAndStage() },
                )

                // ── Подготовленная копия: подтверждение ─────────────────
                ui.staged?.let { manifest ->
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(4.dp))
                    val created = SimpleDateFormat("d.MM.yyyy HH:mm", Locale.getDefault())
                        .format(Date(manifest.createdAtMs))
                    Text(
                        "Копия готова: ${manifest.displayName.ifBlank { "без имени" }} от $created, " +
                            "версия APU ${manifest.appVersionName}. Применить на этом устройстве? " +
                            "Текущие данные будут заменены.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    ApuActionBubble(
                        label = if (ui.restarting) "Перезапускаем…" else "Применить и перезапустить",
                        icon = Icons.Default.RestartAlt,
                        onClick = {
                            viewModel.confirmAndExit {
                                (context as? android.app.Activity)?.finishAffinity()
                            }
                        },
                    )
                    TextButton(onClick = viewModel::discardStaged) {
                        Text("Не применять")
                    }
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
            TextButton(
                enabled = !ui.busy && !ui.restarting,
                onClick = {
                    viewModel.stopShare()
                    onDismiss()
                },
            ) { Text("Закрыть") }
        },
    )
}

private fun humanBytes(bytes: Long): String = when {
    bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f МБ", bytes / (1024.0 * 1024))
    else -> String.format(java.util.Locale.US, "%.0f КБ", bytes / 1024.0)
}
