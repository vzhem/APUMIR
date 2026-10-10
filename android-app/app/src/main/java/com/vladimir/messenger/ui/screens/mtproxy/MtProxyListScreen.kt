package com.vladimir.messenger.ui.screens.mtproxy

import com.vladimir.messenger.R
import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.ui.components.ApuAction
import com.vladimir.messenger.ui.components.ApuActionsMenu
import com.vladimir.messenger.ui.components.ApuBubbleField
import com.vladimir.messenger.ui.components.ApuSettingsHeader
import com.vladimir.messenger.ui.components.ApuSettingsDialog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.domain.model.MtProtoProxy
import com.vladimir.messenger.ui.components.ApuBubble
import com.vladimir.messenger.ui.components.ApuBubbleMutedColor
import com.vladimir.messenger.ui.components.ApuTextAction
import com.vladimir.messenger.ui.components.ChatWallpaper
import java.text.SimpleDateFormat
import java.util.*
import com.vladimir.messenger.ui.components.ApuPremiumFloatingActionButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MtProxyListScreen(
    onBackClick: () -> Unit,
    viewModel: MtProxyViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    var showAddDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    // Четыре значка съедали шапку и обрезали «MTProto прокси». Все действия
    // собраны в одно фирменное меню, как на остальных длинных экранах.
    var showActionsMenu by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    // Подложка на весь экран, в том числе под верхней панелью - как в
    // остальных разделах APU.
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
                title = { ApuSettingsHeader(stringResource(R.string.mtproxy_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showActionsMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.mtproxy_actions))
                        }
                        ApuActionsMenu(
                            expanded = showActionsMenu,
                            onDismiss = { showActionsMenu = false },
                            actions = listOf(
                                ApuAction(
                                    title = stringResource(R.string.mtproxy_import),
                                    icon = Icons.Default.ContentPaste,
                                    onClick = { showImportDialog = true },
                                ),
                                ApuAction(
                                    title = stringResource(R.string.mtproxy_collect),
                                    icon = Icons.Default.Download,
                                    enabled = !uiState.isCollecting,
                                    onClick = { viewModel.collectNow() },
                                ),
                                ApuAction(
                                    title = stringResource(R.string.mtproxy_check_all),
                                    icon = Icons.Default.Refresh,
                                    enabled = !uiState.isChecking,
                                    onClick = { viewModel.checkAllAndPickBest() },
                                ),
                                ApuAction(
                                    title = stringResource(R.string.mtproxy_clean_dead),
                                    icon = Icons.Default.CleaningServices,
                                    onClick = { viewModel.cleanupDead() },
                                ),
                            ),
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            ApuPremiumFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = Icons.Default.Add,
                contentDescription = stringResource(R.string.mtproxy_add_cd),
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (uiState.proxies.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                ApuBubble(modifier = Modifier.padding(32.dp)) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Default.CloudOff,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = ApuBubbleMutedColor,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.mtproxy_none),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            stringResource(R.string.mtproxy_none_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = ApuBubbleMutedColor,
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                // Снизу - запас под плавающий «+», чтобы он не закрывал последний прокси.
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Раунд 196: список ограничен 500 лучшими - сообщаем, сколько всего.
                if (uiState.totalInPool > uiState.proxies.size) {
                    item(key = "pool-count") {
                        Text(
                            stringResource(R.string.mtp_shown_tpl, uiState.proxies.size, uiState.totalInPool),
                            style = MaterialTheme.typography.bodySmall,
                            color = ApuBubbleMutedColor,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
                items(uiState.proxies, key = { it.id }) { proxy ->
                    MtProxyCard(
                        proxy = proxy,
                        onSetActive = { viewModel.setActive(proxy.id) },
                        onDelete = { viewModel.delete(proxy.id) },
                        onCheck = { viewModel.checkOne(proxy) }
                    )
                }
            }
        }
    }

    }

    if (showAddDialog) {
        AddProxyDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { input ->
                viewModel.addProxy(input)
                showAddDialog = false
            }
        )
    }

    if (showImportDialog) {
        ImportProxyDialog(
            onDismiss = { showImportDialog = false },
            onConfirm = { text ->
                viewModel.importFromClipboard(text)
                showImportDialog = false
            }
        )
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MtProxyCard(
    proxy: MtProtoProxy,
    onSetActive: () -> Unit,
    onDelete: () -> Unit,
    onCheck: () -> Unit,
) {
    val statusColor = when {
        proxy.isActive -> MaterialTheme.colorScheme.primary
        proxy.failCount >= 3 -> Color.Red.copy(alpha = 0.7f)
        proxy.lastCheck == 0L -> ApuBubbleMutedColor
        else -> MaterialTheme.colorScheme.tertiary
    }

    val statusText = when {
        proxy.isActive -> stringResource(R.string.mtp_active)
        proxy.failCount >= 3 -> stringResource(R.string.mtp_dead_tpl, proxy.failCount)
        proxy.lastCheck == 0L -> stringResource(R.string.mtp_unchecked)
        else -> stringResource(R.string.mtp_ok_tpl, proxy.successCount)
    }

    ApuBubble {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSetActive() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status indicator
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${proxy.host}:${proxy.port}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.mtp_source_tpl, proxy.source, formatDate(proxy.addedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = ApuBubbleMutedColor,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }

            IconButton(onClick = onCheck) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.mtproxy_check))
            }

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun AddProxyDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }

    ApuSettingsDialog(
        onDismissRequest = onDismiss,
        title = { ApuSettingsHeader(stringResource(R.string.mtproxy_add_cd)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.mtproxy_formats),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "• tg://proxy?server=...&port=...&secret=...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "• host:port:secret",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                ApuBubbleField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.mtproxy_proxy)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 3,
                )
            }
        },
        confirmButton = {
            ApuTextAction(
                label = stringResource(R.string.mtproxy_add_btn),
                onClick = { onConfirm(input) },
                enabled = input.isNotBlank(),
            )
        },
        dismissButton = {
            ApuTextAction(label = stringResource(R.string.action_cancel), onClick = onDismiss)
        }
    )
}

@Composable
private fun ImportProxyDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }

    ApuSettingsDialog(
        onDismissRequest = onDismiss,
        title = { ApuSettingsHeader(stringResource(R.string.mtproxy_import)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.mtproxy_paste_hint),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.mtproxy_formats),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "• tg://proxy?server=...&port=...&secret=...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "• socks5://host:port",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "• http://host:port",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "• host:port:secret",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                ApuBubbleField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.mtproxy_proxy)) },
                    placeholder = { Text(stringResource(R.string.mtproxy_paste_placeholder)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 150.dp),
                    singleLine = false,
                    maxLines = 10,
                )
            }
        },
        confirmButton = {
            ApuTextAction(
                label = stringResource(R.string.mtproxy_import_btn),
                onClick = { onConfirm(input) },
                enabled = input.isNotBlank(),
            )
        },
        dismissButton = {
            ApuTextAction(label = stringResource(R.string.action_cancel), onClick = onDismiss)
        }
    )
}

private fun formatDate(timestamp: Long): String {
    if (timestamp == 0L) return "—"
    return SimpleDateFormat("dd.MM.yy HH:mm", Locale.getDefault()).format(Date(timestamp))
}
