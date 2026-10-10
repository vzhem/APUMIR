package com.vladimir.messenger.ui.screens.settings

import com.vladimir.messenger.R
import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.ui.components.ApuBubbleField
import com.vladimir.messenger.ui.components.ApuSettingsCard
import com.vladimir.messenger.ui.components.ApuSettingsHeader

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
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
import com.vladimir.messenger.ui.components.ApuPremiumContentButton
import com.vladimir.messenger.ui.components.DiagnosticsActionStyle

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
                    title = { ApuSettingsHeader(stringResource(R.string.identity_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                // Раунд 250: клавиатура сжимает список, а не закрывает поля паролей.
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
                                stringResource(R.string.identity_why),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = HintBubbleTextColor,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.identity_why_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = HintBubbleMutedColor,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.identity_key_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = HintBubbleMutedColor,
                            )
                        }
                    }
                }

                item {
                    ApuSettingsCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                if (state.protectedNickname != null) stringResource(R.string.identity_protected) else stringResource(R.string.identity_unprotected),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                state.protectedNickname?.let { stringResource(R.string.identity_nick_hint, it) }
                                    ?: stringResource(R.string.identity_reinstall_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                item {
                    ApuSettingsCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                if (state.protectedNickname != null) stringResource(R.string.identity_change_password) else stringResource(R.string.identity_set_password),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.identity_change_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            ApuBubbleField(
                                value = nickname,
                                onValueChange = { nickname = it },
                                label = { Text(stringResource(R.string.identity_nickname)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            ApuBubbleField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text(stringResource(R.string.identity_password)) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                isError = password.isNotEmpty() && password.length < MIN_PASSWORD,
                                supportingText = {
                                    Text(
                                        if (password.isNotEmpty() && password.length < MIN_PASSWORD) {
                                            stringResource(R.string.identity_chars_left, MIN_PASSWORD - password.length)
                                        } else {
                                            stringResource(R.string.identity_min_chars, MIN_PASSWORD)
                                        },
                                        color = if (password.isNotEmpty() && password.length < MIN_PASSWORD) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            ApuBubbleField(
                                value = repeat,
                                onValueChange = { repeat = it },
                                label = { Text(stringResource(R.string.identity_repeat_password)) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                isError = repeat.isNotEmpty() && repeat != password,
                                supportingText = {
                                    if (repeat.isNotEmpty() && repeat != password) {
                                        Text(
                                            stringResource(R.string.identity_passwords_mismatch),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(12.dp))
                            ApuPremiumContentButton(
                                onClick = { viewModel.save(nickname, password) },
                                style = DiagnosticsActionStyle.PRIMARY,
                                enabled = !state.busy &&
                                    nickname.isNotBlank() &&
                                    password.length >= MIN_PASSWORD &&
                                    password == repeat,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (state.busy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.height(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Text(stringResource(R.string.admin_save))
                                }
                            }
                            // Явно называем недостающее: тёмная кнопка без
                            // объяснения выглядит как поломка приложения.
                            val blocker = when {
                                nickname.isBlank() -> stringResource(R.string.identity_enter_nickname)
                                password.length < MIN_PASSWORD -> stringResource(R.string.identity_password_min, MIN_PASSWORD)
                                password != repeat -> stringResource(R.string.identity_repeat_error)
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
                    ApuSettingsCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.identity_restore_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.identity_restore_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            ApuPremiumContentButton(
                                onClick = { viewModel.restore(nickname, password) },
                                style = DiagnosticsActionStyle.QUIET,
                                enabled = !state.busy && nickname.isNotBlank() && password.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.identity_restore)) }
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
