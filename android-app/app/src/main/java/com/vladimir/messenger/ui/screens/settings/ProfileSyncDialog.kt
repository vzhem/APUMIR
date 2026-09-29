package com.vladimir.messenger.ui.screens.settings

// =============================================================================
// PROFILESYNCDIALOG.KT — окно «Синхронизировать аккаунт» (Настройки)
// =============================================================================
// Раунд 223. В фирменном стиле: пузыри-действия ApuActionBubble, подсказки.
// Сверху - загрузить/восстановить, ниже - авто-синхронизация с
// периодичностью. Безопасно: копия зашифрована паролем человека, пароль для
// авто-режима хранится только завёрнутым ключом этого телефона.
// =============================================================================

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.data.backup.ProfileSync
import com.vladimir.messenger.ui.components.ApuActionBubble
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
        onDismissRequest = { if (!ui.busy && !ui.restarting) onDismiss() },
        title = { Text("Синхронизировать аккаунт") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Переносит чаты, контакты, сообщества, ранг и настройки на это " +
                        "устройство из зашифрованной копии в облаке. Пароль копии - " +
                        "тот же, что в «Защите личности». Получённые файлы " +
                        "(картинки, видео) остаются на телефонах.",
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

                // Что лежит в облаке.
                Text(
                    if (ui.cloud.exists)
                        "В облаке: копия от " +
                            SimpleDateFormat("d.MM.yyyy HH:mm", Locale.getDefault())
                                .format(Date(ui.cloud.timeMs)) +
                            ", ${ProfileBackupViewModel.humanBytes(ui.cloud.sizeBytes)}"
                    else "В облаке ещё нет копии",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Загрузить копию этого устройства в облако.
                ApuActionBubble(
                    label = if (ui.busy) "Синхронизируем…" else "Загрузить копию в облако",
                    icon = Icons.Default.CloudSync,
                    onClick = { viewModel.uploadNow() },
                )

                // Скачать копию из облака и подготовить восстановление.
                ApuActionBubble(
                    label = "Восстановить из облака",
                    icon = Icons.Default.CloudDownload,
                    onClick = { viewModel.downloadAndStage() },
                )

                // Подготовленная копия: показать, чья она, и спросить подтверждение.
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

                HorizontalDivider()

                // Авто-синхронизация: надо/не надо и периодичность.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Синхронизировать автоматически",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (ui.sync.enabled)
                                "Копия уходит в облако каждые ${ui.sync.period.title.lowercase()}"
                            else "Загружать копию в облако по расписанию",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = ui.sync.enabled,
                        onCheckedChange = { viewModel.setAutoEnabled(it, ui.sync.period) },
                    )
                }
                if (ui.sync.enabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProfileSync.Period.entries.forEach { period ->
                            androidx.compose.material3.FilterChip(
                                selected = ui.sync.period == period,
                                onClick = { viewModel.setPeriod(period) },
                                label = { Text(period.title) },
                            )
                        }
                    }
                    Text(
                        "Последняя загрузка: " +
                            if (ui.sync.lastUploadAtMs > 0)
                                SimpleDateFormat("d.MM HH:mm", Locale.getDefault())
                                    .format(Date(ui.sync.lastUploadAtMs)) +
                                    ", ${ProfileBackupViewModel.humanBytes(ui.sync.lastUploadBytes)}"
                            else "ещё не было",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ui.sync.lastError?.let { error ->
                    Text(
                        "Последняя авто-загрузка: $error",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
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
                onClick = onDismiss,
            ) { Text("Закрыть") }
        },
    )
}
