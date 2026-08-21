package com.vladimir.messenger.ui.screens.chat

// =============================================================================
// CHATLISTSCREEN.KT — Главный экран со списком чатов
// =============================================================================
// Аналог главного экрана Telegram.
// Показывает список чатов, строку поиска, статус сети.
// FAB для добавления нового контакта.
// =============================================================================

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.ui.components.ContactCard
import com.vladimir.messenger.ui.components.NetworkStatusBar
import com.vladimir.messenger.data.RustBridge
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalContext
import android.content.Intent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    onChatClick: (chatId: String, contactName: String, contactId: String) -> Unit,
    onAddContactClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onScanQrClick: () -> Unit = {},
    onShowMyQrClick: () -> Unit = {},
    onRankClick: () -> Unit = {},
    viewModel: ChatListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var isSearchVisible by remember { mutableStateOf(false) }
    var showInviteDialog by remember { mutableStateOf(false) }
    var showConnectDialog by remember { mutableStateOf(false) }
    var inviteLink by remember { mutableStateOf("") }
    var connectLink by remember { mutableStateOf("") }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                // Полоска статуса сети (появляется только при проблемах)
                NetworkStatusBar(status = uiState.networkStatus)

                TopAppBar(
                    title = {
                        if (isSearchVisible) {
                            // Поиск по чатам
                            SearchTextField(
                                query     = uiState.searchQuery,
                                onQueryChanged = viewModel::onSearchQueryChanged,
                                onClose   = {
                                    isSearchVisible = false
                                    viewModel.onSearchQueryChanged("")
                                },
                            )
                        } else {
                            // Заголовок + компактный бейдж ранга одной колонкой: AssistChip
                            // (рамка/отступы/минимальная высота) не влезал в высоту TopAppBar
                            // и сжимал заголовок до обрезанной последней буквы.
                            Column(modifier = Modifier.clickable(onClick = onRankClick)) {
                                Text(
                                    "Сообщения",
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (uiState.rankBadge.isNotBlank()) {
                                    // Бейдж ранга на самом видном месте: под заголовком,
                                    // всегда перед глазами; тап — что открыто и как расти дальше.
                                    Text(
                                        uiState.rankBadge,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        // Разгрузка панели: частые действия остаются иконками (поиск, скан QR),
                        // остальное уходит в меню «⋮». Заголовок + бейдж ранга помещаются целиком.
                        if (!isSearchVisible) {
                            IconButton(onClick = { isSearchVisible = true }) {
                                Icon(Icons.Default.Search, "Поиск")
                            }
                            IconButton(onClick = onScanQrClick) {
                                Icon(Icons.Default.QrCodeScanner, "Сканировать QR")
                            }
                            Box {
                                var menuOpen by remember { mutableStateOf(false) }
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Default.MoreVert, "Ещё")
                                }
                                DropdownMenu(
                                    expanded = menuOpen,
                                    onDismissRequest = { menuOpen = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Мой QR-код") },
                                        onClick = {
                                            menuOpen = false
                                            onShowMyQrClick()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Поделиться приглашением") },
                                        onClick = {
                                            menuOpen = false
                                            inviteLink = RustBridge.generateInvite()
                                            showInviteDialog = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Подключиться по ссылке") },
                                        onClick = {
                                            menuOpen = false
                                            showConnectDialog = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Настройки") },
                                        onClick = {
                                            menuOpen = false
                                            onSettingsClick()
                                        },
                                    )
                                }
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        floatingActionButton = {
            // FAB: добавить контакт / новый чат
            FloatingActionButton(
                onClick = onAddContactClick,
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Новый чат",
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            when {
                // Загрузка
                uiState.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                // Список пуст
                uiState.filteredChats.isEmpty() -> {
                    EmptyChatList(
                        isSearchActive = uiState.searchQuery.isNotEmpty(),
                        onAddContact   = onAddContactClick,
                        modifier       = Modifier.align(Alignment.Center),
                    )
                }

                // Список чатов
                else -> {
                    LazyColumn(
                        modifier       = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(
                            items = uiState.filteredChats,
                            key   = { it.id },  // key для эффективных обновлений
                        ) { chat ->
                            ContactCard(
                                chat    = chat,
                                onClick = { onChatClick(chat.id, chat.contactName, chat.contactId) },
                            )
                            // Разделитель между чатами
                            HorizontalDivider(
                                modifier  = Modifier.padding(start = 82.dp),
                                thickness = 0.5.dp,
                                color     = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    // Invite dialog
    if (showInviteDialog) {
        val clipboardManager = LocalClipboardManager.current
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { showInviteDialog = false },
            title = { Text("ой адрес для подключения") },
            text = {
                Column {
                    Text("тправьте эту ссылку собеседнику:", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(inviteLink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            clipboardManager.setText(AnnotatedString(inviteLink))
                        }) { Text("опировать") }
                        TextButton(onClick = {
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, inviteLink)
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "оделиться"))
                        }) { Text("оделиться") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInviteDialog = false }) { Text("OK") }
            },
        )
    }

    // Connect dialog
    if (showConnectDialog) {
        AlertDialog(
            onDismissRequest = { showConnectDialog = false },
            title = { Text("одключиться по ссылке") },
            text = {
                OutlinedTextField(
                    value = connectLink,
                    onValueChange = { connectLink = it },
                    label = { Text("ставьте ссылку p2pm://...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (connectLink.isNotBlank()) {
                        RustBridge.connectViaInvite(connectLink)
                        connectLink = ""
                        showConnectDialog = false
                    }
                }) { Text("одключиться") }
            },
            dismissButton = {
                TextButton(onClick = { showConnectDialog = false }) { Text("тмена") }
            },
        )
    }
}

// Поле поиска в TopAppBar
@Composable
private fun SearchTextField(
    query: String,
    onQueryChanged: (String) -> Unit,
    onClose: () -> Unit,
) {
    TextField(
        value       = query,
        onValueChange = onQueryChanged,
        placeholder = { Text("Поиск по чатам...") },
        singleLine  = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor   = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedIndicatorColor   = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
        trailingIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, "Закрыть поиск")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

// Заглушка для пустого списка
@Composable
private fun EmptyChatList(
    isSearchActive: Boolean,
    onAddContact: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier            = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (isSearchActive) {
            Icon(
                Icons.Default.SearchOff,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint     = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Ничего не найдено",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Icon(
                Icons.Default.Forum,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint     = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                "Нет чатов",
                style     = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Добавьте контакт через QR-код или пригласительную ссылку",
                style     = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color     = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onAddContact) {
                Icon(Icons.Default.PersonAdd, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Добавить контакт")
            }
        }
    }

}