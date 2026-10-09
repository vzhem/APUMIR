package com.vladimir.messenger.ui.screens.settings

// =============================================================================
// SETTINGSSCREEN.KT — Экран настроек
// =============================================================================
// Две вкладки в едином стиле APU (подложка на весь экран):
//   - «Профиль»: аватар, имя, @никнейм, QR-код, ссылка, поделиться, ранги.
//   - «Настройки»: оформление, сеть, передача файлов, безопасность, о программе.
// =============================================================================

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.rememberLazyListState
import com.vladimir.messenger.ui.components.ApuBubbleField
import com.vladimir.messenger.ui.components.ApuScrollbar
import com.vladimir.messenger.ui.components.ApuBubbleAccentColor
import com.vladimir.messenger.ui.components.ApuBubbleTextColor
import com.vladimir.messenger.ui.components.ApuSettingsDangerColor
import com.vladimir.messenger.ui.components.ApuBubbleMutedColor
import com.vladimir.messenger.ui.components.ApuBubbleSurfaceColor
import com.vladimir.messenger.ui.components.ApuGold
import com.vladimir.messenger.ui.components.ApuGoldInk
import com.vladimir.messenger.ui.components.ApuPremiumButton
import com.vladimir.messenger.ui.components.ApuSettingsCard
import com.vladimir.messenger.ui.components.ApuTextAction
import com.vladimir.messenger.ui.components.apuGoldBrush
import com.vladimir.messenger.ui.components.apuPremiumGloss
import com.vladimir.messenger.ui.components.apuPremiumLift
import com.vladimir.messenger.ui.components.ApuSettingsDialog
import com.vladimir.messenger.ui.components.ApuSettingsDivider
import com.vladimir.messenger.ui.components.ApuSettingsHeader
import com.vladimir.messenger.ui.components.ApuDiagnosticsActionButton
import com.vladimir.messenger.ui.components.ApuDiagnosticsEventList
import com.vladimir.messenger.ui.components.ApuDiagnosticsHero
import com.vladimir.messenger.ui.components.ApuDiagnosticsPrivacyStrip
import com.vladimir.messenger.ui.components.ApuDiagnosticsReportCard
import com.vladimir.messenger.ui.components.ApuDiagnosticsStatusCard
import com.vladimir.messenger.ui.components.DiagnosticsActionIcons
import com.vladimir.messenger.ui.components.DiagnosticsActionStyle
import com.vladimir.messenger.ui.components.ApuSettingsItem
import com.vladimir.messenger.ui.components.ApuSettingsLayout
import com.vladimir.messenger.ui.components.ApuSettingsSectionTitle
import com.vladimir.messenger.ui.components.ApuProfileQuickAction
import com.vladimir.messenger.ui.components.NotificationMuteDialog
import com.vladimir.messenger.ui.components.notificationMuteStatus
import com.vladimir.messenger.data.diagnostics.DiagnosticsReport
import com.vladimir.messenger.data.diagnostics.TransferDiagnostics
import com.vladimir.messenger.data.notification.NotificationMuteScope
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.vladimir.messenger.ui.components.swipeBack
import com.vladimir.messenger.util.AppShare
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.R
import androidx.compose.ui.res.stringResource
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
import com.vladimir.messenger.data.swarm.StoragePolicy
import com.vladimir.messenger.data.swarm.StorageSettings
import com.vladimir.messenger.data.swarm.SwarmMode
import com.vladimir.messenger.data.swarm.SwarmPolicy
import com.vladimir.messenger.data.swarm.SwarmSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import com.vladimir.messenger.ui.theme.ThemeMode
import com.vladimir.messenger.ui.theme.ThemeModeHolder
import com.vladimir.messenger.ui.theme.AppFontSize
import com.vladimir.messenger.ui.theme.AppFontSizeHolder
import com.vladimir.messenger.ui.theme.UsernameHolder
import com.vladimir.messenger.ui.theme.WallpaperHolder
import com.vladimir.messenger.util.QrCodeGenerator
import com.vladimir.messenger.ui.components.ApuPremiumRadioButton
import com.vladimir.messenger.ui.components.ApuPremiumSlider
import com.vladimir.messenger.ui.components.ApuPremiumSwitch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onShareProfileClick: () -> Unit = {},
    onMtProxyClick: () -> Unit = {},
    onRankBenefitsClick: () -> Unit = {},
    /** Подробности о том, через какие узлы идут данные. */
    onPeerRatingClick: () -> Unit = {},
    /** Никнейм и пароль, которыми личность возвращается после переустановки. */
    onIdentityBackupClick: () -> Unit = {},
    /** Раунд 219: «Поддержать разработчика» (черновик). */
    onSupportClick: () -> Unit = {},
    /** Полная копия профиля в файл (чаты, контакты, ключи) и восстановление из него. */
    onProfileBackupClick: () -> Unit = {},
    /**
     * Открыть сразу профиль, а не список настроек.
     *
     * Один экран на две записи в навигации: «Профиль» из нижней панели и
     * «Настройки». Разводить их в два файла незачем - содержимое общее.
     */
    showProfile: Boolean = false,
    /** Переход к профилю из списка настроек. */
    onProfileClick: () -> Unit = {},
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
    val contextForToast = LocalContext.current
    var showMyQrDialog by remember { mutableStateOf(false) }
    var showUsernameDialog by remember { mutableStateOf(false) }
    var showNameDialog by remember { mutableStateOf(false) }
    // Вкладок больше нет: профиль - отдельный пункт в списке настроек и
    // отдельная кнопка в нижней панели. Вкладка сверху дублировала их и
    // мешала: список настроек начинался не с начала.

    // Подложка на весь экран, в том числе под верхней панелью.
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Смахивание вправо работает как «Назад».
            .swipeBack(onBack = onBackClick),
    ) {
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
                        title = {
                            ApuSettingsHeader(if (showProfile) stringResource(R.string.settings_header_profile) else stringResource(R.string.settings_header_settings))
                        },
                        navigationIcon = {
                            IconButton(onClick = onBackClick) {
                                Icon(Icons.Default.ArrowBack, stringResource(R.string.action_back))
                            }
                        },
                    )
                }
            },
        ) { paddingValues ->
            if (showProfile) {
                ProfileTabContent(
                    paddingValues = paddingValues,
                    uiState = uiState,
                    onMyQr = { showMyQrDialog = true },
                    onCopyLink = {
                        clipboardManager.setText(AnnotatedString(uiState.inviteLink))
                        android.widget.Toast.makeText(contextForToast, contextForToast.getString(R.string.toast_link_copied), android.widget.Toast.LENGTH_SHORT).show()
                    },
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
                    onPeerRatingClick = onPeerRatingClick,
                    onIdentityBackupClick = onIdentityBackupClick,
                    onProfileBackupClick = onProfileBackupClick,
                    onProfileClick = onProfileClick,
                    onSupportClick = onSupportClick,
                )
            }
        }
    }

    // Своё имя: то самое, что видят собеседники в списке чатов и в шапке лички.
    if (showNameDialog) {
        var nameValue by remember(uiState.displayName) {
            mutableStateOf(uiState.displayName.takeIf { it != "Anonymous" }.orEmpty())
        }
        ApuSettingsDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text(stringResource(R.string.settings_name_title)) },
            text = {
                ApuBubbleField(
                    value = nameValue,
                    onValueChange = { nameValue = it.take(50) },
                    label = { Text(stringResource(R.string.settings_name_label)) },
                    placeholder = { Text(stringResource(R.string.settings_name_placeholder)) },
                    singleLine = true,
                    supportingText = { Text("${nameValue.trim().length}/50") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                ApuTextAction(
                    label = stringResource(R.string.action_save),
                    onClick = {
                        viewModel.onDisplayNameChanged(nameValue)
                        showNameDialog = false
                    },
                    enabled = nameValue.trim().length >= 2,
                )
            },
            dismissButton = {
                ApuTextAction(label = stringResource(R.string.action_cancel), onClick = { showNameDialog = false })
            },
        )
    }

    // Свой @никнейм: хранится без собаки и уезжает в ссылку профиля (u=).
    if (showUsernameDialog) {
        val usernameContext = LocalContext.current
        val currentUsername by UsernameHolder.name.collectAsStateWithLifecycle()
        var usernameValue by remember { mutableStateOf(currentUsername.orEmpty()) }
        ApuSettingsDialog(
            onDismissRequest = { showUsernameDialog = false },
            title = { Text(stringResource(R.string.settings_username_title)) },
            text = {
                Column {
                    ApuBubbleField(
                        value = usernameValue,
                        onValueChange = { usernameValue = UsernameHolder.sanitize(it) },
                        label = { Text(stringResource(R.string.settings_username_label)) },
                        placeholder = { Text(stringResource(R.string.settings_username_label)) },
                        prefix = { Text("@") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.settings_username_hint_1) +
                            stringResource(R.string.settings_username_hint_2),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                ApuTextAction(
                    label = stringResource(R.string.action_save),
                    onClick = {

                    UsernameHolder.set(usernameContext, usernameValue)
                    UsernameHolder.clearConflict(usernameContext)
                    showUsernameDialog = false
                },
                    enabled = UsernameHolder.isValid(usernameValue),
                )
            },
            dismissButton = {
                ApuTextAction(label = stringResource(R.string.action_cancel), onClick = { showUsernameDialog = false })
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

        ApuSettingsDialog(
            onDismissRequest = { showMyQrDialog = false },
            title = { Text(stringResource(R.string.settings_my_qr)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.settings_qr_desc),
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
                ApuTextAction(label = stringResource(R.string.action_close), onClick = { showMyQrDialog = false })
            },
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
    // Картинка, для которой сейчас выбирают область. null - окна обрезки нет.
    var avatarToCrop by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    // Куда камера положит снимок: URI нужен и при запуске, и при разборе.
    var pendingPhotoUri by remember { mutableStateOf<android.net.Uri?>(null) }

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
            // Не сохраняем сразу: сперва человек выбирает область.
            avatarToCrop = com.vladimir.messenger.util.AvatarFiles.readForCrop(context, uri)
        }
    }

    val photoTaker = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val shot = pendingPhotoUri
        if (ok && shot != null) {
            avatarToCrop = com.vladimir.messenger.util.AvatarFiles.readForCrop(context, shot)
        }
        pendingPhotoUri = null
    }

    // Разрешение спрашиваем только когда человек нажал «Снять фото»: просить
    // камеру заранее, на всякий случай, - плохая манера.
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val target = com.vladimir.messenger.util.AvatarFiles.captureTarget(context)
            pendingPhotoUri = target
            photoTaker.launch(target)
        }
    }

    // Бегунок справа: видно, где мы в длинном списке.
    val profileScrollState = rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = profileScrollState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                ApuSettingsCard(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier.size(104.dp)
                                .clickable(role = androidx.compose.ui.semantics.Role.Button) { showAvatarPicker = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier.size(100.dp)
                                    .border(2.dp, ApuBubbleAccentColor.copy(alpha = 0.38f), CircleShape)
                                    .padding(5.dp),
                            ) {
                                MyAvatar(displayName = uiState.displayName, modifier = Modifier.fillMaxSize(), size = 88)
                            }
                            Box(
                                modifier = Modifier.align(Alignment.BottomEnd).size(30.dp)
                                    .clip(CircleShape).background(ApuBubbleAccentColor)
                                    .border(2.dp, ApuBubbleSurfaceColor, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.PhotoCamera,
                                    contentDescription = stringResource(R.string.settings_change_avatar),
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.heightIn(min = 44.dp)
                                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onEditName),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                uiState.displayName,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.settings_change_name), tint = ApuBubbleAccentColor, modifier = Modifier.size(18.dp))
                        }
                        Text(
                            if (myUsername.isNullOrBlank()) stringResource(R.string.settings_set_username) else "@$myUsername",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ApuBubbleAccentColor,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable(onClick = onUsername).padding(vertical = 4.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier.clip(RoundedCornerShape(20.dp))
                                    .background(Color(0xFFE0245E).copy(alpha = 0.07f))
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.Favorite, contentDescription = null, tint = Color(0xFFE0245E), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(uiState.heartCount.toString(), fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (uiState.heartCount == 0) "сердечки" else "рейтинг",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ApuBubbleMutedColor,
                                )
                            }
                            Row(
                                modifier = Modifier.clip(RoundedCornerShape(20.dp))
                                    .background(
                                        if (uiState.antiRatingCount > 0) {
                                            ApuSettingsDangerColor.copy(alpha = 0.10f)
                                        } else {
                                            ApuBubbleAccentColor.copy(alpha = 0.07f)
                                        }
                                    )
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("\uD83D\uDC4E", style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    uiState.antiRatingCount.toString(),
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (uiState.antiRatingCount > 0) ApuSettingsDangerColor else ApuBubbleTextColor,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "жалобы",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (uiState.antiRatingCount > 0) ApuSettingsDangerColor else ApuBubbleMutedColor,
                                )
                            }
                            if (uiState.antiRatingWarning) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    stringResource(R.string.settings_complaints_spike),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ApuSettingsDangerColor,
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Box(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(ApuBubbleAccentColor.copy(alpha = 0.04f)).padding(10.dp),
                        ) {
                            SelectionContainer {
                                Text(
                                    uiState.fingerprint,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = ApuBubbleMutedColor,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        val actions = listOf(
                            Triple(stringResource(R.string.settings_my_qr), Icons.Default.QrCode, onMyQr),
                            Triple(stringResource(R.string.groups_link), Icons.Default.ContentCopy, onCopyLink),
                            Triple(stringResource(R.string.identity_nickname), Icons.Default.AlternateEmail, onUsername),
                            Triple(stringResource(R.string.settings_avatar), Icons.Default.AccountCircle, { showAvatarPicker = true }),
                        )
                        val fontScale = LocalDensity.current.fontScale
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            val columns = ApuSettingsLayout.profileActionColumns(maxWidth.value, fontScale)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                actions.chunked(columns).forEach { group ->
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        group.forEach { (title, icon, click) ->
                                            ApuProfileQuickAction(title, icon, click, modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                        if (avatarUri != null) {
                            Spacer(Modifier.height(6.dp))
                            ApuTextAction(
                                label = stringResource(R.string.settings_remove_avatar),
                                onClick = { AvatarHolder.set(context, null) },
                            )
                        }
                    }
                }
            }

            // Каждая строка - свой пузырь: голые ListItem во всю ширину выбивались
            // из ряда и плохо читались на обоях.
            item {
                SettingsCard {
                    SettingsItem(
                        icon = Icons.Default.Share,
                        title = stringResource(R.string.settings_share_profile),
                        subtitle = stringResource(R.string.settings_share_profile_hint),
                        onClick = onShareProfile,
                    )
                }
            }

            item {
                SettingsCard {
                    SettingsItem(
                        icon = Icons.Default.EmojiEvents,
                        title = stringResource(R.string.settings_ranks),
                        subtitle = stringResource(R.string.settings_ranks_hint),
                        onClick = onRankBenefits,
                    )
                }
            }
        }
        ApuScrollbar(state = profileScrollState)
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
            onTakePhoto = {
                showAvatarPicker = false
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.CAMERA,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (granted) {
                    val target = com.vladimir.messenger.util.AvatarFiles.captureTarget(context)
                    pendingPhotoUri = target
                    photoTaker.launch(target)
                } else {
                    cameraPermission.launch(android.Manifest.permission.CAMERA)
                }
            },
            onDismiss = { showAvatarPicker = false },
        )
    }

    // Выбор области: и для снимка с камеры, и для картинки из галереи.
    avatarToCrop?.let { source ->
        com.vladimir.messenger.ui.components.AvatarCropDialog(
            source = source,
            onConfirm = { cropped ->
                com.vladimir.messenger.util.AvatarFiles.saveCropped(context, cropped)
                    ?.let { AvatarHolder.set(context, it) }
                avatarToCrop = null
            },
            onDismiss = { avatarToCrop = null },
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
    onPeerRatingClick: () -> Unit = {},
    onIdentityBackupClick: () -> Unit = {},
    onProfileBackupClick: () -> Unit = {},
    onProfileClick: () -> Unit = {},
    /** Раунд 219: «Поддержать разработчика» (черновик). */
    onSupportClick: () -> Unit = {},
) {
    // Диалог «Сеть сообщений» и буфер обмена для «Скопировать» в нём —
    // локальные для этого экрана.
    var showMqttDialog by remember { mutableStateOf(false) }
    // Безопасный отчёт о сети, ядре и передачах: сводка для человека плюс
    // текст, который копируют целиком (TransferDiagnostics.collect).
    var showTransferLogsDialog by remember { mutableStateOf(false) }
    var transferLogsRefresh by remember { mutableIntStateOf(0) }
    var transferLogsSnapshot by remember { mutableStateOf<TransferDiagnostics.Snapshot?>(null) }
    var transferLogsLoading by remember { mutableStateOf(false) }
    // Что именно сейчас делает сбор — словами. Владелец 2026-10-07: «кнопки не
    // работают… всё на паузе»; окно должно объяснять, что происходит, а не
    // молчать пустым экраном.
    var transferLogsStage by remember { mutableStateOf<String?>(null) }
    // Ошибка сбора — отдельно от хода работ: «читаю журнал» это не то же
    // самое, что «сбор не удался», и красить их одним цветом нельзя.
    var transferLogsError by remember { mutableStateOf<String?>(null) }
    // Сторож: сбор идёт дольше обычного — человек узнает об этом словами,
    // а не догадкой «приложение, похоже, встало».
    var transferLogsSlow by remember { mutableStateOf(false) }
    val settingsContext = LocalContext.current
    LaunchedEffect(showTransferLogsDialog, transferLogsRefresh) {
        if (showTransferLogsDialog) {
            transferLogsLoading = true
            transferLogsError = null
            transferLogsStage = TransferDiagnostics.STAGE_DEVICE
            try {
                // Сбор идёт в фоне: внутри вызовы ядра, база и `logcat`.
                // Быстрая часть приходит сразу (onPartial) — окно не пустует,
                // журнал процесса догоняет и уточняет отчёт.
                val full = withContext(Dispatchers.IO) {
                    TransferDiagnostics.collect(
                        context = settingsContext,
                        onStage = { stage -> transferLogsStage = stage },
                        onPartial = { partial -> transferLogsSnapshot = partial },
                    )
                }
                transferLogsSnapshot = full
                transferLogsStage = null
            } catch (failure: Throwable) {
                // Сбор не имеет права оставить окно в вечной «паузе»: говорим,
                // что случилось, и предлагаем повторить. Быстрая часть отчёта
                // при этом остаётся на экране — она уже собрана.
                transferLogsStage = null
                transferLogsError = "сбор отчёта не завершился (" +
                    failure.javaClass.simpleName + ") — нажмите «Обновить»"
            } finally {
                transferLogsLoading = false
            }
        }
    }
    LaunchedEffect(showTransferLogsDialog, transferLogsRefresh) {
        transferLogsSlow = false
        if (showTransferLogsDialog) {
            delay(5_000)
            if (transferLogsLoading) transferLogsSlow = true
        }
    }
    // р240: диагностика синхронизации устройств одной личности.
    // (имя с Mirror: showSyncDialog занят окном переноса профиля)
    var showMirrorDiag by remember { mutableStateOf(false) }
    // Диалог резервной копии адресов (раздел «Сервер»).
    var showAddrBookDialog by remember { mutableStateOf(false) }
    // Раунд 223: окно разового переноса профиля.
    var showSyncDialog by remember { mutableStateOf(false) }
    // Раунд 249: подтверждение выхода из APU.
    var showLogoutDialog by remember { mutableStateOf(false) }
    var muteScope by remember { mutableStateOf<NotificationMuteScope?>(null) }
    val muteRevision by viewModel.notificationMuteRevision.collectAsStateWithLifecycle()
    val notificationMuteNowMs = remember(muteRevision) { System.currentTimeMillis() }
    val mqttClipboard = LocalClipboardManager.current
    // Бегунок справа: видно, где мы в длинном списке.
    val settingsScrollState = rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = settingsScrollState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            // Профиль первым пунктом: имя, @никнейм и свой QR нужны чаще
            // остального, а раньше они прятались за вкладкой сверху.
            item { SettingsSectionTitle(stringResource(R.string.section_my_profile)) }
            item {
                SettingsCard {
                    SettingsItem(
                        icon = Icons.Default.Person,
                        title = stringResource(R.string.settings_item_profile),
                        subtitle = stringResource(R.string.settings_item_profile_hint),
                        onClick = onProfileClick,
                    )
                }
            }

            // ----------------------------------------------------------------
            // ОФОРМЛЕНИЕ: день / ночь / авто + обои
            // ----------------------------------------------------------------
            item { SettingsSectionTitle(stringResource(R.string.section_appearance)) }
            item {
                SettingsCard {
                    val context = LocalContext.current
                    val themeMode by ThemeModeHolder.mode.collectAsStateWithLifecycle()
                    ThemeModeChoices(selected = themeMode, onSelect = { ThemeModeHolder.set(context, it) })
                    ApuSettingsDivider()
                    val appFontSize by AppFontSizeHolder.size.collectAsStateWithLifecycle()
                    // Раунд 268: у этого блока не было боковых отступов, и
                    // подпись «…группах, каналах и темах» упиралась в край
                    // карточки (скрин владельца 2026-10-05). Теперь как у
                    // выбора темы: BoxWithConstraints + отступы 16dp, а если
                    // три чипа не влезают в строку (в т.ч. при «Крупном»
                    // размере текста) — столбиком, а не обрезанным рядом.
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        val chipsHorizontal = ApuSettingsLayout.horizontalFontSizeChoices(
                            maxWidth.value,
                            LocalDensity.current.fontScale,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(R.string.settings_font_size),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                stringResource(R.string.settings_font_size_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val chipRows =
                                if (chipsHorizontal) listOf(AppFontSize.entries.toList())
                                else AppFontSize.entries.map { listOf(it) }
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                chipRows.forEach { rowOptions ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        rowOptions.forEach { option ->
                                            FilterChip(
                                                selected = appFontSize == option,
                                                onClick = { AppFontSizeHolder.set(context, option) },
                                                label = {
                                                    Text(
                                                        option.title,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                },
                                                modifier =
                                                    if (chipsHorizontal) Modifier
                                                    else Modifier.fillMaxWidth(),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Свои обои: из галереи или стандартные в тон теме.
                    ApuSettingsDivider()
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
                    SettingsItem(
                        icon = Icons.Default.Wallpaper,
                        title = stringResource(R.string.settings_wallpaper),
                        subtitle = if (customWallpaper != null) stringResource(R.string.settings_wallpaper_custom) else "Стандартные, в тон теме",
                        onClick = { wallpaperPicker.launch("image/*") },
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                    ) {
                        ApuPremiumButton(
                            label = stringResource(R.string.settings_from_gallery),
                            icon = Icons.Default.PhotoLibrary,
                            onClick = { wallpaperPicker.launch("image/*") },
                        )
                    }
                    if (customWallpaper != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                        ) {
                            ApuPremiumButton(
                                label = stringResource(R.string.settings_reset_wallpaper),
                                icon = Icons.Default.RestartAlt,
                                style = DiagnosticsActionStyle.QUIET,
                                onClick = { WallpaperHolder.set(context, null) },
                            )
                        }
                    }
                }
            }

            // ----------------------------------------------------------------
            // ЯЗЫК интерфейса: русский / English. Применяется после перезапуска
            // экрана; выбор хранится в p2p_prefs (см. AppLanguageHolder).
            // ----------------------------------------------------------------
            item { SettingsSectionTitle(stringResource(R.string.language_section)) }
            item {
                SettingsCard {
                    val context = LocalContext.current
                    val appLanguage by com.vladimir.messenger.ui.i18n.AppLanguageHolder.language
                        .collectAsStateWithLifecycle()
                    var showLanguageDialog by remember { mutableStateOf(false) }
                    var showTranslateConsent by remember { mutableStateOf(false) }
                    SettingsItem(
                        icon = Icons.Default.Language,
                        title = stringResource(R.string.language_app),
                        subtitle = appLanguage.nativeName,
                        onClick = { showLanguageDialog = true },
                    )
                    // Перевод входящих сообщений на язык приложения (на устройстве).
                    val translateOn by com.vladimir.messenger.data.translate.TranslationSettings.enabled
                        .collectAsStateWithLifecycle()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.translate_title),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                stringResource(R.string.translate_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = translateOn,
                            onCheckedChange = {
                                if (it) {
                                    // Включение только после согласия: пакеты весят около 30 МБ.
                                    showTranslateConsent = true
                                } else {
                                    com.vladimir.messenger.data.translate.TranslationSettings.set(context, false)
                                }
                            },
                        )
                    }
                    if (showTranslateConsent) {
                        AlertDialog(
                            onDismissRequest = { showTranslateConsent = false },
                            title = { Text(stringResource(R.string.translate_consent_title)) },
                            text = { Text(stringResource(R.string.translate_consent_body)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    com.vladimir.messenger.data.translate.TranslationSettings.set(context, true)
                                    showTranslateConsent = false
                                }) { Text(stringResource(R.string.translate_consent_ok)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showTranslateConsent = false }) {
                                    Text(stringResource(R.string.action_cancel))
                                }
                            },
                        )
                    }
                    if (showLanguageDialog) {
                        AlertDialog(
                            onDismissRequest = { showLanguageDialog = false },
                            title = { Text(stringResource(R.string.language_app)) },
                            text = {
                                Column {
                                    com.vladimir.messenger.ui.i18n.AppLanguage.entries.forEach { lang ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable(role = Role.RadioButton) {
                                                    showLanguageDialog = false
                                                    if (lang != appLanguage) {
                                                        com.vladimir.messenger.ui.i18n.AppLanguageHolder.set(context, lang)
                                                        (context as? android.app.Activity)?.recreate()
                                                    }
                                                }
                                                .padding(vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            RadioButton(
                                                selected = lang == appLanguage,
                                                onClick = null,
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(lang.nativeName)
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { showLanguageDialog = false }) {
                                    Text(stringResource(R.string.action_cancel))
                                }
                            },
                        )
                    }
                }
            }

            // ----------------------------------------------------------------
            // Пауза новых сообщений: приложение целиком или нужный раздел.
            // ----------------------------------------------------------------
            item { SettingsSectionTitle(stringResource(R.string.section_notifications)) }
            item {
                SettingsCard {
                    val muteScopes = listOf(
                        NotificationMuteScope.APP to Icons.Default.NotificationsActive,
                        NotificationMuteScope.PERSONAL_CHATS to Icons.Default.Person,
                        NotificationMuteScope.GROUPS to Icons.Default.Groups,
                        NotificationMuteScope.CHANNELS to Icons.Default.Campaign,
                        NotificationMuteScope.TOPICS to Icons.Default.Forum,
                    )
                    muteScopes.forEachIndexed { index, (scope, icon) ->
                        val untilMs = viewModel.notificationMuteUntil(scope)
                        SettingsItem(
                            icon = icon,
                            title = scope.title,
                            subtitle = if (untilMs > notificationMuteNowMs) {
                                notificationMuteStatus(untilMs, notificationMuteNowMs)
                            } else {
                                scope.description
                            },
                            onClick = { muteScope = scope },
                        )
                        if (index < muteScopes.lastIndex) ApuSettingsDivider()
                    }
                }
            }

            item { SettingsSectionTitle(stringResource(R.string.section_security)) }
            item {
                SettingsCard {
                    SettingsItem(
                        icon     = Icons.Default.Shield,
                        title    = stringResource(R.string.settings_identity_protection),
                        // Самая дорогая потеря в мессенджере - оказаться для
                        // всех новым человеком после переустановки. Поэтому
                        // строка честно говорит, защищён человек или нет.
                        subtitle = stringResource(R.string.settings_identity_protection_hint),
                        onClick  = onIdentityBackupClick,
                    )
                    ApuSettingsDivider()
                    SettingsItem(
                        icon     = Icons.Default.Save,
                        title    = stringResource(R.string.settings_backup),
                        // «Защита личности» возвращает только адрес и имя; здесь -
                        // всё: чаты, контакты, сообщества, ключи, ранг, настройки.
                        subtitle = stringResource(R.string.settings_backup_hint),
                        onClick  = onProfileBackupClick,
                    )
                    ApuSettingsDivider()
                    // Раунд 249: выход из профиля - телефон возвращается на
                    // экран входа, где можно войти под другим логином.
                    SettingsItem(
                        icon     = Icons.Default.Logout,
                        title    = stringResource(R.string.settings_logout),
                        subtitle = stringResource(R.string.settings_logout_hint),
                        onClick  = { showLogoutDialog = true },
                    )
                }
            }

            item { SettingsSectionTitle(stringResource(R.string.section_network)) }
            item {
                SettingsCard {
                    SettingsItem(
                        icon  = Icons.Default.Hub,
                        title = stringResource(R.string.settings_connection_status),
                        subtitle = uiState.connectionStatus.displayName,
                        trailingContent = {
                            StatusDot(status = uiState.connectionStatus)
                        }
                    )
                    ApuSettingsDivider()
                    SettingsItem(
                        icon     = Icons.Default.People,
                        // Было «Подключено пиров» - слово из документации, а не
                        // из речи. Теперь это вход в подробности: кто держит
                        // сеть и через кого данные идут первыми.
                        title    = stringResource(R.string.peer_nodes_title),
                        subtitle = "${uiState.connectedPeers} на связи - открыть оценку",
                        onClick  = onPeerRatingClick,
                    )
                    ApuSettingsDivider()
                    SettingsItem(
                        icon     = Icons.Default.Public,
                        title    = stringResource(R.string.settings_public_ip),
                        subtitle = uiState.publicIp ?: stringResource(R.string.settings_public_ip_unknown),
                    )
                    ApuSettingsDivider()
                    SettingsItem(
                        icon  = Icons.Default.RestartAlt,
                        title = stringResource(R.string.settings_restart_engine),
                        subtitle = stringResource(R.string.settings_restart_engine_hint),
                        onClick = viewModel::onRestartEngine,
                    )
                    ApuSettingsDivider()
                    SettingsItem(
                        icon  = Icons.Default.Refresh,
                        title = stringResource(R.string.settings_collect_peers),
                        subtitle = stringResource(R.string.settings_collect_peers_hint),
                        onClick = viewModel::onTriggerGossipDiscovery,
                    )
                    ApuSettingsDivider()
                    // р240: по этим четырём строкам сразу видно, почему не
                    // доходят сообщения: роль, партнёр, канал, недоотправленные.
                    SettingsItem(
                        icon     = Icons.Default.Refresh,
                        title    = stringResource(R.string.settings_sync_diag),
                        subtitle = stringResource(R.string.settings_sync_diag_hint),
                        onClick  = { showMirrorDiag = true },
                    )
                    ApuSettingsDivider()
                    SettingsItem(
                        icon     = Icons.Default.VpnKey,
                        title    = stringResource(R.string.settings_proxy_tunnel),
                        subtitle = stringResource(R.string.settings_proxy_tunnel_hint),
                        trailingContent = {
                            ApuPremiumSwitch(
                                checked = uiState.proxyTunnelEnabled,
                                onCheckedChange = viewModel::onProxyTunnelToggle,
                            )
                        },
                    )
                    ApuSettingsDivider()
                    // Настройка прокси стоит рядом с выключателем прокси, а не
                    // отдельным разделом: раньше два прокси-пункта жили в разных
                    // концах экрана.
                    SettingsItem(
                        icon = Icons.Default.Dns,
                        title = stringResource(R.string.settings_proxy_manual),
                        subtitle = stringResource(R.string.settings_proxy_manual_hint),
                        onClick = onMtProxyClick,
                    )
                }
            }

            // ----------------------------------------------------------------
            // ПЕРЕДАЧА ФАЙЛОВ
            // ----------------------------------------------------------------
            item { SettingsSectionTitle(stringResource(R.string.section_file_transfer)) }
            item {
                SettingsCard {
                    SettingsItem(
                        icon     = Icons.Default.Delete,
                        title    = stringResource(R.string.settings_stop_stuck),
                        subtitle = stringResource(R.string.settings_stop_stuck_hint),
                        onClick  = viewModel::onCancelStalledTransfers,
                    )
                    ApuSettingsDivider()
                    SettingsItem(
                        icon     = Icons.Default.CleaningServices,
                        title    = stringResource(R.string.settings_clear_done),
                        subtitle = stringResource(R.string.settings_clear_done_hint),
                        onClick  = viewModel::onPurgeCompletedTransfers,
                    )
                }
            }

            // ----------------------------------------------------------------
            // РАЗДАЧА: темп, в котором телефон рассылает и раздаёт дальше
            // ----------------------------------------------------------------
            item { SettingsSectionTitle(stringResource(R.string.section_seeding)) }
            item {
                SettingsCard {
                    val context = LocalContext.current
                    // Режим прочитан в MainActivity.onCreate (SwarmSettings.init).
                    val swarmMode by SwarmSettings.mode.collectAsStateWithLifecycle()
                    SwarmMode.entries.forEach { mode ->
                        val limits = SwarmPolicy.limitsFor(mode, metered = false, lowPower = false)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { SwarmSettings.set(context, mode) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ApuPremiumRadioButton(
                                selected = swarmMode == mode,
                                onClick = { SwarmSettings.set(context, mode) },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(mode.title, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    if (mode == SwarmMode.UNLIMITED) {
                                        stringResource(R.string.settings_swarm_full)
                                    } else {
                                        "до ${limits.maxPacketsPerMinute} пакетов в минуту, " +
                                            "${limits.maxConcurrentSends} одновременно"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    ApuSettingsDivider()
                    SettingsItem(
                        icon = Icons.Default.Groups,
                        title = stringResource(R.string.settings_seed_priority),
                        subtitle = "Сначала контактам, проверенным и стабильным узлам, " +
                            "потом всем остальным. В обычном и экономном режимах на " +
                            "мобильном интернете и при заряде ниже " +
                            "${SwarmPolicy.LOW_BATTERY_PERCENT} % темп вдвое ниже.",
                    )
                }
            }

            // ----------------------------------------------------------------
            // МЕСТО ПОД ПЕРЕСЫЛКУ: сколько байт телефон отдаёт как сервер
            // ----------------------------------------------------------------
            item {
                SettingsCard {
                    val context = LocalContext.current
                    // Раунд 215: режим «Я сервер» - телефон решает, хранит ли он
                    // чужие данные и раздаёт ли их, или работает абонентом
                    // (только свои неотправленные данные ждут доставки).
                    val imServer by com.vladimir.messenger.data.swarm.ServerMode.enabled
                        .collectAsStateWithLifecycle()
                    SettingsItem(
                        icon = Icons.Default.Storage,
                        title = stringResource(R.string.settings_i_am_server),
                        subtitle = if (imServer) {
                            stringResource(R.string.settings_relay_on)
                        } else {
                            stringResource(R.string.settings_relay_off)
                        },
                        trailingContent = {
                            ApuPremiumSwitch(
                                checked = imServer,
                                onCheckedChange = {
                                    com.vladimir.messenger.data.swarm.ServerMode.set(context, it)
                                },
                            )
                        },
                    )
                    ApuSettingsDivider()
                    // Квота прочитана в MainActivity.onCreate (StorageSettings.init).
                    val quota by StorageSettings.quotaBytes.collectAsStateWithLifecycle()
                    // Ползунок двигается по положениям шкалы; в настройки и
                    // контактам уходит только отпущенное значение, а не каждый
                    // кадр перетаскивания.
                    var step by remember(quota) { mutableIntStateOf(StoragePolicy.nearestStep(quota)) }
                    // Занятое место считается обходом папок - не на главном потоке.
                    val usage by produceState<StorageSettings.Usage?>(initialValue = null, key1 = quota) {
                        value = withContext(Dispatchers.IO) {
                            runCatching { StorageSettings.usage(context) }.getOrNull()
                        }
                    }
                    val free by produceState(initialValue = 0L, key1 = quota) {
                        value = withContext(Dispatchers.IO) { StorageSettings.freeBytes(context) }
                    }
                    // Чужие файлы на хранении (этап 7 роя) - часть «кусков
                    // файлов», но человеку важно видеть, сколько из них не его.
                    val held by produceState(initialValue = 0L, key1 = quota) {
                        value = withContext(Dispatchers.IO) {
                            runCatching { viewModel.custodyHeldBytes() }.getOrDefault(0L)
                        }
                    }
                    SettingsItem(
                        icon = Icons.Default.Storage,
                        title = "Место под пересылку: ${StoragePolicy.format(StoragePolicy.stepBytes(step))}",
                        subtitle = stringResource(R.string.settings_space_body),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            StoragePolicy.format(StoragePolicy.MIN_QUOTA_BYTES),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ApuPremiumSlider(
                            value = step.toFloat(),
                            onValueChange = {
                                step = it.roundToInt().coerceIn(0, StoragePolicy.STEPS.lastIndex)
                            },
                            onValueChangeFinished = {
                                StorageSettings.set(context, StoragePolicy.stepBytes(step))
                            },
                            valueRange = 0f..StoragePolicy.STEPS.lastIndex.toFloat(),
                            steps = StoragePolicy.STEPS.size - 2,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                        )
                        Text(
                            StoragePolicy.format(StoragePolicy.MAX_QUOTA_BYTES),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val used = usage
                    Text(
                        if (used == null) {
                            stringResource(R.string.settings_counting_space)
                        } else {
                            "Занято сейчас: ${StoragePolicy.format(used.total)} " +
                                "(куски файлов ${StoragePolicy.format(used.chunkBytes)}" +
                                (if (held > 0L) ", из них чужих на хранении ${StoragePolicy.format(held)}" else "") +
                                ", принятые файлы ${StoragePolicy.format(used.receivedBytes)}, " +
                                (if (used.groupFileBytes > 0L) "мои файлы для раздачи в сообществах ${StoragePolicy.format(used.groupFileBytes)}, " else "") +
                                "очередь сообщений ${StoragePolicy.format(used.relayBytes)}). " +
                                "Свободно на телефоне: ${StoragePolicy.format(free)}; последние " +
                                "${StoragePolicy.format(StoragePolicy.FREE_RESERVE_BYTES)} не занимаются никогда."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
            }

            // Раздел «Безопасность» с экспортом ключей убран: кнопка вела к
            // незавершённой выгрузке, которая ничего не сохраняла. Появится
            // снова, когда восстановление ключей будет работать целиком.

            // ----------------------------------------------------------------
            // ОБНОВЛЕНИЯ: APK-файл приложения раздаётся телефонами роем
            // (docs/UPDATE_SEEDING.md) — без сервера, по кусочкам, как файлы
            // групп. Новая версия видна тем, у кого версия ниже.
            // ----------------------------------------------------------------
            item { SettingsSectionTitle(stringResource(R.string.section_updates)) }
            item { ApkUpdatesCard(viewModel) }

            // ----------------------------------------------------------------
            // О ПРИЛОЖЕНИИ
            // ----------------------------------------------------------------
            item { SettingsSectionTitle(stringResource(R.string.section_server)) }
            item {
                SettingsCard {
                    // Наш relay-сервер: реестр, приглашения, обновления и
                    // рой-брокер. Строку состояния обновляет ViewModel
                    // (замер отклика /health).
                    SettingsItem(
                        icon     = Icons.Default.Dns,
                        // Адрес сервера не показываем (просьба владельца,
                        // 2026-09-19): в интерфейсе только нейтральное имя.
                        title    = stringResource(R.string.settings_our_server),
                        subtitle = uiState.serverStatus,
                    )
                    // Диагностика брокерной линии: режим (наш сервер или
                    // запасные), давность ConnAck, последняя ошибка.
                    if (uiState.mqttLink.isNotBlank()) {
                        SettingsItem(
                            icon     = Icons.Default.NetworkCheck,
                            title    = stringResource(R.string.settings_network_messages),
                            // Раунд 190: человекочитаемая строка; если разбор
                            // не удался - сырая, как раньше.
                            subtitle = uiState.mqttHuman.ifBlank { uiState.mqttLink },
                            onClick  = { showMqttDialog = true },
                        )
                    }
                    // Облачная копия азбуки адресов: сама раз в 6 часов
                    // и по кнопке (диалог ниже).
                    SettingsItem(
                        icon     = Icons.Default.CloudSync,
                        title    = stringResource(R.string.settings_address_backup),
                        subtitle = uiState.addrBookLine.ifBlank { "…" },
                        onClick  = { showAddrBookDialog = true },
                    )
                }
            }

            // Обычная синхронизация уже настроенных телефонов идёт в фоне
            // через живое зеркало. Это окно - только разовый перенос профиля.
            item { SettingsSectionTitle(stringResource(R.string.section_devices)) }
            item {
                SettingsCard {
                    SettingsItem(
                        icon     = Icons.Default.Sync,
                        title    = stringResource(R.string.settings_profile_transfer),
                        subtitle = stringResource(R.string.settings_profile_transfer_hint),
                        onClick  = { showSyncDialog = true },
                    )
                }
            }

            // ----------------------------------------------------------------
            // ЛОГИ: отчёт для проверки прямой F4-передачи на двух телефонах.
            // ----------------------------------------------------------------
            item { SettingsSectionTitle(stringResource(R.string.section_support)) }
            item {
                SettingsCard {
                    SettingsItem(
                        icon = Icons.Default.Description,
                        title = stringResource(R.string.settings_logs),
                        subtitle = stringResource(R.string.settings_logs_hint),
                        onClick = { showTransferLogsDialog = true },
                    )
                }
            }

            item { SettingsSectionTitle(stringResource(R.string.section_about)) }
            item {
                SettingsCard {
                    // Раунд 219: «Поддержать разработчика». Реквизитов в коде
                    // нет: список способов экран получает из нашего сервиса.
                    SettingsItem(
                        icon     = Icons.Default.Favorite,
                        title    = stringResource(R.string.settings_support_dev),
                        subtitle = stringResource(R.string.settings_support_dev_hint),
                        onClick  = onSupportClick,
                    )
                    SettingsItem(
                        icon     = Icons.Default.Info,
                        title    = stringResource(R.string.settings_version),
                        subtitle = "APU ${uiState.appVersion}",
                    )
                    // Какое ядро внутри: строку отдаёт само ядро, поэтому
                    // по ней видно, что мост Kotlin ↔ Rust собран из свежего
                    // lib.udl (первая функция после снятия заморозки).
                    SettingsItem(
                        icon     = Icons.Default.Memory,
                        title    = stringResource(R.string.settings_core),
                        subtitle = uiState.rustCoreVersion,
                    )
                }
            }
        }
        ApuScrollbar(state = settingsScrollState)
    }

    // Раунд 223: окно синхронизации аккаунта.
    if (showSyncDialog) {
        ProfileSyncDialog(onDismiss = { showSyncDialog = false })
    }

    // Раунд 249: подтверждение выхода. Текст честно говорит, что будет со
    // старым профилем: без «Защиты личности» вернуться в него нельзя.
    if (showLogoutDialog) {
        val nick = uiState.protectedNick
        ApuSettingsDialog(
            onDismissRequest = { showLogoutDialog = false },
            icon = { Icon(Icons.Default.Logout, contentDescription = null) },
            title = { Text(stringResource(R.string.settings_logout_title)) },
            text = {
                Text(
                    buildString {
                        append(
                            stringResource(R.string.settings_logout_body),
                        )
                        append("\n\n")
                        if (nick.isNullOrBlank()) {
                            append(
                                stringResource(R.string.settings_logout_warning),
                            )
                        } else {
                            append(
                                "Вернуться в текущий профиль можно в любой момент: на экране " +
                                    "входа вкладка «Я уже зарегистрирован», никнейм @$nick и ваш пароль.",
                            )
                        }
                    },
                )
            },
            confirmButton = {
                ApuTextAction(label = stringResource(R.string.action_logout), onClick = { viewModel.logout() }, danger = true)
            },
            dismissButton = {
                ApuTextAction(label = stringResource(R.string.action_cancel), onClick = { showLogoutDialog = false })
            },
        )
    }

    if (showAddrBookDialog) {
        ApuSettingsDialog(
            onDismissRequest = { showAddrBookDialog = false },
            title = { Text(stringResource(R.string.settings_address_backup)) },
            text = {
                Text(
                    (uiState.addrBookLine.ifBlank { "…" }) + "\n\n" +
                        uiState.addrBookMessage.ifBlank {
                            stringResource(R.string.settings_backup_note)
                        }
                )
            },
            confirmButton = {
                ApuTextAction(
                    label = stringResource(R.string.action_create_copy),
                    onClick = { viewModel.backupAddressBookNow() },
                )
            },
            dismissButton = {
                ApuTextAction(
                    label = stringResource(R.string.action_restore),
                    onClick = { viewModel.restoreAddressBookNow() },
                )
            },
        )
    }

    if (showMqttDialog) {
        // Раунд 190: диалог по-человечески. В «Скопировать» идёт и сырая
        // строка ядра - по ней в чате разработки видно режим, ConnAck и
        // точный текст ошибки.
        val mqttHumanText = buildString {
            if (uiState.mqttHuman.isNotBlank()) {
                append(uiState.mqttHuman)
                append("\n\n")
                append("Путь выбирается сам: сначала прямой, если сеть его ")
                append("не пропускает — через обходной канал, и потом обратно. ")
                append("Нажимать ничего не нужно.")
                if (uiState.mqttLink.contains(", ошибка ")) {
                    append("\n\nПроверка «Наш сервер» и брокер сообщений — разные ")
                    append("соединения. Если брокер не ответил, приложение повторяет ")
                    append("подключение само; отправлять копию профиля не нужно.")
                }
            } else {
                append(uiState.mqttLink)
            }
        }
        ApuSettingsDialog(
            onDismissRequest = { showMqttDialog = false },
            title = { Text(stringResource(R.string.settings_network_dialog_title)) },
            text = { Text(mqttHumanText) },
            confirmButton = {
                ApuTextAction(
                    label = stringResource(R.string.action_copy),
                    onClick = {
                    val forDiagnostics = listOf(uiState.mqttHuman, uiState.mqttLink)
                        .filter { it.isNotBlank() }
                        .joinToString("\n\n")
                    mqttClipboard.setText(androidx.compose.ui.text.AnnotatedString(forDiagnostics))
                    showMqttDialog = false
                },
                )
            },
            dismissButton = {
                ApuTextAction(label = stringResource(R.string.action_close), onClick = { showMqttDialog = false })
            },
        )
    }

    if (showTransferLogsDialog) {
        val logsSnapshot = transferLogsSnapshot
        val reportText = logsSnapshot?.report.orEmpty()
        // Владелец 2026-10-07: «как то всё по пенсионерски и по деревенски…
        // цвета яркие и чёткие, объём кнопок выразителен, блеск премиальный».
        // Шапка окна — тёмный градиентный баннер со статусом, строки проверок —
        // яркие «well»-метки, отчёт — тёмная консоль, кнопки — золотая
        // глянцевая и стеклянные. Подложка остаётся house-стиля
        // (ApuSettingsDialog/ApuSettingsCard), ничего stock Material.
        ApuSettingsDialog(
            onDismissRequest = { showTransferLogsDialog = false },
            icon = { Icon(Icons.Default.Terminal, contentDescription = null) },
            title = { Text(stringResource(R.string.settings_logs)) },
            text = {
                // Диалог прокручивает сводку целиком; только длинный список и
                // сырая консоль имеют собственные ограниченные области.
                Column(
                    // ApuSettingsDialog уже даёт одну прокрутку всей области.
                    // Вторая вложенная вертикальная прокрутка перехватывала жесты.
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val statusLines = logsSnapshot?.statusLines.orEmpty()
                    ApuDiagnosticsHero(
                        lines = statusLines,
                        appVersion = logsSnapshot?.appVersion,
                        collectedAt = logsSnapshot?.let { DiagnosticsReport.clock(it.createdAtMs) },
                        journalSize = logsSnapshot?.journalSize ?: 0,
                        warnCount = logsSnapshot?.warnCount ?: 0,
                        badCount = logsSnapshot?.badCount ?: 0,
                    )
                    // Ход работ и ошибка говорятся словами — окно никогда не
                    // молчит пустым экраном.
                    val stageLine = when {
                        transferLogsLoading -> transferLogsStage ?: stringResource(R.string.settings_collecting_report)
                        transferLogsError != null -> transferLogsError
                        else -> null
                    }
                    if (stageLine != null) {
                        Text(
                            stageLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (transferLogsLoading) {
                                ApuBubbleMutedColor
                            } else {
                                ApuSettingsDangerColor
                            },
                        )
                        if (transferLogsLoading && transferLogsSlow) {
                            Text(
                                stringResource(R.string.settings_slow_collect),
                                style = MaterialTheme.typography.bodySmall,
                                color = ApuBubbleMutedColor,
                            )
                        }
                    } else {
                        ApuDiagnosticsStatusCard(lines = logsSnapshot?.statusLines.orEmpty())
                    }
                    // Список событий — то, что человек называет «логи»: он виден
                    // сразу, как только пришла быстрая часть отчёта.
                    ApuDiagnosticsEventList(events = logsSnapshot?.events.orEmpty())
                    // Обещание приватности — тёмной стеклянной полосой со щитом:
                    // его читают, а не пролистывают.
                    ApuDiagnosticsPrivacyStrip(
                        stringResource(R.string.settings_report_info),
                    )
                    ApuDiagnosticsReportCard(
                        text = reportText.ifBlank { stringResource(R.string.settings_collecting_report) },
                        maxHeight = 260.dp,
                    )
                }
            },
            confirmButton = {
                // На узком экране четыре кнопки в одной строке сжимались по
                // ширине: «Отправить» исчезала за краем. Сетка 2×2 даёт всем
                // действиям одинаковую ширину и оставляет главную кнопку первой.
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val logsTitle = stringResource(R.string.settings_logs_title)
                        val logsCopied = stringResource(R.string.settings_logs_copied)
                        // Кнопка всегда живая: при сборке отчёта объясняет,
                        // что происходит, вместо того чтобы молчать.
                        ApuDiagnosticsActionButton(
                            label = stringResource(R.string.action_send),
                            icon = DiagnosticsActionIcons.Send,
                            modifier = Modifier.weight(1f),
                            style = DiagnosticsActionStyle.PRIMARY,
                            compact = true,
                            onClick = {
                                if (reportText.isNotBlank()) {
                                    AppShare.shareText(settingsContext, reportText, logsTitle)
                                } else {
                                    apuDiagnosticsNothingYet(settingsContext, transferLogsStage)
                                }
                            },
                        )
                        ApuDiagnosticsActionButton(
                            label = stringResource(R.string.action_copy),
                            icon = DiagnosticsActionIcons.Copy,
                            modifier = Modifier.weight(1f),
                            compact = true,
                            onClick = {
                                if (reportText.isNotBlank()) {
                                    mqttClipboard.setText(AnnotatedString(reportText))
                                    android.widget.Toast.makeText(
                                        settingsContext,
                                        logsCopied,
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    apuDiagnosticsNothingYet(settingsContext, transferLogsStage)
                                }
                            },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ApuDiagnosticsActionButton(
                            label = stringResource(R.string.action_refresh),
                            icon = DiagnosticsActionIcons.Refresh,
                            modifier = Modifier.weight(1f),
                            style = DiagnosticsActionStyle.QUIET,
                            compact = true,
                            onClick = {
                                transferLogsStage = TransferDiagnostics.STAGE_DEVICE
                                transferLogsLoading = true
                                transferLogsRefresh++
                            },
                        )
                        ApuDiagnosticsActionButton(
                            label = stringResource(R.string.action_close),
                            modifier = Modifier.weight(1f),
                            style = DiagnosticsActionStyle.QUIET,
                            compact = true,
                            onClick = { showTransferLogsDialog = false },
                        )
                    }
                }
            },

        )
    }

    muteScope?.let { scope ->
        val untilMs = viewModel.notificationMuteUntil(scope)
        NotificationMuteDialog(
            targetName = scope.title,
            mutedUntilMs = untilMs,
            onSelectUntil = { chosenUntil ->
                viewModel.setNotificationMuteUntil(scope, chosenUntil)
                muteScope = null
            },
            onTurnOn = {
                viewModel.setNotificationMuteUntil(scope, 0L)
                muteScope = null
            },
            onDismiss = { muteScope = null },
        )
    }

    if (showMirrorDiag) {
        // р243: текст диагностики - в фоновом потоке. MirrorHub.debugStatus()
        // спрашивает ядро (JNI), и вызов при отрисовке окна давал «APU не
        // отвечает» ровно в тот момент, когда человек смотрит на диагностику
        // (скриншот владельца 30.09 21:20).
        var syncText by remember { mutableStateOf("Собираю…") }
        LaunchedEffect(showMirrorDiag) {
            syncText = withContext(Dispatchers.IO) {
                com.vladimir.messenger.data.mirror.MirrorHub.debugStatus()
            }
        }
        ApuSettingsDialog(
            onDismissRequest = { showMirrorDiag = false },
            title = { Text(stringResource(R.string.settings_sync_devices)) },
            text = {
                Text(
                    syncText + stringResource(R.string.settings_sync_hint),
                )
            },
            confirmButton = {
                ApuTextAction(
                    label = stringResource(R.string.action_copy),
                    onClick = {

                    mqttClipboard.setText(
                        androidx.compose.ui.text.AnnotatedString(syncText),
                    )
                    showMirrorDiag = false
                },
                )
            },
            dismissButton = {
                ApuTextAction(label = stringResource(R.string.settings_close), onClick = { showMirrorDiag = false })
            },
        )
    }
}

// =============================================================================
// ДИАЛОГ ВЫБОРА АВАТАРА
// =============================================================================

// =============================================================================
// ВСПОМОГАТЕЛЬНЫЕ КОМПОНЕНТЫ
// =============================================================================

@Composable
private fun ThemeModeChoices(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        val horizontal = ApuSettingsLayout.horizontalThemeChoices(maxWidth.value, fontScale)
        Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val groups = if (horizontal) listOf(ThemeMode.entries.toList()) else ThemeMode.entries.map { listOf(it) }
            groups.forEach { group ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    group.forEach { mode ->
                        val checked = selected == mode
                        val shape = RoundedCornerShape(14.dp)
                        val label = when (mode) {
                            ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_auto)
                            ThemeMode.LIGHT -> stringResource(R.string.settings_theme_day)
                            ThemeMode.DARK -> stringResource(R.string.settings_theme_night)
                        }
                        val icon = when (mode) {
                            ThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
                            ThemeMode.LIGHT -> Icons.Default.LightMode
                            ThemeMode.DARK -> Icons.Default.DarkMode
                        }
                        Column(
                            // Выбранный режим — золотая плитка с глянцем и тенью
                            // (премиальный слой, как в «Логах»); остальные —
                            // спокойное стекло, чтобы выбор читался сразу.
                            modifier = Modifier.weight(1f)
                                .then(
                                    if (checked) {
                                        Modifier.apuPremiumLift(8.dp, shape, ApuGold.copy(alpha = 0.45f))
                                    } else {
                                        Modifier
                                    },
                                )
                                .clip(shape)
                                .then(
                                    if (checked) {
                                        Modifier.background(apuGoldBrush(), shape)
                                    } else {
                                        Modifier.background(ApuBubbleAccentColor.copy(alpha = 0.06f), shape)
                                    },
                                )
                                .border(
                                    1.dp,
                                    if (checked) Color.White.copy(alpha = 0.5f) else ApuBubbleAccentColor.copy(alpha = 0.16f),
                                    shape,
                                )
                                .then(
                                    if (checked) Modifier.apuPremiumGloss(shape, intensity = 0.85f, topFraction = 0.7f) else Modifier,
                                )
                                .selectable(checked, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = { onSelect(mode) })
                                .heightIn(min = 64.dp).padding(horizontal = 8.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            val ink = if (checked) ApuGoldInk else ApuBubbleAccentColor
                            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
                            Text(label, style = MaterialTheme.typography.labelLarge, color = ink, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Text(
                if (selected == ThemeMode.SYSTEM) stringResource(R.string.settings_theme_follows) else "Выбрано: ${selected.title.lowercase()}",
                style = MaterialTheme.typography.bodySmall,
                color = ApuBubbleMutedColor,
            )
        }
    }
}

@Composable
private fun SettingsSectionTitle(title: String) = ApuSettingsSectionTitle(title)

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    ApuSettingsCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        content = content,
    )
}

@Composable
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) = ApuSettingsItem(icon, title, subtitle, onClick, trailingContent)

/**
 * Карточка «Обновления» (docs/UPDATE_SEEDING.md): APK раздаётся телефонами
 * роем, как файлы групп. Состояние живое (StateFlow сидера): моя раздача,
 * предложения соседей (только версии новее моей), приём, готовое к установке
 * и принятые APK-файлы.
 */
/**
 * Что помечать как обновление: принятый файл (transferId). Файл из
 * проводника идёт отдельным путём (SettingsViewModel.ApkPickUi): имя и
 * версия читаются из самого файла автоматически.
 */
private data class MarkTarget(
    val title: String,
    val versionGuess: String,
    val transferId: String,
)

@Composable
private fun ApkUpdatesCard(viewModel: SettingsViewModel) {
    val seed by viewModel.apkSeed.collectAsStateWithLifecycle()
    val offers by viewModel.apkOffers.collectAsStateWithLifecycle()
    val download by viewModel.apkDownload.collectAsStateWithLifecycle()
    val ready by viewModel.apkReady.collectAsStateWithLifecycle()
    val receivedApks by viewModel.apkReceivedApks.collectAsStateWithLifecycle()
    val patchOffers by viewModel.apkPatchOffers.collectAsStateWithLifecycle()
    val patchDownload by viewModel.apkPatchDownload.collectAsStateWithLifecycle()
    val checking by viewModel.updatesChecking.collectAsStateWithLifecycle()
    val official by viewModel.officialRelease.collectAsStateWithLifecycle()
    val apkPick by viewModel.apkPick.collectAsStateWithLifecycle()
    var markTarget by remember { mutableStateOf<MarkTarget?>(null) }

    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Имя и версия возьмутся из самого файла — покажем их в диалоге.
            viewModel.onApkPicked(uri)
        }
    }

    LaunchedEffect(Unit) { viewModel.onUpdatesAppeared() }

    SettingsCard {
        // Моя раздача.
        seed?.let { s ->
            SettingsItem(
                icon    = Icons.Default.Share,
                title   = if (s.preparing) {
                    "Готовлю раздачу обновления v${s.version}"
                } else {
                    "Раздаю обновление v${s.version}"
                },
                subtitle = "${s.name}, ${StoragePolicy.format(s.sizeBytes)}; получили: ${s.served}",
            )
            ApuSettingsDivider()
            SettingsItem(
                icon    = Icons.Default.Close,
                title   = stringResource(R.string.settings_stop_sharing),
                subtitle = stringResource(R.string.settings_stop_sharing_hint),
                onClick = viewModel::onStopUpdateSeed,
            )
        }
        // Готово к установке (раздача уже идёт — новая версия расходится).
        // Кнопка показывает, ЧТО поставит: «Обновить до vX». Появляется и
        // сама — когда скачанный с сайта файл встал в раздел.
        ready?.let { r ->
            SettingsItem(
                icon    = Icons.Default.SystemUpdate,
                title   = "Обновить до v${r.version}",
                subtitle = "${r.name}, ${StoragePolicy.format(r.sizeBytes)}; уже раздаётся соседям",
                onClick = viewModel::onInstallUpdate,
            )
        }
        // Приём идёт: куски собираются со всех сидов этой версии.
        download?.let { d ->
            SettingsItem(
                icon    = Icons.Default.Download,
                title   = "Принимаю v${d.version}",
                subtitle = StoragePolicy.format(d.receivedBytes) + " из " + StoragePolicy.format(d.totalBytes) +
                    "; кусками от " + offers.size.coerceAtLeast(1).toString() + " сосед(ей)",
            )
            ApuSettingsDivider()
            SettingsItem(
                icon    = Icons.Default.Close,
                title   = stringResource(R.string.settings_stop_receiving),
                onClick = viewModel::onCancelUpdateDownload,
            )
        }
        // Раунд 133: приём ДИФФ-ПАТЧА (только разница версий) от соседа.
        patchDownload?.let { pd ->
            SettingsItem(
                icon    = Icons.Default.Download,
                title   = "Принимаю v${pd.version} (компактно)",
                subtitle = StoragePolicy.format(pd.receivedBytes) + " из " + StoragePolicy.format(pd.totalBytes) +
                    " — качаем только разницу версий",
            )
            ApuSettingsDivider()
            SettingsItem(
                icon    = Icons.Default.Close,
                title   = stringResource(R.string.settings_stop_receiving),
                onClick = viewModel::onCancelPatchDownload,
            )
        }
        // Раунд 133: сосед раздаёт ПАТЧ для ровно нашей версии — качаем
        // разницу, а не весь APK. Соберём файл на месте и сверим sha256.
        val patchBest = patchOffers.firstOrNull()
        if (patchBest != null && download == null && patchDownload == null && ready == null) {
            SettingsItem(
                icon    = Icons.Default.Download,
                title   = "Скачать компактно v" + patchBest.toVersion,
                subtitle = "Патч " + StoragePolicy.format(patchBest.sizeBytes) +
                    " от " + patchOffers.size.coerceAtLeast(1).toString() + " сосед(ей) — только разница версий",
                onClick = { viewModel.onDownloadPatchFrom(patchBest.nodeId) },
            )
        }
        // Выбор, откуда качать. Проверка нашла обновление И на официальном
        // сайте, И у соседей в сети — показываем обе кнопки рядом.
        // Локальные копии: делегированные свойства не умнее cast'ов, а
        // условие в переменной не даёт smart cast для best.
        val officialNow = official
        val best = offers.firstOrNull()
        if (officialNow != null && best != null && download == null && patchDownload == null && ready == null) {
            Text(
                stringResource(R.string.settings_update_two_places),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
            SettingsItem(
                icon    = Icons.Default.CloudDownload,
                title   = "С официального сайта v" + officialNow.version.removePrefix("v"),
                subtitle = stringResource(R.string.settings_via_internet),
                onClick = viewModel::onDownloadOfficialRelease,
            )
            SettingsItem(
                icon    = Icons.Default.Download,
                title   = "По сети v" + best.version,
                subtitle = "Кусками от " + offers.size + " сосед(ей) — без интернета",
                onClick = { viewModel.onDownloadUpdateFrom(best.nodeId) },
            )
        } else {
            // Предложение соседа: лучший (самый новый) первый. Версия — на
            // самой кнопке: «Скачать v…».
            if (best != null && download == null && patchDownload == null && ready == null) {
                SettingsItem(
                    icon    = Icons.Default.Download,
                    title   = "Скачать v" + best.version,
                    subtitle = if (offers.size > 1) {
                        "Раздают " + offers.size + " соседа; " + StoragePolicy.format(best.sizeBytes)
                    } else {
                        "Раздаёт сосед; " + StoragePolicy.format(best.sizeBytes)
                    },
                    onClick = { viewModel.onDownloadUpdateFrom(best.nodeId) },
                )
            }
            // Официальный релиз, найденный кнопкой «Проверить новую версию».
            official?.let { rel ->
                SettingsItem(
                    icon    = Icons.Default.CloudDownload,
                    title   = "Скачать официальный v" + rel.version.removePrefix("v"),
                    subtitle = stringResource(R.string.settings_from_github),
                    onClick = viewModel::onDownloadOfficialRelease,
                )
            }
        }
        // Принятые APK: «раздать полученный» (сценарий: APK переслан с ПК).
        receivedApks.forEach { apk ->
            SettingsItem(
                icon    = Icons.Default.InsertDriveFile,
                title   = apk.displayName,
                subtitle = buildString {
                    append("Получен, ")
                    append(StoragePolicy.format(apk.sizeBytes))
                    apk.versionGuess?.let { append(", версия ")
                        append(it) }
                },
                onClick = {
                    markTarget = MarkTarget(
                        title        = stringResource(R.string.settings_share_received),
                        versionGuess = apk.versionGuess ?: "",
                        transferId   = apk.transferId,
                    )
                },
            )
        }
        // Пометить файл (с ПК / из проводника): имя и версия читаются сами.
        SettingsItem(
            icon    = Icons.Default.FileOpen,
            title   = stringResource(R.string.settings_mark_apk),
            subtitle = "Имя и версия возьмутся из файла; проверим (это APK, версия новее текущей) и раздаём всем, у кого ниже версия",
            onClick = { apkPicker.launch(arrayOf("application/vnd.android.package-archive")) },
        )
        // «Проверить новую версию»: спросить соседей (upask) + посмотреть
        // официальный релиз. Пока идёт — кнопка замирает на «Проверяю…».
        ApuSettingsDivider()
        SettingsItem(
            icon    = Icons.Default.Refresh,
            title   = if (checking) stringResource(R.string.settings_checking) else stringResource(R.string.settings_check_version),
            subtitle = stringResource(R.string.settings_check_hint),
            onClick = if (checking) null else viewModel::onCheckForUpdates,
        )
    }

    markTarget?.let { target ->
        ApkVersionDialog(
            title          = target.title,
            initialVersion = target.versionGuess,
            onConfirm = { version ->
                markTarget = null
                viewModel.onMarkReceivedApkAsUpdate(target.transferId, version)
            },
            onDismiss = { markTarget = null },
        )
    }

    // Файл выбран в проводнике: имя и версия читаются из него самого.
    apkPick?.let { pick ->
        if (pick.error == null) {
            ApkVersionDialog(
                title          = stringResource(R.string.settings_share_update_q),
                fileName       = pick.displayName,
                sizeText       = StoragePolicy.format(pick.sizeBytes),
                initialVersion = pick.version ?: "",
                reading        = pick.tempPath == null,
                onConfirm = { version -> viewModel.onApkPickConfirm(version) },
                onDismiss = { viewModel.onApkPickCancel() },
            )
        }
    }
}

/**
 * Диалог подтверждения версии обновления: числовая (например 11.70.29),
 * должна быть новее текущей. Версия подставляется сама (из имени или из
 * самого APK); человек может исправить. [reading] — файл ещё копируется,
 * версия вот-вот заполнится.
 */
@Composable
private fun ApkVersionDialog(
    title: String,
    initialVersion: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    fileName: String? = null,
    sizeText: String? = null,
    reading: Boolean = false,
) {
    var version by remember(initialVersion) { mutableStateOf(initialVersion) }
    ApuSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (fileName != null) {
                    Text(
                        fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (sizeText != null) {
                    Text(
                        "Размер: " + sizeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (reading) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.settings_reading_version),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    stringResource(R.string.settings_version_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(10.dp))
                ApuBubbleField(
                    value        = version,
                    onValueChange = { version = it },
                    singleLine = true,
                    label      = { Text(stringResource(R.string.settings_version_label)) },
                    modifier   = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            ApuTextAction(
                label = stringResource(R.string.settings_share_btn),
                onClick = { onConfirm(version) },
                enabled = !reading && version.isNotBlank(),
            )
        },
        dismissButton = {
            ApuTextAction(label = stringResource(R.string.action_cancel), onClick = onDismiss)
        },
    )
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

// Расширение для отображения статуса
private val com.vladimir.messenger.data.repository.NetworkStatus.displayName: String
    get() = when (this) {
        com.vladimir.messenger.data.repository.NetworkStatus.Connected    -> "Подключен"
        com.vladimir.messenger.data.repository.NetworkStatus.Connecting   -> "Подключение..."
        com.vladimir.messenger.data.repository.NetworkStatus.Degraded     -> "Через ретранслятор"
        com.vladimir.messenger.data.repository.NetworkStatus.Disconnected -> "Нет соединения"
    }

/**
 * Отчёт ещё собирается: кнопка не молчит, а объясняет, где именно идёт сбор.
 * Владелец 2026-10-07: «кнопки в новом не работают» — мёртвых кнопок быть не
 * должно: у нажатия всегда есть видимый ответ.
 */
private fun apuDiagnosticsNothingYet(context: android.content.Context, stage: String?) {
    val text = stage?.takeIf { it.isNotBlank() }
        ?.let { "Отчёт собирается: $it" }
        ?: "Отчёт ещё собирается — нажмите «Обновить»"
    android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
}
