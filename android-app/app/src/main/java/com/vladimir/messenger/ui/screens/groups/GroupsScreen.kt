package com.vladimir.messenger.ui.screens.groups

// =============================================================================
// GROUPSSCREEN.KT — раздел «Группы»: список групп и создание новой
// =============================================================================

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.data.group.GroupInviteLinks
import com.vladimir.messenger.data.group.GroupSummary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsScreen(
    onGroupClick: (groupId: String) -> Unit,
    onBackClick: () -> Unit,
    /** Ссылка-приглашение из QR или из внешнего открытия: сразу пробуем войти. */
    joinLink: String? = null,
    viewModel: GroupsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }
    var showRankHint by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }

    LaunchedEffect(joinLink) {
        // Проверяем, что это действительно ссылка: в параметр может прилететь
        // шаблон маршрута или посторонний текст - тогда нечего и пытаться.
        if (!joinLink.isNullOrBlank() && GroupInviteLinks.parseTarget(joinLink) != null) {
            viewModel.joinByLink(joinLink)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Группы") },
                navigationIcon = {
                    TextButton(onClick = onBackClick) { Text("Назад") }
                },
                actions = {
                    // Кроме QR: вставить скопированную ссылку и войти.
                    IconButton(onClick = { showJoin = true }) {
                        Icon(Icons.Filled.Link, contentDescription = "Войти по ссылке")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                if (uiState.canCreate) showCreate = true else showRankHint = true
            }) {
                Icon(Icons.Filled.Add, contentDescription = "Создать группу")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = viewModel::onSearchQueryChanged,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                placeholder = { Text("Поиск группы") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
            )

            when {
                uiState.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }

                uiState.filtered.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Groups,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Групп пока нет", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Создайте первую или войдите по ссылке-приглашению",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = { showJoin = true }) {
                            Text("Войти по ссылке")
                        }
                    }
                }

                else -> LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(uiState.filtered, key = { it.id }) { group ->
                        GroupRow(group = group, onClick = { onGroupClick(group.id) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    val joinMessage = uiState.joinMessage
    if (uiState.joining || joinMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.consumeJoinResult() },
            title = { Text("Вход по ссылке") },
            text = {
                if (uiState.joining) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Подключаемся к группе...")
                    }
                } else {
                    Text(joinMessage.orEmpty())
                }
            },
            confirmButton = {
                val target = uiState.joinedGroupId
                TextButton(
                    enabled = !uiState.joining,
                    onClick = {
                        viewModel.consumeJoinResult()
                        if (target != null) onGroupClick(target)
                    },
                ) { Text(if (target != null) "Открыть чат" else "Готово") }
            },
        )
    }

    if (showJoin) {
        JoinByLinkDialog(
            onDismiss = { showJoin = false },
            onSubmit = { link ->
                showJoin = false
                viewModel.joinByLink(link)
            },
        )
    }

    if (showCreate) {
        CreateGroupDialog(
            creating = uiState.creating,
            error = uiState.createError,
            onDismiss = {
                showCreate = false
                viewModel.dismissCreateError()
            },
            onCreate = { title, about, isPublic, topics ->
                viewModel.createGroup(title, about, isPublic, topics) { groupId ->
                    showCreate = false
                    onGroupClick(groupId)
                }
            },
        )
    }

    if (showRankHint) {
        AlertDialog(
            onDismissRequest = { showRankHint = false },
            title = { Text("Создание групп недоступно") },
            text = {
                Text(
                    "Создавать группы можно с ранга «Проводник» — это 10 квалифицированных " +
                        "приглашённых. Вступать в группы по ссылке можно уже сейчас."
                )
            },
            confirmButton = { TextButton(onClick = { showRankHint = false }) { Text("Понятно") } },
        )
    }
}

@Composable
private fun GroupRow(group: GroupSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (group.isPublic) Icons.Filled.Public else Icons.Filled.Lock,
                contentDescription = if (group.isPublic) "Публичная" else "Частная",
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(group.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append(group.memberCount)
                        append(" участн.")
                        if (group.topicsEnabled) append(" • темы")
                        group.lastMessagePreview?.let { append(" • ").append(it) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (group.pendingRequests > 0) {
                Badge { Text(group.pendingRequests.toString()) }
                Spacer(Modifier.width(8.dp))
            }
            if (group.unreadCount > 0) {
                Badge { Text(group.unreadCount.toString()) }
            }
        }
    }
}

@Composable
private fun CreateGroupDialog(
    creating: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onCreate: (String, String, Boolean, Boolean) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var about by remember { mutableStateOf("") }
    var isPublic by remember { mutableStateOf(false) }
    var topics by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая группа") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Название") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = about,
                    onValueChange = { about = it },
                    label = { Text("Описание") },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Публичная группа", fontWeight = FontWeight.Medium)
                        Text(
                            "Вход по ссылке без одобрения",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = isPublic, onCheckedChange = { isPublic = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Темы", fontWeight = FontWeight.Medium)
                        Text("Обсуждения внутри группы", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = topics, onCheckedChange = { topics = it })
                }
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
                if (creating) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && !creating,
                onClick = { onCreate(title, about, isPublic, topics) },
            ) { Text("Создать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/**
 * Вход в группу по вставленной ссылке-приглашению. Разбор строгий: если строка
 * не похожа на приглашение, показываем ошибку, а не молча закрываемся.
 */
@Composable
private fun JoinByLinkDialog(
    onDismiss: () -> Unit,
    onSubmit: (link: String) -> Unit,
) {
    var link by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Войти по ссылке") },
        text = {
            Column {
                OutlinedTextField(
                    value = link,
                    onValueChange = {
                        link = it
                        error = null
                    },
                    label = { Text("Ссылка-приглашение") },
                    placeholder = { Text("p2pmessenger://group?slug=...") },
                    // Ссылка длинная (в ней адрес владельца) — даём ей переноситься.
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Проверяем, что это приглашение в группу, но отдаём ВСЮ
                    // ссылку: в ней id группы и адрес владельца, без них войти
                    // с другого телефона нельзя.
                    if (GroupInviteLinks.parseTarget(link) == null) {
                        error = "Не похоже на ссылку-приглашение в группу"
                    } else {
                        onSubmit(link.trim())
                    }
                },
            ) { Text("Войти") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
