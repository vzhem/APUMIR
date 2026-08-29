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
import com.vladimir.messenger.data.local.entity.DirectoryEntity
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
import com.vladimir.messenger.ui.components.HintBubble
import com.vladimir.messenger.ui.components.HintBubbleMutedColor
import com.vladimir.messenger.ui.components.HintBubbleTextColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsScreen(
    onGroupClick: (groupId: String) -> Unit,
    onChannelClick: (channelId: String) -> Unit = { onGroupClick(it) },
    onBackClick: () -> Unit,
    /** Ссылка-приглашение из QR или из внешнего открытия: сразу пробуем войти. */
    joinLink: String? = null,
    /** Из меню кнопки-карандаша: "group" или "channel" - сразу открыть создание. */
    create: String? = null,
    viewModel: GroupsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }
    // Из меню кнопки-карандаша сразу открываем диалог создания группы/канала.
    LaunchedEffect(create) {
        if (!create.isNullOrBlank() && uiState.canCreate) showCreate = true
    }
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

                uiState.filtered.isEmpty() && uiState.directoryMatches.isEmpty() ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                    // Раунд 48: пустое состояние в пузыре HintBubble - голый
                    // текст брал цвет из темы и на обоях не читался.
                    HintBubble {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Filled.Groups,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = HintBubbleMutedColor,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Групп пока нет",
                                style = MaterialTheme.typography.bodyLarge,
                                color = HintBubbleTextColor,
                            )
                            Text(
                                "Создайте первую или войдите по ссылке-приглашению",
                                style = MaterialTheme.typography.bodyMedium,
                                color = HintBubbleMutedColor,
                            )
                            Spacer(Modifier.height(12.dp))
                            TextButton(onClick = { showJoin = true }) {
                                Text("Войти по ссылке")
                            }
                        }
                    }
                }

                else -> LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(uiState.filtered, key = { it.id }) { group ->
                        GroupRow(
                            group = group,
                            onClick = {
                                // Канал открывается лентой постов, группа - чатом.
                                if (group.isChannel) onChannelClick(group.id) else onGroupClick(group.id)
                            },
                        )
                        HorizontalDivider()
                    }
                    // Поиск по сетевому каталогу: чужие публичные группы и каналы.
                    if (uiState.directoryMatches.isNotEmpty()) {
                        item {
                            Text(
                                "Найдено в сети",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(uiState.directoryMatches, key = { it.groupId }) { entry ->
                            DirectoryRow(entry = entry) { link -> viewModel.joinByLink(link) }
                        }
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
                        Text("Подключаемся...")
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
            initialIsChannel = create == "channel",
            onDismiss = {
                showCreate = false
                viewModel.dismissCreateError()
            },
            onCreate = { title, about, isPublic, topics, isChannel ->
                viewModel.createGroup(
                    title = title,
                    about = about,
                    isPublic = isPublic,
                    topicsEnabled = topics,
                    onCreated = { groupId ->
                        showCreate = false
                        if (isChannel) onChannelClick(groupId) else onGroupClick(groupId)
                    },
                    isChannel = isChannel,
                )
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
    onCreate: (String, String, Boolean, Boolean, Boolean) -> Unit,
    initialIsChannel: Boolean = false,
) {
    var title by remember { mutableStateOf("") }
    var about by remember { mutableStateOf("") }
    var isPublic by remember { mutableStateOf(false) }
    var topics by remember { mutableStateOf(true) }
    var isChannel by remember { mutableStateOf(initialIsChannel) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isChannel) "Новый канал" else "Новая группа") },
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
                // Канал - это лента постов с комментариями: посты пишут
                // администраторы, обсуждение живёт под постом.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Это канал", fontWeight = FontWeight.Medium)
                        Text(
                            "Посты пишут администраторы, под ними комментарии",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = isChannel, onCheckedChange = { isChannel = it })
                }
                if (!isChannel) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Темы", fontWeight = FontWeight.Medium)
                            Text("Обсуждения внутри группы", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = topics, onCheckedChange = { topics = it })
                    }
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
                onClick = { onCreate(title, about, isPublic, topics, isChannel) },
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

/** Строка найденного в сетевом каталоге: чужая публичная группа или канал. */
@Composable
private fun DirectoryRow(entry: DirectoryEntity, onJoin: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                (if (entry.isChannel) "Канал" else "Группа") +
                    (if (entry.needsApproval) " · по заявке" else " · вход сразу"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = {
            onJoin(
                GroupInviteLinks.build(
                    slug = entry.slug,
                    groupId = entry.groupId,
                    ownerId = entry.ownerId,
                    isChannel = entry.isChannel,
                    requestApproval = entry.needsApproval,
                )
            )
        }) { Text("Вступить") }
    }
    HorizontalDivider()
}
