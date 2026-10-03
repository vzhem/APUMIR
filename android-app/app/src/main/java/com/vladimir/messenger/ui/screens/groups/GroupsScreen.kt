package com.vladimir.messenger.ui.screens.groups

// =============================================================================
// GROUPSSCREEN.KT — раздел «Сообщества»: группы и каналы, создание новых
// =============================================================================

import com.vladimir.messenger.ui.components.ApuAction
import com.vladimir.messenger.ui.components.ApuActionsMenu
import com.vladimir.messenger.ui.components.ApuBubbleAccentColor
import com.vladimir.messenger.ui.components.ApuBubbleMutedColor
import com.vladimir.messenger.ui.components.ApuBubbleSurfaceColor
import com.vladimir.messenger.ui.components.ApuBubbleTextColor
import com.vladimir.messenger.ui.components.ApuSearchField
import com.vladimir.messenger.ui.components.ApuSettingsDialog
import com.vladimir.messenger.ui.components.ApuSettingsHeader
import com.vladimir.messenger.ui.components.ApuSettingsLayout
import com.vladimir.messenger.ui.components.ApuSettingsSectionTitle
import com.vladimir.messenger.ui.components.GroupAvatar
import com.vladimir.messenger.ui.components.InviteShareCard
import com.vladimir.messenger.ui.components.BubbleOverflowMenu
import com.vladimir.messenger.ui.components.BubbleMenuAction
import com.vladimir.messenger.util.AppShare
import com.vladimir.messenger.data.group.GroupRole
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Forum
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.MoreVert
import com.vladimir.messenger.ui.components.swipeBack
import androidx.compose.foundation.lazy.rememberLazyListState
import com.vladimir.messenger.ui.components.ApuScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.data.group.GroupInviteLinks
import com.vladimir.messenger.data.link.ShortLinks
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
    /** Открыть конкретный пост канала: переход по ссылке на запись. */
    onOpenPost: (channelId: String, topicId: String) -> Unit = { id, _ -> onChannelClick(id) },
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
    //
    // Открываем РОВНО ОДИН раз за жизнь экрана: параметр остаётся в маршруте,
    // и при возврате из созданного канала диалог всплывал снова, будто
    // предлагая создать ещё один.
    var createHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(create) {
        if (!createHandled && !create.isNullOrBlank() && uiState.canCreate) {
            createHandled = true
            createAsChannel = create == "channel"
            showCreate = true
        }
    }
    var showJoin by remember { mutableStateOf(false) }

    // Входим по ссылке РОВНО ОДИН раз за жизнь экрана - по той же причине,
    // что и с create: параметр остаётся в маршруте, и при возврате из поста
    // вход повторялся, а окно «Вы подписаны на канал» всплывало снова
    // (владелец, 2026-09-08). rememberSaveable переживает уход экрана в стек.
    var joinHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(joinLink) {
        // Проверяем, что это действительно ссылка: в параметр может прилететь
        // шаблон маршрута или посторонний текст - тогда нечего и пытаться.
        if (!joinHandled && !joinLink.isNullOrBlank() &&
            (GroupInviteLinks.parseTarget(joinLink) != null || ShortLinks.isShortLink(joinLink))
        ) {
            joinHandled = true
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
                title = { ApuSettingsHeader("Сообщества") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    // Кроме QR: вставить скопированную ссылку и войти.
                    IconButton(onClick = { showJoin = true }) {
                        Icon(Icons.Filled.Link, contentDescription = "Войти по ссылке")
                    }
                    // Меню «⋮» - в фирменных золотых пузырях, как в каналах и группах.
                    Box {
                        var menuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Ещё")
                        }
                        ApuActionsMenu(
                            expanded = menuOpen,
                            onDismiss = { menuOpen = false },
                            actions = listOf(
                                ApuAction(
                                    title = "Новая группа",
                                    icon = Icons.Filled.Groups,
                                    onClick = {
                                        createAsChannel = false
                                        if (uiState.canCreate) showCreate = true else showRankHint = true
                                    },
                                ),
                                ApuAction(
                                    title = "Новый канал",
                                    icon = Icons.Filled.Campaign,
                                    onClick = {
                                        createAsChannel = true
                                        if (uiState.canCreate) showCreate = true else showRankHint = true
                                    },
                                ),
                                ApuAction(
                                    title = "Войти по ссылке",
                                    icon = Icons.Filled.Link,
                                    onClick = { showJoin = true },
                                ),
                                ApuAction(
                                    title = "Обновить список",
                                    icon = Icons.Filled.Refresh,
                                    onClick = { viewModel.refreshDirectory() },
                                ),
                            ),
                        )
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
            ApuSearchField(
                value = uiState.searchQuery,
                onValueChange = viewModel::onSearchQueryChanged,
                placeholder = "Поиск групп и каналов",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
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
        ApuSettingsDialog(
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
                val postTopic = uiState.joinedPostTopicId
                TextButton(
                    enabled = !uiState.joining,
                    onClick = {
                        viewModel.consumeJoinResult()
                        if (target != null) {
                            // Ссылка на пост открывает сам пост, а не начало
                            // ленты: человек перешёл ради конкретной записи.
                            if (postTopic != null) {
                                onOpenPost(target, postTopic)
                            } else {
                                onGroupClick(target)
                            }
                        }
                    },
                ) {
                    Text(
                        when {
                            target == null -> "Готово"
                            postTopic != null -> "Открыть пост"
                            else -> "Открыть чат"
                        }
                    )
                }
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
        // Раунд 198: получателя ссылки может не быть с APU - по умолчанию
        // прикладываем установочный APK; галочка позволяет не таскать 40 МБ.
        var attachApk by remember { mutableStateOf(true) }
        ApuSettingsDialog(
            onDismissRequest = { inviteChoice = null },
            title = { Text("Пригласить в $what") },
            text = {
                Column {
                    Text(
                        "Покажите QR-код, если человек рядом: он отсканирует его и войдёт сразу. " +
                            "Ссылку можно отправить кому угодно - по ней вход как обычно."
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { attachApk = !attachApk }
                            .padding(horizontal = 4.dp),
                    ) {
                        Checkbox(checked = attachApk, onCheckedChange = { attachApk = it })
                        Text(
                            "Приложить установочный файл (APK)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
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
                        // Раунд 162: каналу - «канал».
                        AppShare.shareGroupInvite(context, title, link, chosen.isChannel, attachApk)
                    }
                }) { Text("Отправить ссылку") }
            },
        )
    }

    // Сам QR-код для встречи лицом к лицу.
    qrInvite?.let { (title, link) ->
        ApuSettingsDialog(
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
        ApuSettingsDialog(
            onDismissRequest = { confirmLeave = null },
            title = {
                Text(
                    when {
                        owner -> "Удалить $what?"
                        group.isChannel -> "Отписаться от канала?"
                        else -> "Выйти из группы?"
                    }
                )
            },
            text = {
                Text(
                    when {
                        owner && group.isChannel ->
                            "«${group.title}» и все посты будут удалены у всех подписчиков."
                        owner ->
                            "«${group.title}» и вся переписка будут удалены у всех участников."
                        group.isChannel ->
                            "Вы перестанете получать посты канала «${group.title}»."
                        else ->
                            "Вы перестанете получать сообщения группы «${group.title}»."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.leaveOrDelete(group)
                    confirmLeave = null
                }) {
                    Text(
                        when {
                            owner -> "Удалить"
                            group.isChannel -> "Отписаться"
                            else -> "Выйти"
                        },
                        color = if (owner) MaterialTheme.colorScheme.error else ApuBubbleAccentColor,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = null }) { Text("Отмена") }
            },
        )
    }

    if (showRankHint) {
        ApuSettingsDialog(
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
    // Раунд 264: фирменный пузырь главного экрана - светлая полупрозрачная
    // подложка с золотой рамкой поверх обоев. Списки выглядят одинаково.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(ApuBubbleSurfaceColor)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                shape = RoundedCornerShape(18.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
            GroupAvatar(
                groupId = group.id,
                title = group.title,
                size = 44.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    group.title,
                    fontWeight = FontWeight.SemiBold,
                    color = ApuBubbleTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Что это - канал или группа - должно быть видно СРАЗУ, иначе
                // в общем списке они неразличимы.
                Text(
                    buildString {
                        append(if (group.isChannel) "Канал" else "Группа")
                        append(if (group.isPublic) " · публичная" else " · частная")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = ApuBubbleAccentColor,
                    maxLines = 1,
                )
                Text(
                    buildString {
                        append(group.memberCount)
                        append(" участн.")
                        if (group.topicsEnabled) append(" • темы")
                        // Раунд 156: без служебных строк (гифки/стикеры).
                        group.lastMessagePreview?.let {
                            append(" • ").append(
                                com.vladimir.messenger.util.ChatPreviews.human(it).orEmpty()
                            )
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = ApuBubbleMutedColor,
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

    ApuSettingsDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .border(1.5.dp, ApuBubbleAccentColor.copy(alpha = 0.38f), CircleShape)
                        .padding(4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (title.isNotBlank()) {
                        GroupAvatar(
                            groupId = title.trim(),
                            title = title,
                            size = 44.dp,
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(ApuBubbleAccentColor.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (isChannel) Icons.Filled.Campaign else Icons.Filled.Groups,
                                contentDescription = null,
                                tint = ApuBubbleAccentColor,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isChannel) "Новый канал" else "Новая группа",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = ApuBubbleTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (isChannel) {
                            "Лента публикаций с комментариями"
                        } else {
                            "Общение участников и темы"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = ApuBubbleMutedColor,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Выбор вида сообщества сверху — как в современных мессенджерах:
                // сразу видно «Группа» или «Канал», без лишнего переключателя внизу.
                CommunityTypeChoices(
                    isChannel = isChannel,
                    onSelectChannel = { isChannel = it },
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(if (isChannel) "Название канала" else "Название") },
                    placeholder = {
                        Text(if (isChannel) "Например, Новости APU" else "Например, Рабочий чат")
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = about,
                    onValueChange = { about = it },
                    label = { Text(if (isChannel) "Описание канала" else "Описание") },
                    placeholder = { Text("О чём сообщество (необязательно)") },
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                // Настройки доступа и тем — в единой карточке с иконками в стиле APU.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(ApuBubbleAccentColor.copy(alpha = 0.05f))
                        .border(
                            width = 1.dp,
                            color = ApuBubbleAccentColor.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(16.dp),
                        ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 68.dp)
                            .clickable(role = Role.Switch) { isPublic = !isPublic }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(
                                    ApuBubbleAccentColor.copy(alpha = 0.10f),
                                    RoundedCornerShape(12.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (isPublic) Icons.Filled.Public else Icons.Filled.Lock,
                                contentDescription = null,
                                tint = ApuBubbleAccentColor,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = if (isChannel) "Публичный канал" else "Публичная группа",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = ApuBubbleTextColor,
                            )
                            Text(
                                text = if (isChannel) {
                                    "Подписка по ссылке без одобрения"
                                } else {
                                    "Вход по ссылке без одобрения"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = ApuBubbleMutedColor,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = isPublic, onCheckedChange = { isPublic = it })
                    }

                    if (!isChannel) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 62.dp, end = 12.dp),
                            thickness = 0.5.dp,
                            color = ApuBubbleAccentColor.copy(alpha = 0.15f),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 68.dp)
                                .clickable(role = Role.Switch) { topics = !topics }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(
                                        ApuBubbleAccentColor.copy(alpha = 0.10f),
                                        RoundedCornerShape(12.dp),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Forum,
                                    contentDescription = null,
                                    tint = ApuBubbleAccentColor,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = "Темы",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = ApuBubbleTextColor,
                                )
                                Text(
                                    text = "Обсуждения внутри группы",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ApuBubbleMutedColor,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(checked = topics, onCheckedChange = { topics = it })
                        }
                    }
                }

                if (error != null) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = title.isNotBlank() && !creating,
                onClick = { onCreate(title, about, isPublic, topics, isChannel) },
                shape = RoundedCornerShape(14.dp),
            ) {
                if (creating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFFFFF8E6),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (creating) "Создаём…" else "Создать")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun CommunityTypeChoices(
    isChannel: Boolean,
    onSelectChannel: (Boolean) -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    val options = listOf(
        Triple(false, "Группа", "Чаты и темы"),
        Triple(true, "Канал", "Посты и комментарии"),
    )
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val horizontal = ApuSettingsLayout.horizontalCommunityTypeChoices(maxWidth.value, fontScale)
        val rows = if (horizontal) listOf(options) else options.map { listOf(it) }
        Column(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rows.forEach { rowOptions ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowOptions.forEach { (channelOption, label, subtitle) ->
                        val selected = isChannel == channelOption
                        val shape = RoundedCornerShape(14.dp)
                        val ink = if (selected) Color(0xFFFFF8E6) else ApuBubbleAccentColor
                        val subInk = if (selected) Color(0xFFFFF8E6).copy(alpha = 0.85f) else ApuBubbleMutedColor
                        val icon = if (channelOption) Icons.Filled.Campaign else Icons.Filled.Groups
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(shape)
                                .background(
                                    if (selected) ApuBubbleAccentColor else ApuBubbleAccentColor.copy(alpha = 0.06f),
                                )
                                .border(
                                    width = 1.dp,
                                    color = ApuBubbleAccentColor.copy(alpha = if (selected) 0.75f else 0.18f),
                                    shape = shape,
                                )
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = { onSelectChannel(channelOption) },
                                )
                                .heightIn(min = 58.dp)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = ink,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = subInk,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
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

    ApuSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text("Войти по ссылке") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Вставьте ссылку-приглашение или пересланное сообщение со ссылкой.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ApuBubbleMutedColor,
                )
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
                    shape = RoundedCornerShape(14.dp),
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
            Button(
                onClick = {
                    // Проверяем, что это приглашение в группу, но отдаём ВСЮ
                    // ссылку: в ней id группы и адрес владельца, без них войти
                    // с другого телефона нельзя.
                    // Короткую ссылку /s/<код> принимаем тоже: что за ней -
                    // узнает репозиторий у сервиса.
                    if (GroupInviteLinks.parseTarget(link) == null && !ShortLinks.isShortLink(link)) {
                        error = "Не похоже на ссылку-приглашение в группу"
                    } else {
                        onSubmit(link.trim())
                    }
                },
                shape = RoundedCornerShape(14.dp),
            ) { Text("Войти") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

/** Заголовок раздела сообществ и сетевого каталога в фирменной капсуле. */
@Composable
private fun DirectoryHeader(title: String) {
    ApuSettingsSectionTitle(title)
}

/** Строка найденного в сетевом каталоге: чужая публичная группа или канал. */
@Composable
private fun DirectoryRow(entry: DirectoryEntity, onJoin: (String) -> Unit) {
    // Раунд 264: открытые группы и каналы сети - тоже в фирменных пузырях,
    // как и «мои» сообщества: списки выглядят едино.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(ApuBubbleSurfaceColor)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                shape = RoundedCornerShape(18.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupAvatar(
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
                // Раунд 264: строка теперь в светлом пузыре - тёмный текст.
                color = ApuBubbleTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (entry.isChannel) "Канал" else "Группа",
                style = MaterialTheme.typography.labelMedium,
                color = ApuBubbleAccentColor,
            )
            Text(
                if (entry.needsApproval) "Вход по заявке" else "Вход сразу",
                style = MaterialTheme.typography.bodySmall,
                color = ApuBubbleMutedColor,
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
        }) {
            Text(
                if (entry.isChannel) "Подписаться" else "Вступить",
                color = ApuBubbleAccentColor,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
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
            title = if (group.isChannel) "Пригласить в канал" else "Пригласить в сообщество",
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
            title = when {
                group.myRole == GroupRole.OWNER ->
                    if (group.isChannel) "Удалить канал" else "Удалить группу"
                group.isChannel -> "Отписаться"
                else -> "Выйти"
            },
            icon = Icons.Filled.Delete,
            destructive = true,
            onClick = onLeaveOrDelete,
        )
    )
}
