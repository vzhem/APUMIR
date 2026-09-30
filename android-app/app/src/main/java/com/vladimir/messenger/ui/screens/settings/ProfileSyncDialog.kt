package com.vladimir.messenger.ui.screens.settings

// =============================================================================
// PROFILESYNCDIALOG.KT — окно «Синхронизировать аккаунт» (прямой перенос)
// =============================================================================
// Раунд 224: без облака. Источник показывает QR и код; приёмник вводит адрес
// и код (или сканирует QR любым сканером) и забирает копию напрямую.
// Фирменный стиль: пузыри ApuActionBubble, QR белым боксом (как р213).
// =============================================================================

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
        title = { Text("Синхронизировать аккаунт") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Прямой перенос чатов, контактов, сообществ, ранга и настроек " +
                        "между вашими устройствами по сети Wi-Fi - БЕЗ облака, " +
                        "ничего нигде не хранится. Пароль копии - тот же, что в " +
                        "«Защите личности». Получённые файлы (картинки, видео) " +
                        "остаются на телефонах.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                androidx.compose.material3.OutlinedTextField(
                    value = ui.password,
                    onValueChange = viewModel::onPasswordChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Пароль копии") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )

                // ── Источник: раздача копии ─────────────────────────────
                Text(
                    "Телефон с данными",
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
                        // QR белым боксом, как приглашение (р213).
                        val qrBitmap = remember(address.qrPayload) {
                            QrCodeGenerator.generateQrCode(address.qrPayload, 512)
                        }
                        qrBitmap?.let {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                androidx.compose.foundation.layout.Box(
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

                // ── Приёмник: забрать копию ─────────────────────────────
                Text(
                    "Новое устройство (пустое)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
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
                    val created = SimpleDateFormat("d.MM.yyyy HH:mm", Locale.getDefault())
                        .format(Date(manifest.createdAtMs))
                    Text(
                        "Копия: ${manifest.displayName.ifBlank { "без имени" }} от $created, " +
                            "версия APU ${manifest.appVersionName}. Применить? " +
                            "Текущие данные этого устройства будут заменены.",
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
