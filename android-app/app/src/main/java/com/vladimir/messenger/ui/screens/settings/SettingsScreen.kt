package com.vladimir.messenger.ui.screens.settings

// =============================================================================
// SETTINGSSCREEN.KT — Экран настроек
// =============================================================================
// Две вкладки в едином стиле APU (подложка на весь экран):
//   - «Профиль»: аватар, имя, @никнейм, QR-код, ссылка, поделиться, ранги.
//   - «Настройки»: оформление, сеть, передача файлов, безопасность, о программе.
// =============================================================================

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.ui.components.ChatWallpaper
import com.vladimir.messenger.ui.components.AvatarPickerDialog
import com.vladimir.messenger.ui.components.MyAvatar
import com.vladimir.messenger.ui.theme.AvatarHolder
import com.vladimir.messenger.ui.theme.StatusConnecting
import com.vladimir.messenger.ui.theme.StatusDegraded
import com.vladimir.messenger.ui.theme.StatusOffline
import com.vladimir.messenger.ui.theme.StatusOnline
import com.vladimir.messenger.ui.theme.ThemeMode
import com.vladimir.messenger.ui.theme.ThemeModeHolder
import com.vladimir.messenger.ui.theme.UsernameHolder
import com.vladimir.messenger.ui.theme.WallpaperHolder
import com.vladimir.messenger.util.QrCodeGenerator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onShareProfileClick: () -> Unit = {},
    onMtProxyClick: () -> Unit = {},
    onRankBenefitsClick: () -> Unit = {},
    /**
     * Нижняя панель разделов. Приходит снаружи, из навигации: экран не знает
     * маршрутов и не должен их знать. Пустая по умолчанию, чтобы превью и
     * тесты обходились без навигации.
     */
    bottomBar: @Composable () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboardManager.current
    var showBackupDialog by remember { mutableStateOf(false) }
    var showMyQrDialog by remember { mutableStateOf(false) }
    var showUsernameDialog by remember { mutableStateOf(false) }
    var showNameDialog by remember { mutableStateOf(false) }
    // Профиль вынесен в отдельную вкладку.
    var selectedTab by remember { mutableStateOf(0) }

    // Подложка на весь экран, в том числе под верхней панелью.
    Box(modifier = Modifier.fillMaxSize()) {
        ChatWallpaper()
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = bottomBar,
            topBar = {
                Column {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                    // Прокрутка НЕ должна красить панель: под ней обои APU.
                    scrolledContainerColor = Color.Transparent,
                        ),
                        title = { Text("Настройки", fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = onBackClick) {
                                Icon(Icons.Default.ArrowBack, "Назад")
                            }
                        },
                    )
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = Color.Transparent,
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Профиль") },
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Настройки") },
                        )
                    }
                }
            },
        ) { paddingValues ->
            if (selectedTab == 0) {
                ProfileTabContent(
                    paddingValues = paddingValues,
                    uiState = uiState,
                    onMyQr = { showMyQrDialog = true },
                    onCopyLink = { clipboardManager.setText(AnnotatedString(uiState.inviteLink)) },
                    onUsername = { showUsernameDialog = true },
                    onEditName = { showNameDialog = true },
                    onShareProfile = onShareProfileClick,
                    onRankBenefits = onRankBenefitsClick,
                )
            } else {
                SettingsTabContent(
                    paddingValues = paddingValues,
                    uiState = uiState,
                    viewModel = viewModel,
                    onMtProxyClick = onMtProxyClick,
                    onBackup = { showBackupDialog = true },
                )
            }
        }
    }

    // Своё имя: то самое, что видят собеседники в списке чатов и в шапке лички.
    if (showNameDialog) {
        var nameValue by remember(uiState.displayName) {
            mutableStateOf(uiState.displayName.takeIf { it != "Anonymous" }.orEmpty())
        }
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("Ваше имя") },
            text = {
                OutlinedTextField(
                    value = nameValue,
                    onValueChange = { nameValue = it.take(50) },
                    label = { Text("Имя") },
                    placeholder = { Text("Имя Фамилия") },
                    singleLine = true,
                    supportingText = { Text("${nameValue.trim().length}/50") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = nameValue.trim().length >= 2,
                    onClick = {
                        viewModel.onDisplayNameChanged(nameValue)
                        showNameDialog = false
                    },
                ) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) { Text("Отмена") }
            },
        )
    }

    // Свой @никнейм: хранится без собаки и уезжает в ссылку профиля (u=).
    if (showUsernameDialog) {
        val usernameContext = LocalContext.current
        val currentUsername by UsernameHolder.name.collectAsStateWithLifecycle()
        var usernameValue by remember { mutableStateOf(currentUsername.orEmpty()) }
        AlertDialog(
            onDismissRequest = { showUsernameDialog = false },
            title = { Text("Ваш @никнейм") },
            text = {
                Column {
                    OutlinedTextField(
                        value = usernameValue,
                        // Чистим прямо при наборе: недопустимый знак не
                        // появляется в поле, а не отвергается после «Сохранить».
                        onValueChange = { usernameValue = UsernameHolder.sanitize(it) },
                        label = { Text("никнейм") },
                        placeholder = { Text("никнейм") },
                        prefix = { Text("@") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Латинские буквы, цифры и подчёркивание. " +
                            "Короткое имя даёт короткую ссылку и крупный QR-код.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // Собака - неснимаемый префикс; храним имя без неё.
                    UsernameHolder.set(usernameContext, usernameValue)
                    UsernameHolder.clearConflict(usernameContext)
                    showUsernameDialog = false
                }, enabled = UsernameHolder.isValid(usernameValue)) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { showUsernameDialog = false }) { Text("Отмена") }
            },
        )
    }

    // Диалог «Мой QR-код».
    if (showMyQrDialog) {
        // Тот же генератор, что и везде: свой ZXing-блок здесь рисовал код с
        // другими настройками, поэтому «Мой QR-код» отличался от QR в контактах.
        val qrBitmap = remember(uiState.inviteLink) {
            QrCodeGenerator.generateQrCode(uiState.inviteLink)
        }

        AlertDialog(
            onDismissRequest = { showMyQrDialog = false },
            title = { Text("Мой QR-код") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "QR-код профиля",
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
                    Text("Закрыть")
                }
            },
        )
    }

    // Диалог резервного копирования.
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
// ВКЛАДКА «ПРОФИЛЬ»
// =============================================================================

@Composable
private fun ProfileTabContent(
    paddingValues: PaddingValues,
    uiState: SettingsUiState,
    onMyQr: () -> Unit,
    onCopyLink: () -> Unit,
    onUsername: () -> Unit,
    onEditName: () -> Unit,
    onShareProfile: () -> Unit,
    onRankBenefits: () -> Unit,
) {
    val context = LocalContext.current
    val myUsername by UsernameHolder.name.collectAsStateWithLifecycle()
    val avatarUri by AvatarHolder.uri.collectAsStateWithLifecycle()
    var showAvatarPicker by remember { mutableStateOf(false) }
    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) {
            }
            AvatarHolder.set(context, uri.toString())
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Свой аватар: картинка из галереи либо инициалы.
                    MyAvatar(
                        displayName = uiState.displayName,
                        modifier = Modifier.size(96.dp),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    // Имя правится прямо отсюда: тап по строке (или по
                    // карандашу) открывает диалог.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { onEditName() },
                    ) {
                        Text(
                            uiState.displayName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Изменить имя",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        if (myUsername.isNullOrBlank()) {
                            "@никнейм не задан"
                        } else {
                            "@$myUsername"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
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

                // Кнопки в два ряда: текст целиком умещается.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick  = onMyQr,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Мой QR", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick  = onCopyLink,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Ссылка", maxLines = 1)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick  = onUsername,
                        modifier = Modifier.weight(1f),
                    ) {
                        // Собака уже нарисована иконкой - в подписи её быть не
                        // должно, иначе на кнопке видно две «@».
                        Icon(Icons.Default.AlternateEmail, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Никнейм", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick  = { showAvatarPicker = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Аватар", maxLines = 1)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick  = onEditName,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Изменить имя", maxLines = 1)
                    }
                }
                if (avatarUri != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) {
                        TextButton(onClick = { AvatarHolder.set(context, null) }) {
                            Text("Убрать аватар")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // Каждая строка - свой пузырь: голые ListItem во всю ширину выбивались
        // из ряда и плохо читались на обоях.
        item {
            SettingsCard {
                SettingsItem(
                    icon = Icons.Default.Share,
                    title = "Поделиться профилем",
                    subtitle = "Отправить ссылку для добавления в контакты",
                    onClick = onShareProfile,
                )
            }
        }

        item {
            SettingsCard {
                SettingsItem(
                    icon = Icons.Default.EmojiEvents,
                    title = "Ранги и возможности",
                    subtitle = "Что открывается за подтверждённые приглашения",
                    onClick = onRankBenefits,
                )
            }
        }
    }

    // Диалог выбора аватара: стандартный набор из 50 или картинка из галереи.
    if (showAvatarPicker) {
        AvatarPickerDialog(
            context = context,
            onPickUri = { uri ->
                AvatarHolder.set(context, uri)
                showAvatarPicker = false
            },
            onPickGallery = {
                showAvatarPicker = false
                avatarPicker.launch("image/*")
            },
            onDismiss = { showAvatarPicker = false },
        )
    }
}

// =============================================================================
// ВКЛАДКА «НАСТРОЙКИ»
// =============================================================================

@Composable
private fun SettingsTabContent(
    paddingValues: PaddingValues,
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    onMtProxyClick: () -> Unit,
    onBackup: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        // ----------------------------------------------------------------
        // ОФОРМЛЕНИЕ: день / ночь / авто + обои
        // ----------------------------------------------------------------
        item { SettingsSectionTitle("Оформление") }
        item {
            SettingsCard {
                val context = LocalContext.current
                val themeMode by ThemeModeHolder.mode.collectAsStateWithLifecycle()
                ThemeMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { ThemeModeHolder.set(context, mode) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = themeMode == mode,
                            onClick = { ThemeModeHolder.set(context, mode) },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(mode.title, style = MaterialTheme.typography.bodyLarge)
                    }
                }

                // Свои обои: из галереи или стандартные в тон теме.
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                val customWallpaper by WallpaperHolder.uri.collectAsStateWithLifecycle()
                val wallpaperPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetContent()
                ) { uri ->
                    if (uri != null) {
                        try {
                            context.contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                        } catch (_: Exception) {
                        }
                        WallpaperHolder.set(context, uri.toString())
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Обои (подложка)", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (customWallpaper != null) {
                                "Своя картинка из галереи"
                            } else {
                                "Стандартные, в тон теме"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { wallpaperPicker.launch("image/*") }) {
                        Text("Из галереи")
                    }
                }
                if (customWallpaper != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                    ) {
                        TextButton(onClick = { WallpaperHolder.set(context, null) }) {
                            Text("Вернуть стандартные")
                        }
                    }
                }
            }
        }

        // ----------------------------------------------------------------
        // СТАТУС СЕТИ
        // ----------------------------------------------------------------
        item { SettingsSectionTitle("Сеть") }
        item {
            SettingsCard {
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
                    subtitle = uiState.publicIp ?: "Не удалось определить (нет сети?)",
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
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
                    subtitle = "Запустить поиск пиров по сети",
                    onClick = viewModel::onTriggerGossipDiscovery,
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
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
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                // Настройка прокси стоит рядом с выключателем прокси, а не
                // отдельным разделом: раньше два прокси-пункта жили в разных
                // концах экрана.
                SettingsItem(
                    icon = Icons.Default.Dns,
                    title = "Настроить прокси вручную",
                    subtitle = "Свои серверы MTProto",
                    onClick = onMtProxyClick,
                )
            }
        }

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
        // БЕЗОПАСНОСТЬ
        // ----------------------------------------------------------------
        item { SettingsSectionTitle("Безопасность") }
        item {
            SettingsCard {
                SettingsItem(
                    icon     = Icons.Default.Backup,
                    title    = "Резервная копия ключей",
                    subtitle = "Экспорт ключей для восстановления",
                    onClick  = onBackup,
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
            }
        }
    }
}

// =============================================================================
// ДИАЛОГ ВЫБОРА АВАТАРА
// =============================================================================

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
        // Переливающийся значок, как в меню списка чатов: раздел настроек
        // выглядит с ним заодно с остальным приложением.
        com.vladimir.messenger.ui.components.ShimmerIcon(
            imageVector = icon,
            size = 22.dp,
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
