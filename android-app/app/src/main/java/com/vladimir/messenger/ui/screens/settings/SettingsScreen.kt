package com.vladimir.messenger.ui.screens.settings

// =============================================================================
// SETTINGSSCREEN.KT — Экран настроек
// =============================================================================
// Показывает:
//   - Профиль пользователя (имя, fingerprint, QR-код для приглашений)
//   - Статус сетевого движка (подключенные пиры, публичный IP)
//   - Управление соединением (перезапуск движка)
//   - Резервное копирование ключей
//   - О приложении
// =============================================================================

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.ui.components.Avatar
import com.vladimir.messenger.ui.theme.StatusConnecting
import com.vladimir.messenger.ui.theme.StatusDegraded
import com.vladimir.messenger.ui.theme.StatusOffline
import com.vladimir.messenger.ui.theme.StatusOnline
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onShareProfileClick: () -> Unit = {},
    onMtProxyClick: () -> Unit = {},
    onRankBenefitsClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboardManager.current
    var showBackupDialog by remember { mutableStateOf(false) }
    var showMyQrDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, "Назад")
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier       = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {

            // ----------------------------------------------------------------
            // ПРОФИЛЬ
            // ----------------------------------------------------------------
            // ----------------------------------------------------------------
            // ПОДЕЛИТЬСЯ ПРОФИЛЕМ
            // ----------------------------------------------------------------
            item {
                ListItem(
                    headlineContent = { Text("Поделиться профилем") },
                    supportingContent = { Text("Отправить ссылку для добавления в контакты") },
                    leadingContent = {
                        Icon(Icons.Default.Share, contentDescription = null)
                    },
                    modifier = Modifier.clickable { onShareProfileClick() }
                )
                HorizontalDivider()
            }

            item {
                ListItem(
                    headlineContent = { Text("Ранги и возможности") },
                    supportingContent = { Text("Что открывается за подтверждённые приглашения") },
                    leadingContent = { Icon(Icons.Default.EmojiEvents, contentDescription = null) },
                    modifier = Modifier.clickable { onRankBenefitsClick() }
                )
                HorizontalDivider()
            }

            // ----------------------------------------------------------------
            // MTPROTO ПРОКСИ
            // ----------------------------------------------------------------
            item {
                ListItem(
                    headlineContent = { Text("MTProto прокси") },
                    supportingContent = { Text("Управление прокси для Telegram relay") },
                    leadingContent = {
                        Icon(Icons.Default.VpnKey, contentDescription = null)
                    },
                    modifier = Modifier.clickable { onMtProxyClick() }
                )
                HorizontalDivider()
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Row(
                        modifier          = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(
                            name     = uiState.displayName,
                            modifier = Modifier.size(64.dp),
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                uiState.displayName,
                                style     = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                uiState.fingerprint,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // Кнопки профиля
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Показать QR-код (мой)
                        OutlinedButton(
                            onClick  = { showMyQrDialog = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Мой QR")
                        }
                        // Скопировать ссылку
                        OutlinedButton(
                            onClick  = {
                                clipboardManager.setText(AnnotatedString(uiState.inviteLink))
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Копировать")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // ----------------------------------------------------------------
            // СТАТУС СЕТИ
            // ----------------------------------------------------------------
            item {
                SettingsSectionTitle("Сеть")
            }
            item {
                SettingsCard {
                    // Статус соединения
                    SettingsItem(
                        icon  = Icons.Default.Hub,
                        title = "Статус соединения",
                        subtitle = uiState.connectionStatus.displayName,
                        trailingContent = {
                            StatusDot(status = uiState.connectionStatus)
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsItem(
                        icon     = Icons.Default.People,
                        title    = "Подключено пиров",
                        subtitle = "${uiState.connectedPeers} устройств",
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsItem(
                        icon     = Icons.Default.Public,
                        title    = "Публичный IP",
                        subtitle = uiState.publicIp ?: "Определяется...",
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsItem(
                        icon     = Icons.Default.Router,
                        title    = "Режим подключения",
                        subtitle = uiState.connectionMode,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    // Перезапуск движка
                    SettingsItem(
                        icon  = Icons.Default.RestartAlt,
                        title = "Перезапустить сетевой движок",
                        subtitle = "Пересоединиться со всеми пирами",
                        onClick = viewModel::onRestartEngine,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsItem(
                        icon  = Icons.Default.Refresh,
                        title = "Собрать данные об абонентах",
                        subtitle = "апустить Gossip для поиска пиров",
                        onClick = viewModel::onTriggerGossipDiscovery,
                    )
                }
            }

            // ----------------------------------------------------------------
            // БЕЗОПАСНОСТЬ
            // ----------------------------------------------------------------
            // ПЕРЕДАЧА ФАЙЛОВ
            // ----------------------------------------------------------------
            item { SettingsSectionTitle("Передача файлов") }
            item {
                SettingsCard {
                    SettingsItem(
                        icon     = Icons.Default.Delete,
                        title    = "Остановить зависшие отправки",
                        subtitle = "Отменяет незавершённые отправки и чистит их очереди",
                        onClick  = viewModel::onCancelStalledTransfers,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsItem(
                        icon     = Icons.Default.CleaningServices,
                        title    = "Очистить завершённые",
                        subtitle = "Освобождает место; сохранённые файлы остаются у вас",
                        onClick  = viewModel::onPurgeCompletedTransfers,
                    )
                }
            }

            // ----------------------------------------------------------------
            // СЕТЬ
            // ----------------------------------------------------------------
            item { SettingsSectionTitle("Сеть") }
            item {
                SettingsCard {
                    SettingsItem(
                        icon     = Icons.Default.VpnKey,
                        title    = "Туннель через прокси",
                        subtitle = "Автовыбор лучшего прокси для соединений (любая сеть)",
                        trailingContent = {
                            Switch(
                                checked = uiState.proxyTunnelEnabled,
                                onCheckedChange = viewModel::onProxyTunnelToggle,
                            )
                        },
                    )
                }
            }

            // ----------------------------------------------------------------
            item { SettingsSectionTitle("Безопасность") }
            item {
                SettingsCard {
                    SettingsItem(
                        icon     = Icons.Default.Backup,
                        title    = "Резервная копия ключей",
                        subtitle = "Экспорт ключей для восстановления",
                        onClick  = { showBackupDialog = true },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsItem(
                        icon     = Icons.Default.Security,
                        title    = "Протокол шифрования",
                        subtitle = "Ed25519 + X25519 + ChaCha20-Poly1305",
                    )
                }
            }

            // ----------------------------------------------------------------
            // О ПРИЛОЖЕНИИ
            // ----------------------------------------------------------------
            item { SettingsSectionTitle("О приложении") }
            item {
                SettingsCard {
                    SettingsItem(
                        icon     = Icons.Default.Info,
                        title    = "Версия",
                        subtitle = "APU ${uiState.appVersion}",
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsItem(
                        icon     = Icons.Default.Code,
                        title    = "Rust Core",
                        subtitle = uiState.rustCoreVersion,
                    )
                }

    // Диалог показа моего QR-кода
    if (showMyQrDialog) {
        val qrBitmap = remember(uiState.inviteLink) {
            try {
                val writer = QRCodeWriter()
                val hints = mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")
                val bitMatrix = writer.encode(uiState.inviteLink, BarcodeFormat.QR_CODE, 512, 512, hints)
                val width = bitMatrix.width
                val height = bitMatrix.height
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
                for (x in 0 until width) {
                    for (y in 0 until height) {
                        bmp.setPixel(x, y, if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                    }
                }
                bmp
            } catch (e: Exception) {
                null
            }
        }

        AlertDialog(
            onDismissRequest = { showMyQrDialog = false },
            title = { Text("My QR Code") },
            text = {
                androidx.compose.foundation.layout.Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "QR Code",
                            modifier = Modifier.size(280.dp),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    Text(
                        text = uiState.inviteLink,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showMyQrDialog = false }) {
                    Text("Close")
                }
            },
        )
    }
            }
        }
    }

    // Диалог резервного копирования
    if (showBackupDialog) {
        BackupDialog(
            onDismiss = { showBackupDialog = false },
            onExport  = { password ->
                viewModel.onExportKeys(password)
                showBackupDialog = false
            }
        )
    }
}

// =============================================================================
// ВСПОМОГАТЕЛЬНЫЕ КОМПОНЕНТЫ
// =============================================================================

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        text     = title,
        style    = MaterialTheme.typography.labelLarge,
        color    = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = null,
            tint               = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier           = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailingContent?.invoke()
        if (onClick != null) {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }


}

@Composable
private fun StatusDot(status: com.vladimir.messenger.data.repository.NetworkStatus) {
    val color = when (status) {
        com.vladimir.messenger.data.repository.NetworkStatus.Connected    -> StatusOnline
        com.vladimir.messenger.data.repository.NetworkStatus.Connecting   -> StatusConnecting
        com.vladimir.messenger.data.repository.NetworkStatus.Degraded     -> StatusDegraded
        com.vladimir.messenger.data.repository.NetworkStatus.Disconnected -> StatusOffline
    }
    Surface(
        modifier = Modifier.size(12.dp),
        shape    = RoundedCornerShape(50),
        color    = color,
    ) {}
}

@Composable
private fun BackupDialog(
    onDismiss: () -> Unit,
    onExport: (password: String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title   = { Text("Экспорт ключей") },
        text    = {
            Column {
                Text(
                    "Введите пароль для шифрования резервной копии. Без этого пароля восстановить ключи будет невозможно.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value         = password,
                    onValueChange = { password = it },
                    label         = { Text("Пароль") },
                    singleLine    = true,
                    visualTransformation = if (passwordVisible)
                        androidx.compose.ui.text.input.VisualTransformation.None
                    else
                        androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                )
            }
        },
        confirmButton = {
            Button(
                onClick  = { onExport(password) },
                enabled  = password.length >= 8,
            ) {
                Text("Экспорт")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

// Расширение для отображения статуса
private val com.vladimir.messenger.data.repository.NetworkStatus.displayName: String
    get() = when (this) {
        com.vladimir.messenger.data.repository.NetworkStatus.Connected    -> "Подключен"
        com.vladimir.messenger.data.repository.NetworkStatus.Connecting   -> "Подключение..."
        com.vladimir.messenger.data.repository.NetworkStatus.Degraded     -> "Через ретранслятор"
        com.vladimir.messenger.data.repository.NetworkStatus.Disconnected -> "Нет соединения"
    }