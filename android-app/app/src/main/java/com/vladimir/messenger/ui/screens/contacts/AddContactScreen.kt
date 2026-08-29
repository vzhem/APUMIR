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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
                        containerColor = Color.Transparent
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
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Ссылка-приглашение", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Вставьте ссылку, которую вам прислал собеседник, или его отпечаток.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = uiState.inviteLink,
                            onValueChange = viewModel::onInviteLinkChanged,
                            label = { Text("Ссылка или отпечаток") },
                            placeholder = { Text("p2pmessenger://add?... или pk_...") },
                            modifier = Modifier.fillMaxWidth(),
                            supportingText = { uiState.error?.let { Text(it) } },
                        )
                        OutlinedTextField(
                            value = uiState.displayName,
                            onValueChange = viewModel::onDisplayNameChanged,
                            label = { Text("Имя контакта (необязательно)") },
                            placeholder = { Text("Анна, Стас, ...") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = viewModel::onAddContactClicked,
                            enabled = uiState.inviteLink.isNotBlank() && !uiState.isLoading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (uiState.isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.height(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.Default.PersonAdd, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Добавить контакт")
                            }
                        }
                    }
                }

                // ---------------- Поиск по @никнейму ----------------
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Найти по @никнейму", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Поиск по сетевому реестру имён: кого уже видели ваши контакты.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = uiState.nickQuery,
                            onValueChange = viewModel::onNickQueryChanged,
                            label = { Text("никнейм") },
                            placeholder = { Text("evzhem") },
                            prefix = { Text("@") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            singleLine = true,
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
                            HorizontalDivider()
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
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // ---------------- QR-код ----------------
                OutlinedButton(
                    onClick = onScanQrClick,
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
