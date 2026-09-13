package com.vladimir.messenger.ui.screens.settings

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.vladimir.messenger.data.backup.BackupCipher
import com.vladimir.messenger.ui.components.ChatWallpaper
import com.vladimir.messenger.ui.components.HintBubble
import com.vladimir.messenger.ui.components.HintBubbleMutedColor
import com.vladimir.messenger.ui.components.HintBubbleTextColor
import com.vladimir.messenger.ui.components.swipeBack
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    // Диалоги системы: «куда сохранить» и «какой файл открыть». Пароль
    // берём из полей на момент выбора файла.
    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        if (uri != null) viewModel.create(uri, password, includeReceived)
    }
    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.stage(uri, restorePassword)
    }
    // Закрыть задачу целиком: процесс умрёт следом, а при следующем запуске
    // копия ляжет на место. Активность ищем по цепочке контекстов.
    val activityContext = LocalContext.current
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
                    title = { Text("Резервная копия") },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    HintBubble {
                        Column {
                            Text(
                                "Что это",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = HintBubbleTextColor,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Один файл со всем профилем: чаты, контакты, сообщества и каналы, " +
                                    "ключи шифрования, ранг, настройки, аватар. После переустановки или " +
                                    "на новом телефоне вы вернётесь из него таким, каким были — " +
                                    "собеседники ничего не заметят.",
                                style = MaterialTheme.typography.bodySmall,
                                color = HintBubbleMutedColor,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Файл заперт паролем и хранится там, куда вы его положите: в «Файлах» " +
                                    "телефона, на флешке или в облаке. Без пароля он никому не читается. " +
                                    "«Защита личности» возвращает только адрес и имя — это отдельная, " +
                                    "полная копия.",
                                style = MaterialTheme.typography.bodySmall,
                                color = HintBubbleMutedColor,
                            )
                        }
                    }
                }

                // Подготовленная копия ждёт перезапуска - это главное, показываем первым.
                state.stagedManifest?.let { manifest ->
                    item {
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.96f),
                            ),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "Копия готова к восстановлению",
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
                                        Button(
                                            onClick = { viewModel.confirmAndExit(onExit = closeApp) },
                                            enabled = !state.busy,
                                            shape = RoundedCornerShape(14.dp),
                                            modifier = Modifier.weight(1f),
                                        ) { Text("Восстановить и закрыть") }
                                        Spacer(Modifier.width(8.dp))
                                        TextButton(onClick = viewModel::discardStaged, enabled = !state.busy) { Text("Отмена") }
                                    }
                                }
                            }
                        }
                    }
                }

                if (state.hasIdentity) item {
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                        ),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Сделать резервную копию",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Придумайте пароль для файла. Он может отличаться от пароля «Защиты личности».",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("Пароль файла") },
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
                            OutlinedTextField(
                                value = repeat,
                                onValueChange = { repeat = it },
                                label = { Text("Пароль ещё раз") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                isError = repeat.isNotEmpty() && repeat != password,
                                supportingText = {
                                    if (repeat.isNotEmpty() && repeat != password) {
                                        Text("Пароли не совпадают", color = MaterialTheme.colorScheme.error)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Приложить полученные файлы", style = MaterialTheme.typography.bodyMedium)
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
                                Switch(checked = includeReceived, onCheckedChange = { includeReceived = it })
                            }
                            Spacer(Modifier.height(12.dp))
                            val canCreate = !state.busy &&
                                password.length >= BackupCipher.MIN_PASSWORD_LENGTH &&
                                password == repeat
                            Button(
                                onClick = { createLauncher.launch(viewModel.suggestedFileName()) },
                                enabled = canCreate,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (state.busy && state.busyText.startsWith("Собираем")) {
                                    CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(state.busyText)
                                } else {
                                    Text("Сохранить в файл…")
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

                item {
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                        ),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Восстановить из файла",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Введите пароль файла и выберите его. Копия сначала проверится, " +
                                    "и только после вашего подтверждения заменит текущий профиль.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = restorePassword,
                                onValueChange = { restorePassword = it },
                                label = { Text("Пароль файла") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { openLauncher.launch(arrayOf("*/*")) },
                                enabled = !state.busy && restorePassword.isNotEmpty(),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (state.busy && state.busyText.startsWith("Открываем")) {
                                    CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(state.busyText)
                                } else {
                                    Text("Выбрать файл копии…")
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
}
