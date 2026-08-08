package com.vladimir.messenger.ui.screens.mtproxy

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
import java.text.SimpleDateFormat
import java.util.*

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

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("MTProto прокси", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        showImportDialog = true
                    }) {
                        Icon(Icons.Default.ContentPaste, "Импорт прокси")
                    }
                    IconButton(onClick = { viewModel.collectNow() }, enabled = !uiState.isCollecting) {
                        Icon(Icons.Default.Download, "Собрать прокси из каналов")
                    }
                    IconButton(onClick = { viewModel.checkAllAndPickBest() }, enabled = !uiState.isChecking) {
                        Icon(Icons.Default.Refresh, "Проверить все")
                    }
                    IconButton(onClick = { viewModel.cleanupDead() }) {
                        Icon(Icons.Default.CleaningServices, "Очистить мёртвые")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, "Добавить прокси")
            }
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Нет прокси",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Добавьте вручную или импортируйте из буфера",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
        proxy.lastCheck == 0L -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.tertiary
    }

    val statusText = when {
        proxy.isActive -> "● Активный"
        proxy.failCount >= 3 -> "✗ Нерабочий (${proxy.failCount} fail)"
        proxy.lastCheck == 0L -> "? Не проверен"
        else -> "✓ Рабочий (${proxy.successCount})"
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSetActive() }
                .padding(16.dp),
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
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor
                )
                Text(
                    "Источник: ${proxy.source} | ${formatDate(proxy.addedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onCheck) {
                Icon(Icons.Default.Refresh, contentDescription = "Проверить")
            }

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Удалить",
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить прокси") },
        text = {
            Column {
                Text(
                    "Поддерживаемые форматы:",
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
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Прокси") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 3
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(input) },
                enabled = input.isNotBlank()
            ) {
                Text("Добавить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}



@Composable
private fun ImportProxyDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Импорт прокси") },
        text = {
            Column {
                Text(
                    "Вставьте прокси (один или несколько, каждый на новой строке):",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Поддерживаемые форматы:",
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
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Прокси") },
                    placeholder = { Text("Вставьте сюда...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 150.dp),
                    singleLine = false,
                    maxLines = 10
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(input) },
                enabled = input.isNotBlank()
            ) {
                Text("Импортировать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

private fun formatDate(timestamp: Long): String {
    if (timestamp == 0L) return "—"
    return SimpleDateFormat("dd.MM.yy HH:mm", Locale.getDefault()).format(Date(timestamp))
}
