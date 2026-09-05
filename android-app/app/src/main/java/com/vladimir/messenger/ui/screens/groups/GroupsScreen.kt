package com.vladimir.messenger.ui.screens.groups

// =============================================================================
// GROUPSSCREEN.KT — раздел «Группы»: список групп и создание новой
// =============================================================================

import com.vladimir.messenger.ui.components.InviteShareCard
import com.vladimir.messenger.ui.components.BubbleOverflowMenu
import com.vladimir.messenger.ui.components.BubbleMenuAction
import com.vladimir.messenger.util.AppShare
import com.vladimir.messenger.data.group.GroupRole
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Forum
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.MoreVert
import com.vladimir.messenger.ui.components.swipeBack
import androidx.compose.foundation.lazy.rememberLazyListState
import com.vladimir.messenger.ui.components.ApuScrollbar
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
import com.vladimir.messenger.ui.components.ChatWallpaper
import com.vladimir.messenger.ui.components.HintBubble
import com.vladimir.messenger.ui.components.HintBubbleMutedColor
import com.vladimir.messenger.ui.components.HintBubbleTextColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsScreen(
    onGroupClick: (groupId: String) -> Unit,
    onChannelClick: (channelId: String) -> Unit = { onGroupClick(it) },
    onBackClick: () -> Unit,
    /** Управление группой: тот же экран, что и из меню на главной. */
    onGroupAdminClick: (groupId: String) -> Unit = {},
    /** Ссылка-приглашение из QR или из внешнего открытия: сразу пробуем войти. */
    joinLink: String? = null,
    /** Из меню кнопки-карандаша: "group" или "channel" - сразу открыть создание. */
    create: String? = null,
    /**
     * Нижняя панель разделов. Приходит снаружи, из навигации: экран не знает
     * маршрутов и не должен их знать. Пустая по умолчанию, чтобы превью и
     * тесты обходились без навигации.
     */
    bottomBar: @Composable () -> Unit = {},
    viewModel: GroupsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }
    var showRankHint by remember { mutableStateOf(false) }
    // Что создаём из меню «⋮»: группу или канал. Диалог умеет и то и другое,
    // но открывать его сразу на нужном виде удобнее, чем щёлкать переключатель.
    var createAsChannel by remember { mutableStateOf(create == "channel") }
    // Выход из группы и удаление - через подтверждение: это необратимо.
    var confirmLeave by remember { mutableStateOf<GroupSummary?>(null) }
    // Как приглашать: показать QR при встрече или отправить ссылку.
    var inviteChoice by remember { mutableStateOf<GroupSummary?>(null) }
    // Готовый QR: название группы и ссылка со входом без одобрения.
    var qrInvite by remember { mutableStateOf<Pair<String, String>?>(null) }
    val context = LocalContext.current
    // Из меню кнопки-карандаша сразу открываем диалог создания группы/канала.
    LaunchedEffect(create) {
        if (!create.isNullOrBlank() && uiState.canCreate) {
            createAsChannel = create == "channel"
            showCreate = true
        }
    }
    var showJoin by remember { mutableStateOf(false) }

    LaunchedEffect(joinLink) {
        // Проверяем, что это действительно ссылка: в параметр может прилететь
        // шаблон маршрута или посторонний текст - тогда нечего и пытаться.
        if (!joinLink.isNullOrBlank() && GroupInviteLinks.parseTarget(joinLink) != null) {
            viewModel.joinByLink(joinLink)
        }
    }

    // Обои APU лежат подложкой под всем экраном, как в «Контактах»: сам
    // Scaffold и шапка прозрачные, иначе они закрасили бы картину сплошным
    // цветом темы.
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Смахивание вправо работает как «Назад».
            .swipeBack(onBack = onBackClick),
    ) {
    ChatWallpaper()
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        bottomBar = bottomBar,
        topBar = {
            TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    // Прокрутка НЕ должна красить панель: под ней обои APU.
                    scrolledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                title = { Text("Группы") },
                navigationIcon = {
                    TextButton(onClick = onBackClick) { Text("Назад") }
                },
                actions = {
                    // Кроме QR: вставить скопированную ссылку и войти.
                    IconButton(onClick = { showJoin = true }) {
                        Icon(Icons.Filled.Link, contentDescription = "Войти по ссылке")
                    }
                    // Меню «⋮» - как на главном экране: редкие действия не
                    // занимают панель, но и не спрятаны.
                    Box {
                        var menuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Ещё")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            shape = RoundedCornerShape(20.dp),
                            tonalElevation = 3.dp,
                            shadowElevation = 8.dp,
                        ) {
                            DropdownMenuItem(
                                text = { Text("Новая группа") },
                                leadingIcon = { Icon(Icons.Filled.Groups, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    createAsChannel = false
                                    if (uiState.canCreate) showCreate = true else showRankHint = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Новый канал") },
                                leadingIcon = { Icon(Icons.Filled.Campaign, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    createAsChannel = true
                                    if (uiState.canCreate) showCreate = true else showRankHint = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Войти по ссылке") },
                                leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    showJoin = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Обновить список") },
                                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    viewModel.refreshDirectory()
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                createAsChannel = false
                if (uiState.canCreate) showCreate = true else showRankHint = true
            }) {
                Icon(Icons.Filled.Add, contentDescription = "Создать группу или канал")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = viewModel::onSearchQueryChanged,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                placeholder = { Text("Поиск групп и каналов") },
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
                                if (uiState.searchQuery.isBlank()) {
                                    "Групп и каналов пока нет"
                                } else {
                                    "Ничего не нашлось"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = HintBubbleTextColor,
                            )
                            Text(
                                if (uiState.searchQuery.isBlank()) {
                                    "Создайте свою или войдите по ссылке-приглашению"
                                } else {
                                    "Попробуйте другое название или войдите по ссылке"
                                },
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

                else -> {
                    // Бегунок справа: видно, где мы в длинном списке.
                    val scrollState = rememberLazyListState()
                    Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = scrollState,
                        contentPadding = PaddingValues(bottom = 96.dp),
                    ) {
                        // Свои группы и каналы - тоже раздельно: это разные вещи,
                        // и в поиске их надо различать с одного взгляда.
                        val myGroups = uiState.filtered.filter { !it.isChannel }
                        val myChannels = uiState.filtered.filter { it.isChannel }
                        if (myGroups.isNotEmpty()) {
                            item { DirectoryHeader("Мои группы") }
                            items(myGroups, key = { it.id }) { group ->
                                GroupRow(
                                    group = group,
                                    onClick = { onGroupClick(group.id) },
                                    menuActions = groupMenuActions(
                                        group = group,
                                        onOpen = { onGroupClick(group.id) },
                                        onAdmin = { onGroupAdminClick(group.id) },
                                        onInvite = { inviteChoice = group },
                                        onMarkRead = { viewModel.markGroupRead(group.id) },
                                        onLeaveOrDelete = { confirmLeave = group },
                                    ),
                                )
                                HorizontalDivider()
                            }
                        }
                        if (myChannels.isNotEmpty()) {
                            item { DirectoryHeader("Мои каналы") }
                            items(myChannels, key = { it.id }) { group ->
                                // Канал открывается лентой постов, группа - чатом.
                                GroupRow(
                                    group = group,
                                    onClick = { onChannelClick(group.id) },
                                    menuActions = groupMenuActions(
                                        group = group,
                                        onOpen = { onChannelClick(group.id) },
                                        onAdmin = { onGroupAdminClick(group.id) },
                                        onInvite = { inviteChoice = group },
                                        onMarkRead = { viewModel.markGroupRead(group.id) },
                                        onLeaveOrDelete = { confirmLeave = group },
                                    ),
                                )
                                HorizontalDivider()
                            }
                        }
                        // Поиск по сетевому каталогу. Группы и каналы разнесены по
                        // своим заголовкам: в общей куче непонятно, куда вступаешь.
                        val foundGroups = uiState.directoryMatches.filter { !it.isChannel }
                        val foundChannels = uiState.directoryMatches.filter { it.isChannel }
                        val browsing = uiState.searchQuery.isBlank()
                        if (foundGroups.isNotEmpty()) {
                            item {
                                DirectoryHeader(
                                    if (browsing) "Открытые группы сети" else "Группы в сети"
                                )
                            }
                            items(foundGroups, key = { it.groupId }) { entry ->
                                DirectoryRow(entry = entry) { link -> viewModel.joinByLink(link) }
                            }
                        }
                        if (foundChannels.isNotEmpty()) {
                            item {
                                DirectoryHeader(
                                    if (browsing) "Открытые каналы сети" else "Каналы в сети"
                                )
                            }
                            items(foundChannels, key = { it.groupId }) { entry ->
                                DirectoryRow(entry = entry) { link -> viewModel.joinByLink(link) }
                            }
                        }
                    }
                    ApuScrollbar(state = scrollState)
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
            initialIsChannel = createAsChannel,
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

    // Как приглашать: показываем QR или отправляем ссылку.
    inviteChoice?.let { group ->
        val what = if (group.isChannel) "канал" else "группу"
        AlertDialog(
            onDismissRequest = { inviteChoice = null },
            title = { Text("Пригласить в $what") },
            text = {
                Text(
                    "Покажите QR-код, если человек рядом: он отсканирует его и войдёт сразу. " +
                        "Ссылку можно отправить кому угодно - по ней вход как обычно."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val chosen = group
                    inviteChoice = null
                    viewModel.prepareQrInvite(chosen.id) { title, link ->
                        qrInvite = title to link
                    }
                }) { Text("Показать QR-код") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val chosen = group
                    inviteChoice = null
                    viewModel.shareInvite(chosen.id) { title, link ->
                        AppShare.shareGroupInvite(context, title, link)
                    }
                }) { Text("Отправить ссылку") }
            },
        )
    }

    // Сам QR-код для встречи лицом к лицу.
    qrInvite?.let { (title, link) ->
        AlertDialog(
            onDismissRequest = { qrInvite = null },
            title = { Text(title) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Пусть собеседник откроет сканер QR на главном экране " +
                            "и наведёт камеру. Он войдёт без подтверждения.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    InviteShareCard(link = link, displayName = title)
                }
            },
            confirmButton = {
                TextButton(onClick = { qrInvite = null }) { Text("Готово") }
            },
        )
    }

    // Подтверждение выхода или удаления.
    confirmLeave?.let { group ->
        val owner = group.myRole == GroupRole.OWNER
        val what = if (group.isChannel) "канал" else "группу"
        AlertDialog(
            onDismissRequest = { confirmLeave = null },
            title = { Text(if (owner) "Удалить $what?" else "Выйти из группы?") },
            text = {
                Text(
                    if (owner) {
                        "«${group.title}» и вся переписка будут удалены у всех участников."
                    } else {
                        "Вы перестанете получать сообщения группы «${group.title}»."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.leaveOrDelete(group)
                    confirmLeave = null
                }) { Text(if (owner) "Удалить" else "Выйти") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = null }) { Text("Отмена") }
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
private fun GroupRow(
    group: GroupSummary,
    onClick: () -> Unit,
    /** Пункты меню «⋮» справа. Пустой список - кнопки нет (каталог сети). */
    menuActions: List<BubbleMenuAction> = emptyList(),
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.vladimir.messenger.ui.components.GroupAvatar(
                groupId = group.id,
                title = group.title,
                size = 44.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(group.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Что это - канал или группа - должно быть видно СРАЗУ, иначе
                // в общем списке они неразличимы.
                Text(
                    buildString {
                        append(if (group.isChannel) "Канал" else "Группа")
                        append(if (group.isPublic) " · публичная" else " · частная")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
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
            // Меню «⋮» - как в пузырях главного экрана: списки должны
            // выглядеть и вести себя одинаково.
            BubbleOverflowMenu(actions = menuActions)
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
                    label = { Text(if (isChannel) "Название канала" else "Название") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = about,
                    onValueChange = { about = it },
                    label = { Text(if (isChannel) "Описание канала" else "Описание") },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (isChannel) "Публичный канал" else "Публичная группа",
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            if (isChannel) {
                                "Подписка по ссылке без одобрения"
                            } else {
                                "Вход по ссылке без одобрения"
                            },
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
                    placeholder = { Text("Ссылка") },
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

/** Заголовок раздела найденного в сети. */
@Composable
private fun DirectoryHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Строка найденного в сетевом каталоге: чужая публичная группа или канал. */
@Composable
private fun DirectoryRow(entry: DirectoryEntity, onJoin: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.vladimir.messenger.ui.components.GroupAvatar(
            groupId = entry.groupId,
            title = entry.title,
            size = 40.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (entry.isChannel) "Канал" else "Группа",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                if (entry.needsApproval) "Вход по заявке" else "Вход сразу",
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

/**
 * Пункты меню «⋮» в пузыре группы или канала.
 *
 * Набор тот же, что на главном экране: списки обязаны вести себя одинаково,
 * иначе владелец ищет привычное действие и не находит.
 */
private fun groupMenuActions(
    group: GroupSummary,
    onOpen: () -> Unit,
    onAdmin: () -> Unit,
    onInvite: () -> Unit,
    onMarkRead: () -> Unit,
    onLeaveOrDelete: () -> Unit,
): List<BubbleMenuAction> = buildList {
    add(
        BubbleMenuAction(
            title = if (group.isChannel) "Открыть канал" else "Открыть группу",
            icon = Icons.Filled.Forum,
            onClick = onOpen,
        )
    )
    add(
        BubbleMenuAction(
            title = if (group.isChannel) "Пригласить в канал" else "Пригласить в группу",
            icon = Icons.Filled.PersonAdd,
            onClick = onInvite,
        )
    )
    if (GroupRole.isAdminOrOwner(group.myRole)) {
        add(
            BubbleMenuAction(
                title = "Управление",
                icon = Icons.Filled.Settings,
                onClick = onAdmin,
            )
        )
    }
    add(
        BubbleMenuAction(
            title = "Отметить прочитанным",
            icon = Icons.Filled.DoneAll,
            onClick = onMarkRead,
        )
    )
    add(
        BubbleMenuAction(
            title = if (group.myRole == GroupRole.OWNER) {
                if (group.isChannel) "Удалить канал" else "Удалить группу"
            } else {
                "Выйти"
            },
            icon = Icons.Filled.Delete,
            destructive = true,
            onClick = onLeaveOrDelete,
        )
    )
}
