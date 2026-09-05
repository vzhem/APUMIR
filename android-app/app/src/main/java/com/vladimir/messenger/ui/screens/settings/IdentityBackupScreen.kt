package com.vladimir.messenger.ui.screens.settings

// =============================================================================
// IDENTITYBACKUPSCREEN.KT — «Защита личности»
// =============================================================================
// Здесь человек задаёт никнейм и пароль, которыми потом вернёт себя после
// переустановки приложения. Раньше переустановка делала его новым: другой
// адрес, потерянный ранг, оборванная переписка, а для собеседников — незнакомец.
//
// Пароль наружу не уходит: ключ запирается прямо на телефоне, а на сервере
// лежат непрозрачные байты, которые никто, включая владельца сервера, прочитать
// не может.
// =============================================================================

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.ui.components.ChatWallpaper
import com.vladimir.messenger.ui.components.HintBubble
import com.vladimir.messenger.ui.components.HintBubbleMutedColor
import com.vladimir.messenger.ui.components.HintBubbleTextColor
import com.vladimir.messenger.ui.components.swipeBack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentityBackupScreen(
    onBackClick: () -> Unit,
    viewModel: IdentityBackupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var nickname by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }

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
                    title = { Text("Защита личности") },
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
                                "Зачем это нужно",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = HintBubbleTextColor,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Если удалить и поставить приложение заново, вы станете для " +
                                    "собеседников новым человеком: пропадут ранг, приглашения и " +
                                    "переписка. Задайте никнейм и пароль — и сможете вернуть себя " +
                                    "на любом телефоне.",
                                style = MaterialTheme.typography.bodySmall,
                                color = HintBubbleMutedColor,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Пароль никуда не отправляется. Ключ запирается прямо здесь, " +
                                    "на телефоне, поэтому прочитать его не может никто.",
                                style = MaterialTheme.typography.bodySmall,
                                color = HintBubbleMutedColor,
                            )
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
                                if (state.protectedNickname != null) "Личность защищена" else "Личность не защищена",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                state.protectedNickname?.let { "Никнейм для восстановления: @$it" }
                                    ?: "Переустановка приложения сотрёт вас безвозвратно",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
                                if (state.protectedNickname != null) "Сменить пароль" else "Задать пароль",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Менять можно сколько угодно — адрес и переписка останутся прежними.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = nickname,
                                onValueChange = { nickname = it },
                                label = { Text("Никнейм") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("Пароль") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = repeat,
                                onValueChange = { repeat = it },
                                label = { Text("Пароль ещё раз") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                // Повтор обязателен: опечатку в пароле человек
                                // обнаружил бы только при восстановлении, когда
                                // исправить уже нечем.
                                isError = repeat.isNotEmpty() && repeat != password,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { viewModel.save(nickname, password) },
                                enabled = !state.busy &&
                                    nickname.isNotBlank() &&
                                    password.length >= MIN_PASSWORD &&
                                    password == repeat,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (state.busy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.height(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Text("Сохранить")
                                }
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
                                "Вернуть свою личность",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Введите никнейм и пароль, которые задавали раньше. Приложение " +
                                    "перезапустится под вашим прежним адресом.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { viewModel.restore(nickname, password) },
                                enabled = !state.busy && nickname.isNotBlank() && password.isNotEmpty(),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.outlinedButtonColors(),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Восстановить") }
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

private const val MIN_PASSWORD = 8
