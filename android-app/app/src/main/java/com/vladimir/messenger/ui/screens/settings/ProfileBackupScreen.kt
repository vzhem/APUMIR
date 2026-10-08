package com.vladimir.messenger.ui.screens.settings

import com.vladimir.messenger.R
import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.ui.components.ApuBubbleField
import com.vladimir.messenger.ui.components.ApuSettingsDialog

import com.vladimir.messenger.ui.components.ApuSettingsCard
import com.vladimir.messenger.ui.components.ApuSettingsHeader

// =============================================================================
// PROFILEBACKUPSCREEN.KT — «Резервная копия»
// =============================================================================
// Полная копия профиля в один файл: чаты, контакты, сообщества и каналы,
// ключи шифрования, ранг, настройки, аватар и (по желанию) полученные файлы.
// Файл защищён паролем и лежит там, куда его положит человек: «Файлы»
// телефона, флешка, облако. Восстановление - из этого же экрана либо с
// первого экрана после переустановки.
// =============================================================================

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.vladimir.messenger.data.backup.BackupCipher
import com.vladimir.messenger.data.backup.BackupSchedule
import com.vladimir.messenger.ui.components.ApuTextAction
import com.vladimir.messenger.ui.components.ChatWallpaper
import com.vladimir.messenger.ui.components.HintBubble
import com.vladimir.messenger.ui.components.HintBubbleMutedColor
import com.vladimir.messenger.ui.components.HintBubbleTextColor
import com.vladimir.messenger.ui.components.swipeBack
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.vladimir.messenger.ui.components.ApuPremiumContentButton
import com.vladimir.messenger.ui.components.ApuPremiumSwitch
import com.vladimir.messenger.ui.components.DiagnosticsActionStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileBackupScreen(
    onBackClick: () -> Unit,
    viewModel: ProfileBackupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var includeReceived by remember { mutableStateOf(true) }
    var restorePassword by remember { mutableStateOf("") }
    var autoPeriod by remember { mutableStateOf(BackupSchedule.Period.WEEKLY) }
    // Раунд 251: файл, на котором включаем автообновление заново (найденный
    // или выбранный), и его пароль из диалога.
    var autoAttachTarget by remember { mutableStateOf<FoundBackup?>(null) }
    var autoAttachPassword by remember { mutableStateOf("") }
    // Вернулись на экран - задача могла отработать, перечитываем итог; заодно
    // автопоиск копий (вдруг файл появился или разрешение сохранилось).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshSchedule()
        viewModel.refreshFoundBackups()
    }
    // Закрыть задачу целиком: процесс умрёт следом, а при следующем запуске
    // копия ляжет на место. Активность ищем по цепочке контекстов. Объявлен
    // ДО лаунчеров: они сохраняют разрешение через этот же контекст.
    val activityContext = LocalContext.current

    // Диалоги системы: «куда сохранить» и «какой файл открыть». Пароль
    // берём из полей на момент выбора файла.
    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        if (uri != null) {
            // Раунд 250: сохраняем разрешение на созданный файл - автопоиск
            // восстановления найдёт его в следующий раз без проводника.
            runCatching {
                activityContext.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.create(uri, password, includeReceived)
        }
    }
    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                activityContext.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.stage(uri, restorePassword)
        }
    }
    // Раунд 251: повторный выбор файла для автообновления. Здесь сохраняем
    // ПРАВО ЗАПИСИ: без него воркер не сможет перезаписывать файл по расписанию.
    val autoAttachLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                activityContext.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            var name = "APU backup"
            runCatching {
                activityContext.contentResolver.query(
                    uri,
                    arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                    null, null, null,
                )?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: name }
            }
            autoAttachTarget = FoundBackup(uri, name, 0L)
            autoAttachPassword = ""
        }
    }
    val closeApp: () -> Unit = {
        var ctx: android.content.Context = activityContext
        while (ctx is android.content.ContextWrapper && ctx !is android.app.Activity) ctx = ctx.baseContext
        (ctx as? android.app.Activity)?.finishAndRemoveTask()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .swipeBack(onBack = onBackClick),
    ) {
        ChatWallpaper()
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                    ),
                    title = { ApuSettingsHeader(stringResource(R.string.backup_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                )
            },
        ) { padding ->
            // imePadding: когда открывается клавиатура (поля паролей), список
            // сжимается над ней, а прокрутка подводит фокусное поле в зону
            // видимости - раньше клавиатура закрывала ввод пароля.
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    HintBubble {
                        Column {
                            Text(
                                stringResource(R.string.backup_what),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = HintBubbleTextColor,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.backup_what_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = HintBubbleMutedColor,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.backup_password_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = HintBubbleMutedColor,
                            )
                        }
                    }
                }

                // Подготовленная копия ждёт перезапуска - это главное, показываем первым.
                state.stagedManifest?.let { manifest ->
                    item {
                        ApuSettingsCard(
                            highlighted = true,
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    stringResource(R.string.backup_ready),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.height(4.dp))
                                val made = SimpleDateFormat("d MMMM yyyy, HH:mm", Locale.forLanguageTag("ru"))
                                    .format(Date(manifest.createdAtMs))
                                Text(
                                    "Профиль: ${manifest.displayName}" +
                                        (if (manifest.nickname.isNotBlank()) " (@${manifest.nickname})" else "") +
                                        "\nСделана: $made, версия ${manifest.appVersionName}" +
                                        (if (manifest.includesReceived) "\nС полученными файлами: ${manifest.receivedFiles}" else ""),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    if (state.hasIdentity) {
                                        "Приложение закроется; откройте его снова - и профиль будет " +
                                            "заменён на этот. Нынешние чаты на этом телефоне пропадут."
                                    } else {
                                        "Приложение закроется; откройте его снова - и вы войдёте " +
                                            "в восстановленный профиль."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(12.dp))
                                if (state.restarting) {
                                    Text(
                                        "Закрываемся… Откройте APU снова.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                } else {
                                    Row {
                                        ApuPremiumContentButton(
                                            onClick = { viewModel.confirmAndExit(onExit = closeApp) },
                                            style = DiagnosticsActionStyle.PRIMARY,
                                            enabled = !state.busy,
                                            modifier = Modifier.weight(1f),
                                        ) { Text(stringResource(R.string.backup_restore_close)) }
                                        Spacer(Modifier.width(8.dp))
                                        ApuTextAction(
                                            label = stringResource(R.string.action_cancel),
                                            onClick = viewModel::discardStaged,
                                            enabled = !state.busy,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (state.hasIdentity) item {
                    ApuSettingsCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.backup_make),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.backup_password_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            ApuBubbleField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text(stringResource(R.string.backup_file_password)) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                isError = password.isNotEmpty() && password.length < BackupCipher.MIN_PASSWORD_LENGTH,
                                supportingText = {
                                    Text(
                                        if (password.isNotEmpty() && password.length < BackupCipher.MIN_PASSWORD_LENGTH) {
                                            "Ещё ${BackupCipher.MIN_PASSWORD_LENGTH - password.length} знак(ов)"
                                        } else {
                                            "Минимум ${BackupCipher.MIN_PASSWORD_LENGTH} знаков"
                                        },
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            ApuBubbleField(
                                value = repeat,
                                onValueChange = { repeat = it },
                                label = { Text(stringResource(R.string.backup_repeat_password)) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                isError = repeat.isNotEmpty() && repeat != password,
                                supportingText = {
                                    if (repeat.isNotEmpty() && repeat != password) {
                                        Text(stringResource(R.string.backup_passwords_mismatch), color = MaterialTheme.colorScheme.error)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.backup_attach_received), style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        if (state.receivedBytes > 0) {
                                            "Сейчас это ${ProfileBackupViewModel.humanBytes(state.receivedBytes)}; без них копия меньше"
                                        } else {
                                            "Полученных файлов пока нет"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                ApuPremiumSwitch(checked = includeReceived, onCheckedChange = { includeReceived = it })
                            }
                            Spacer(Modifier.height(12.dp))
                            val canCreate = !state.busy &&
                                password.length >= BackupCipher.MIN_PASSWORD_LENGTH &&
                                password == repeat
                            ApuPremiumContentButton(
                                onClick = { createLauncher.launch(viewModel.suggestedFileName()) },
                                style = DiagnosticsActionStyle.PRIMARY,
                                enabled = canCreate,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (state.busy && state.busyText.startsWith("Собираем")) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(state.busyText)
                                } else {
                                    Text(stringResource(R.string.backup_save_file))
                                }
                            }
                            val blocker = when {
                                password.length < BackupCipher.MIN_PASSWORD_LENGTH -> "Пароль минимум ${BackupCipher.MIN_PASSWORD_LENGTH} знаков"
                                password != repeat -> "Повторите пароль без ошибок"
                                else -> null
                            }
                            if (blocker != null) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    blocker,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // Автообновление: показываем, когда есть что обновлять - только что
                // сохранённый файл или уже включённое расписание.
                val schedule = state.schedule
                val scheduleError = schedule?.lastError
                if (state.hasIdentity && (state.lastSaved != null || (schedule != null && schedule.enabled) || scheduleError != null)) item {
                    ApuSettingsCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.backup_auto_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(4.dp))
                            if (schedule != null && schedule.enabled) {
                                val fmt = SimpleDateFormat("d MMMM, HH:mm", Locale.forLanguageTag("ru"))
                                Text(
                                    "Файл: ${schedule.targetName}\n" +
                                        "Период: ${schedule.period.title}" +
                                        (if (schedule.includeReceived) ", с полученными файлами" else ", без полученных файлов") +
                                        (if (schedule.lastOkAtMs > 0) {
                                            "\nПоследнее обновление: ${fmt.format(Date(schedule.lastOkAtMs))} " +
                                                "(${ProfileBackupViewModel.humanBytes(schedule.lastOkBytes)})"
                                        } else {
                                            "\nПервое обновление - примерно через ${schedule.period.days} дн. после включения"
                                        }) +
                                        (if (schedule.nextDueAtMs > 0) "\nСледующее: около ${fmt.format(Date(schedule.nextDueAtMs))}" else "") +
                                        (scheduleError?.let { "\nПоследняя попытка не удалась: $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (scheduleError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(10.dp))
                                PeriodChooser(
                                    selected = schedule.period,
                                    enabled = !state.busy,
                                    onSelect = viewModel::setAutoPeriod,
                                )
                                Spacer(Modifier.height(10.dp))
                                Row {
                                    ApuPremiumContentButton(
                                        onClick = viewModel::runAutoNow,
                                        style = DiagnosticsActionStyle.QUIET,
                                        enabled = !state.busy,
                                        modifier = Modifier.weight(1f),
                                    ) { Text(stringResource(R.string.backup_update_now)) }
                                    Spacer(Modifier.width(8.dp))
                                    ApuTextAction(
                                        label = stringResource(R.string.backup_turn_off),
                                        onClick = viewModel::disableAutoUpdate,
                                        enabled = !state.busy,
                                    )
                                }
                            } else {
                                if (scheduleError != null) {
                                    Text(
                                        "Автообновление остановлено: $scheduleError",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                }
                                if (state.lastSaved != null) {
                                    Text(
                                        stringResource(R.string.backup_auto_body),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    PeriodChooser(
                                        selected = autoPeriod,
                                        enabled = !state.busy,
                                        onSelect = { autoPeriod = it },
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    ApuPremiumContentButton(
                                        onClick = { viewModel.enableAutoUpdate(password, includeReceived, autoPeriod) },
                                        style = DiagnosticsActionStyle.PRIMARY,
                                        enabled = !state.busy && password.length >= BackupCipher.MIN_PASSWORD_LENGTH,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text("Включить: ${autoPeriod.title}") }
                                    if (password.length < BackupCipher.MIN_PASSWORD_LENGTH) {
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            "Пароль в поле выше стёрт — введите его снова, он нужен для обновлений",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                } else {
                                    // Раунд 251: расписание остановлено (например,
                                    // после восстановления профиля), а файл уже
                                    // есть на телефоне - включаем заново без
                                    // нового сохранения: нашли/выбрали файл,
                                    // ввели его пароль.
                                    Text(
                                        "Сохраните копию в файл — или включите обновление заново на уже " +                                            "готовом файле: найденном ниже либо выбранном в проводнике. " +                                            "Понадобится пароль этого файла.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    PeriodChooser(
                                        selected = autoPeriod,
                                        enabled = !state.busy,
                                        onSelect = { autoPeriod = it },
                                    )
                                    if (state.foundBackups.isNotEmpty()) {
                                        Spacer(Modifier.height(10.dp))
                                        Text(
                                            stringResource(R.string.backup_found_here),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        state.foundBackups.forEach { fb ->
                                            ApuPremiumContentButton(
                                                onClick = {
                                                    autoAttachTarget = fb
                                                    autoAttachPassword = ""
                                                },
                                                style = DiagnosticsActionStyle.QUIET,
                                                enabled = !state.busy,
                                                modifier = Modifier.fillMaxWidth(),
                                            ) {
                                                Text("Обновлять: " + fb.name, maxLines = 1)
                                            }
                                            Spacer(Modifier.height(6.dp))
                                        }
                                    }
                                    ApuPremiumContentButton(
                                        onClick = { autoAttachLauncher.launch(arrayOf("*/*")) },
                                        style = DiagnosticsActionStyle.QUIET,
                                        enabled = !state.busy,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text(stringResource(R.string.backup_pick_other)) }
                                }
                            }
                        }
                    }
                }

                item {
                    ApuSettingsCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.backup_restore_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.backup_restore_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            ApuBubbleField(
                                value = restorePassword,
                                onValueChange = { restorePassword = it },
                                label = { Text(stringResource(R.string.backup_file_password)) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            // Раунд 250: копии, найденные на телефоне сами
                            // (сохранённые разрешения SAF + файл автообновления).
                            // Предлагаем их первыми: пароль уже введён - тап по
                            // найденному файлу сразу готовит восстановление.
                            if (state.foundBackups.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    stringResource(R.string.backup_found_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(6.dp))
                                state.foundBackups.forEach { fb ->
                                    ApuPremiumContentButton(
                                        onClick = { viewModel.stage(fb.uri, restorePassword) },
                                        style = DiagnosticsActionStyle.QUIET,
                                        enabled = !state.busy && restorePassword.isNotEmpty(),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            fb.name + " · " + ProfileBackupViewModel.humanBytes(fb.size),
                                            maxLines = 1,
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            ApuPremiumContentButton(
                                onClick = { openLauncher.launch(arrayOf("*/*")) },
                                style = DiagnosticsActionStyle.QUIET,
                                enabled = !state.busy && restorePassword.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (state.busy && state.busyText.startsWith("Открываем")) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(state.busyText)
                                } else {
                                    Text(stringResource(R.string.backup_pick_file))
                                }
                            }
                        }
                    }
                }

                state.message?.let { message ->
                    item {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.failed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }

    // Раунд 251: пароль файла, на котором включаем автообновление заново.
    autoAttachTarget?.let { target ->
        ApuSettingsDialog(
            onDismissRequest = { autoAttachTarget = null },
            title = { ApuSettingsHeader(stringResource(R.string.backup_auto_file_title)) },
            text = {
                Column {
                    Text(target.name, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Телефон будет перезаписывать этот файл по расписанию. " +                            "Нужен пароль именно этого файла.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    ApuBubbleField(
                        value = autoAttachPassword,
                        onValueChange = { autoAttachPassword = it },
                        label = { Text(stringResource(R.string.backup_file_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                ApuTextAction(
                    label = stringResource(R.string.backup_enable),
                    onClick = {
                        val t = target
                        autoAttachTarget = null
                        viewModel.enableAutoUpdateFor(t.uri, autoAttachPassword, includeReceived, autoPeriod)
                    },
                    enabled = autoAttachPassword.length >= BackupCipher.MIN_PASSWORD_LENGTH && !state.busy,
                )
            },
            dismissButton = {
                ApuTextAction(label = stringResource(R.string.action_cancel), onClick = { autoAttachTarget = null })
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodChooser(
    selected: BackupSchedule.Period,
    enabled: Boolean,
    onSelect: (BackupSchedule.Period) -> Unit,
) {
    val periods = BackupSchedule.Period.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        periods.forEachIndexed { index, period ->
            SegmentedButton(
                selected = selected == period,
                onClick = { onSelect(period) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, periods.size),
                label = { Text(period.short) },
            )
        }
    }
}
