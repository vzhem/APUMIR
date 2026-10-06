package com.vladimir.messenger.ui.screens.contacts

// =============================================================================
// ADDCONTACTSCREEN.KT
// =============================================================================
// Добавление контакта - в едином стиле APU (подложка на весь экран, русский
// текст, скруглённые карточки). Три способа:
//   1. Вставить ссылку-приглашение или отпечаток.
//   2. Найти по @никнейму в роевом реестре и добавить в один тап.
//   3. Отсканировать QR-код.
// =============================================================================

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.ui.components.ApuBubble
import com.vladimir.messenger.ui.components.ApuBubbleAccentColor
import com.vladimir.messenger.ui.components.ApuBubbleMutedColor
import com.vladimir.messenger.ui.components.ApuBubbleShape
import com.vladimir.messenger.ui.components.ApuBubbleSurfaceColor
import com.vladimir.messenger.ui.components.ApuFormTextField
import com.vladimir.messenger.ui.components.ApuSearchField
import com.vladimir.messenger.ui.components.ChatWallpaper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(
    initialInviteLink: String?,
    onContactAdded: () -> Unit,
    onBackClick: () -> Unit,
    onScanQrClick: () -> Unit = {},
    viewModel: AddContactViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var autoSubmitDone by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(initialInviteLink) {
        if (!initialInviteLink.isNullOrBlank() && !autoSubmitDone) {
            viewModel.onInviteLinkChanged(initialInviteLink)
            viewModel.onAddContactClicked()
            autoSubmitDone = true
        }
    }

    LaunchedEffect(uiState.contactAdded) {
        if (uiState.contactAdded) {
            onContactAdded()
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Подложка на весь экран, в том числе под верхней панелью.
    Box(modifier = Modifier.fillMaxSize()) {
        ChatWallpaper()
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    // Прокрутка НЕ должна красить панель: под ней обои APU.
                    scrolledContainerColor = Color.Transparent,
                    ),
                    title = { Text("Добавить контакт", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    },
                )
            },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ---------------- Ссылка-приглашение ----------------
                // Все поверхности на экране - фирменные пузыри поверх обоев:
                // не серые Material-карточки, а та же светлая поверхность с
                // золотой рамкой, что у списка контактов и чатов.
                ApuBubble(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Ссылка-приглашение", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Вставьте ссылку, которую вам прислал собеседник.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ApuBubbleMutedColor,
                    )
                    ApuFormTextField(
                        value = uiState.inviteLink,
                        onValueChange = viewModel::onInviteLinkChanged,
                        label = "Ссылка",
                        placeholder = "Вставьте ссылку или ключ",
                        isError = uiState.error != null,
                        supportingText = uiState.error,
                    )
                    ApuFormTextField(
                        value = uiState.displayName,
                        onValueChange = viewModel::onDisplayNameChanged,
                        label = "Имя контакта (необязательно)",
                        placeholder = "Например, Анна",
                    )
                    Button(
                        onClick = viewModel::onAddContactClicked,
                        enabled = uiState.inviteLink.isNotBlank() && !uiState.isLoading,
                        shape = ApuBubbleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContainerColor = ApuBubbleMutedColor.copy(alpha = 0.22f),
                            disabledContentColor = ApuBubbleMutedColor.copy(alpha = 0.68f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Default.PersonAdd, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Добавить контакт")
                        }
                    }
                }

                // ---------------- Поиск по @никнейму ----------------
                ApuBubble(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Найти по @никнейму", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Поиск по сетевому реестру имён: кого уже видели ваши контакты.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ApuBubbleMutedColor,
                    )
                    ApuSearchField(
                        value = uiState.nickQuery,
                        onValueChange = viewModel::onNickQueryChanged,
                        placeholder = "@никнейм",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (uiState.nickSearching) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Ищем...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    uiState.nickResults.forEach { entry ->
                        HorizontalDivider(color = ApuBubbleMutedColor.copy(alpha = 0.22f))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "@${entry.name}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    entry.ownerId.takeLast(8),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ApuBubbleMutedColor,
                                )
                            }
                            TextButton(
                                onClick = { viewModel.onAddByNicknameClicked(entry) },
                                enabled = !uiState.isLoading,
                            ) {
                                Text("Добавить")
                            }
                        }
                    }
                    if (!uiState.nickSearching &&
                        uiState.nickQuery.trimStart('@').trim().isNotBlank() &&
                        uiState.nickResults.isEmpty()
                    ) {
                        Text(
                            "Никого не нашли. Ищем только тех, чьё имя уже встречалось в сети.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ApuBubbleMutedColor,
                        )
                    }
                }

                // ---------------- QR-код ----------------
                // Это тоже отдельное действие-пузырь, а не прозрачная
                // стандартная OutlineButton, которая терялась на обоях.
                OutlinedButton(
                    onClick = onScanQrClick,
                    shape = ApuBubbleShape,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.58f),
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = ApuBubbleSurfaceColor,
                        contentColor = ApuBubbleAccentColor,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Сканировать QR-код")
                }
            }
        }
    }
}
