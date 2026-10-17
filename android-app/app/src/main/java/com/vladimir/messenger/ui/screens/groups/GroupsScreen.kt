package com.vladimir.messenger.ui.screens.groups

// =============================================================================
// GROUPSSCREEN.KT — раздел «Сообщества»: группы и каналы, создание новых
// =============================================================================

import com.vladimir.messenger.ui.components.ApuPremiumSpinner
import android.content.Context
import com.vladimir.messenger.R
import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.ui.components.ApuAction
import com.vladimir.messenger.ui.components.ApuActionsMenu
import com.vladimir.messenger.ui.components.ApuBubbleField
import com.vladimir.messenger.ui.components.ApuNotificationBadge
import com.vladimir.messenger.ui.components.ApuCommunitiesEmpty
import com.vladimir.messenger.ui.components.ApuCommunitiesHero
import com.vladimir.messenger.ui.components.ApuCommunitiesSkeleton
import com.vladimir.messenger.ui.components.ApuCommunityKindBadge
import com.vladimir.messenger.ui.components.ApuSegmentedControl
import com.vladimir.messenger.ui.components.ApuTextAction
import com.vladimir.messenger.ui.components.NotificationMuteDialog
import com.vladimir.messenger.ui.components.ApuBubbleAccentColor
import com.vladimir.messenger.ui.components.ApuBubbleMutedColor
import com.vladimir.messenger.ui.components.ApuBubbleTextColor
import com.vladimir.messenger.ui.components.ApuSearchField
import com.vladimir.messenger.ui.components.apuBubbleSurface
import com.vladimir.messenger.ui.components.apuPremiumGloss
import com.vladimir.messenger.ui.components.apuPremiumLift
import com.vladimir.messenger.ui.components.apuPremiumThread
import com.vladimir.messenger.ui.components.ApuSettingsDialog
import com.vladimir.messenger.ui.components.ApuSettingsHeader
import com.vladimir.messenger.ui.components.ApuSettingsLayout
import com.vladimir.messenger.ui.components.ApuSettingsSectionTitle
import com.vladimir.messenger.ui.components.GroupAvatar
import com.vladimir.messenger.ui.components.GroupInviteRecipientsDialog
import com.vladimir.messenger.ui.components.InviteShareCard
import com.vladimir.messenger.ui.components.BubbleOverflowMenu
import com.vladimir.messenger.ui.components.BubbleMenuAction
import com.vladimir.messenger.util.AppShare
import com.vladimir.messenger.data.group.GroupRole
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import com.vladimir.messenger.ui.components.ApuPremiumIconTile
import com.vladimir.messenger.ui.components.ApuSettingsCard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Forum
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.vladimir.messenger.data.local.entity.DirectoryEntity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.vladimir.messenger.ui.components.ApuPremiumContentButton
import com.vladimir.messenger.ui.components.ApuPremiumFloatingActionButton
import com.vladimir.messenger.ui.components.ApuPremiumCheckbox
import com.vladimir.messenger.ui.components.ApuPremiumSwitch
import com.vladimir.messenger.ui.components.DiagnosticsActionStyle
import com.vladimir.messenger.ui.components.ApuGoldInk

/** Порядок переключателя «Все · Группы · Каналы»: тот же, что у разделов списка. */
private val communityFilters = listOf(
    CommunityFilter.ALL,
    CommunityFilter.GROUPS,
    CommunityFilter.CHANNELS,
)

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
    /** «Мои заявки»: true - заявки в каналы, false - в группы. */
    onMyRequestsClick: (channels: Boolean) -> Unit = {},
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
    val requestCounts by viewModel.myRequestCounts.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }
    var showRankHint by remember { mutableStateOf(false) }
    // Что создаём из меню «⋮»: группу или канал. Диалог умеет и то и другое,
    // но открывать его сразу на нужном виде удобнее, чем щёлкать переключатель.
    var createAsChannel by remember { mutableStateOf(create == "channel") }
    // Выход из группы и удаление - через подтверждение: это необратимо.
    var confirmLeave by remember { mutableStateOf<GroupSummary?>(null) }
    var muteTarget by remember { mutableStateOf<GroupSummary?>(null) }
    // Как приглашать: показать QR, отправить внешнюю ссылку или написать в APU.
    var inviteChoice by remember { mutableStateOf<GroupSummary?>(null) }
    var inviteApu by remember { mutableStateOf<GroupSummary?>(null) }
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
    // Переключатель вида: «Все», «Группы», «Каналы». Живёт в памяти экрана,
    // чтобы возвращение из сообщества показывало тот же срез, что оставили.
    var communityFilter by rememberSaveable { mutableStateOf(CommunityFilter.ALL) }

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
                title = { ApuSettingsHeader(stringResource(R.string.nav_communities)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    // Кроме QR: вставить скопированную ссылку и войти.
                    IconButton(onClick = { showJoin = true }) {
                        Icon(Icons.Filled.Link, contentDescription = stringResource(R.string.groups_join_by_link))
                    }
                    // Меню «⋮» - в фирменных золотых пузырях, как в каналах и группах.
                    Box {
                        var menuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.chat_more))
                        }
                        ApuActionsMenu(
                            expanded = menuOpen,
                            onDismiss = { menuOpen = false },
                            actions = listOf(
                                ApuAction(
                                    title = stringResource(R.string.chat_new_group),
                                    icon = Icons.Filled.Groups,
                                    onClick = {
                                        createAsChannel = false
                                        if (uiState.canCreate) showCreate = true else showRankHint = true
                                    },
                                ),
                                ApuAction(
                                    title = stringResource(R.string.chat_new_channel),
                                    icon = Icons.Filled.Campaign,
                                    onClick = {
                                        createAsChannel = true
                                        if (uiState.canCreate) showCreate = true else showRankHint = true
                                    },
                                ),
                                ApuAction(
                                    title = stringResource(R.string.groups_join_by_link),
                                    icon = Icons.Filled.Link,
                                    onClick = { showJoin = true },
                                ),
                                ApuAction(
                                    title = stringResource(R.string.groups_refresh_list),
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
            ApuPremiumFloatingActionButton(
                onClick = {
                    createAsChannel = false
                    if (uiState.canCreate) showCreate = true else showRankHint = true
                },
                icon = Icons.Filled.Add,
                contentDescription = stringResource(R.string.groups_create_desc),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                // Клавиатура сжимает список, а не накрывает его: во время набора
                // поле поиска и найденные сообщества остаются видны.
                .imePadding(),
        ) {
            ApuSearchField(
                value = uiState.searchQuery,
                onValueChange = viewModel::onSearchQueryChanged,
                placeholder = stringResource(R.string.groups_search),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                // Крестик очистки: длинный запрос стирается одним касанием.
                // При пустом поле слот остаётся пустым и места не занимает.
                trailing = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.groups_clear_search),
                                tint = ApuBubbleTextColor.copy(alpha = 0.6f),
                            )
                        }
                    }
                },
            )

            if (uiState.isLoading) {
                // Пока Room отдаёт список, экран показывает очертания будущих
                // строк, а не пустоту с одиноким спиннером по центру.
                ApuCommunitiesSkeleton()
            } else {
                // Поиск уже применён моделью; здесь — только отбор по виду
                // сообщества. Правила отбора живут в CommunityList: их
                // проверяют JVM-тесты `CommunityListTest`.
                val mine = CommunityList.applyFilter(uiState.filtered, communityFilter)
                val network = CommunityList.applyFilterToDirectory(uiState.directoryMatches, communityFilter)
                val myGroups = mine.filterNot { it.isChannel }
                val showGroupRequests = requestCounts.first > 0 && communityFilter != CommunityFilter.CHANNELS
                val showChannelRequests = requestCounts.second > 0 && communityFilter != CommunityFilter.GROUPS
                val myChannels = mine.filter { it.isChannel }
                val foundGroups = network.filterNot { it.isChannel }
                val foundChannels = network.filter { it.isChannel }

                // Счётчики считаются ДО отбора по виду: иначе выбранный фильтр
                // обнулял бы подписи у соседних вариантов.
                val groupTotal = uiState.filtered.count { !it.isChannel } +
                    uiState.directoryMatches.count { !it.isChannel }
                val channelTotal = uiState.filtered.count { it.isChannel } +
                    uiState.directoryMatches.count { it.isChannel }
                val anyTotal = groupTotal + channelTotal

                val searching = uiState.searchQuery.isNotBlank()
                // Пусто по трём РАЗНЫМ причинам: ничего нет вовсе, фильтр вида
                // скрыл всё или поиск не нашёл. Врать одно «ничего не нашлось»
                // на все случаи - значит оставить человека без объяснения.
                val showingNothing = myGroups.isEmpty() && myChannels.isEmpty() &&
                    foundGroups.isEmpty() && foundChannels.isEmpty()
                val resetting = searching || communityFilter != CommunityFilter.ALL
                // Заголовки разделов каталога зависят от того, ищем мы или просто
                // смотрим, что есть в сети.
                val browsing = uiState.searchQuery.isBlank()

                // Действия пустого состояния. Объявлены отдельными val: у
                // параметров-функций должен быть ясный тип.
                val createLabel = if (anyTotal == 0 && uiState.canCreate) {
                    stringResource(R.string.groups_create_desc)
                } else {
                    null
                }
                val openCreate: () -> Unit = {
                    createAsChannel = false
                    showCreate = true
                }
                val resetView: () -> Unit = {
                    communityFilter = CommunityFilter.ALL
                    if (uiState.searchQuery.isNotEmpty()) viewModel.onSearchQueryChanged("")
                }
                val refreshDirectory: () -> Unit = { viewModel.refreshDirectory() }

                val scrollState = rememberLazyListState()
                // Смена среза возвращает список к началу: найденное не остаётся
                // за верхней кромкой экрана.
                LaunchedEffect(communityFilter, uiState.searchQuery) {
                    scrollState.scrollToItem(0)
                }

                // Бегунок справа: видно, где мы в длинном списке.
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = scrollState,
                        contentPadding = PaddingValues(bottom = 96.dp),
                    ) {
                        // Сводка и переключатель прокручиваются вместе со списком:
                        // место отдаётся сообществам, а не шапке.
                        if (anyTotal > 0) {
                            item(key = "communities-hero") {
                                ApuCommunitiesHero(
                                    groups = uiState.groups.count { !it.isChannel },
                                    channels = uiState.groups.count { it.isChannel },
                                    online = uiState.directoryMatches.size,
                                )
                            }
                            item(key = "communities-filter") {
                                ApuSegmentedControl(
                                    options = communityFilters,
                                    selected = communityFilter,
                                    onSelect = { chosen -> communityFilter = chosen },
                                    label = { option ->
                                        val title = when (option) {
                                            CommunityFilter.ALL -> stringResource(R.string.groups_filter_all)
                                            CommunityFilter.GROUPS -> stringResource(R.string.groups_filter_groups)
                                            CommunityFilter.CHANNELS -> stringResource(R.string.groups_filter_channels)
                                        }
                                        val count = when (option) {
                                            CommunityFilter.ALL -> anyTotal
                                            CommunityFilter.GROUPS -> groupTotal
                                            CommunityFilter.CHANNELS -> channelTotal
                                        }
                                        "$title · $count"
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        }

                        // Пустой экран - не тупик: объясняем словами и даём
                        // действия вместо подсказки, из которой непонятно, что
                        // нажимать.
                        if (showingNothing) {
                            item(key = "communities-empty") {
                                ApuCommunitiesEmpty(
                                    icon = if (searching) Icons.Filled.SearchOff else Icons.Filled.Groups,
                                    title = when {
                                        searching -> stringResource(R.string.groups_nothing_found)
                                        anyTotal > 0 -> stringResource(R.string.groups_filter_empty)
                                        else -> stringResource(R.string.groups_none)
                                    },
                                    body = when {
                                        searching -> stringResource(R.string.groups_search_hint)
                                        anyTotal > 0 -> stringResource(R.string.groups_filter_empty_hint)
                                        else -> stringResource(R.string.groups_empty_hint)
                                    },
                                    primaryLabel = stringResource(R.string.groups_join_by_link),
                                    primaryIcon = Icons.Filled.Link,
                                    onPrimary = { showJoin = true },
                                    secondaryLabel = createLabel,
                                    onSecondary = if (createLabel == null) null else openCreate,
                                    quietLabel = if (resetting) {
                                        stringResource(R.string.groups_show_all)
                                    } else {
                                        stringResource(R.string.groups_refresh_list)
                                    },
                                    quietIcon = if (resetting) Icons.Filled.Close else Icons.Filled.Refresh,
                                    onQuiet = if (resetting) resetView else refreshDirectory,
                                )
                            }
                        }

                        // Свои группы и каналы - тоже раздельно: это разные вещи,
                        // и в поиске их надо различать с одного взгляда.
                        if (myGroups.isNotEmpty()) {
                            item(key = "head-my-groups") {
                                DirectoryHeaderCounted(stringResource(R.string.groups_my_groups), myGroups.size)
                            }
                        }
                        // Заявки в группы - только в разделе групп и только если они есть.
                        if (showGroupRequests) {
                            item(key = "my-requests-groups") {
                                MyRequestsEntry(
                                    title = stringResource(R.string.groups_my_requests_groups),
                                    onClick = { onMyRequestsClick(false) },
                                )
                            }
                        }
                        if (myGroups.isNotEmpty()) {
                            items(myGroups, key = { "mine-" + it.id }) { group ->
                                GroupRow(
                                    group = group,
                                    onClick = { onGroupClick(group.id) },
                                    menuActions = groupMenuActions(
                                        context = context,
                                        group = group,
                                        onOpen = { onGroupClick(group.id) },
                                        onAdmin = { onGroupAdminClick(group.id) },
                                        onInvite = { inviteChoice = group },
                                        onMute = { muteTarget = group },
                                        onMarkRead = { viewModel.markGroupRead(group.id) },
                                        onLeaveOrDelete = { confirmLeave = group },
                                    ),
                                )
                            }
                        }
                        if (myChannels.isNotEmpty()) {
                            item(key = "head-my-channels") {
                                DirectoryHeaderCounted(stringResource(R.string.groups_my_channels), myChannels.size)
                            }
                        }
                        // Заявки в каналы - только в разделе каналов и только если они есть.
                        if (showChannelRequests) {
                            item(key = "my-requests-channels") {
                                MyRequestsEntry(
                                    title = stringResource(R.string.groups_my_requests_channels),
                                    onClick = { onMyRequestsClick(true) },
                                )
                            }
                        }
                        if (myChannels.isNotEmpty()) {
                            items(myChannels, key = { "mine-" + it.id }) { group ->
                                // Канал открывается лентой постов, группа - чатом.
                                GroupRow(
                                    group = group,
                                    onClick = { onChannelClick(group.id) },
                                    menuActions = groupMenuActions(
                                        context = context,
                                        group = group,
                                        onOpen = { onChannelClick(group.id) },
                                        onAdmin = { onGroupAdminClick(group.id) },
                                        onInvite = { inviteChoice = group },
                                        onMute = { muteTarget = group },
                                        onMarkRead = { viewModel.markGroupRead(group.id) },
                                        onLeaveOrDelete = { confirmLeave = group },
                                    ),
                                )
                            }
                        }
                        // Поиск по сетевому каталогу. Группы и каналы разнесены по
                        // своим заголовкам: в общей куче непонятно, куда вступаешь.
                        if (foundGroups.isNotEmpty()) {
                            item(key = "head-net-groups") {
                                DirectoryHeaderCounted(
                                    if (browsing) stringResource(R.string.groups_open_groups) else stringResource(R.string.groups_online_groups),
                                    foundGroups.size,
                                )
                            }
                            items(foundGroups, key = { "net-" + it.groupId }) { entry ->
                                DirectoryRow(entry = entry) { link -> viewModel.joinByLink(link) }
                            }
                        }
                        if (foundChannels.isNotEmpty()) {
                            item(key = "head-net-channels") {
                                DirectoryHeaderCounted(
                                    if (browsing) stringResource(R.string.groups_open_channels) else stringResource(R.string.groups_online_channels),
                                    foundChannels.size,
                                )
                            }
                            items(foundChannels, key = { "net-" + it.groupId }) { entry ->
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

    val joinMessage = uiState.joinMessage
    if (uiState.joining || joinMessage != null) {
        ApuSettingsDialog(
            onDismissRequest = { viewModel.consumeJoinResult() },
            title = { Text(stringResource(R.string.groups_join_title)) },
            text = {
                if (uiState.joining) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ApuPremiumSpinner(modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.groups_connecting))
                    }
                } else {
                    Text(joinMessage.orEmpty())
                }
            },
            confirmButton = {
                val target = uiState.joinedGroupId
                val isChannel = uiState.joinedIsChannel
                val postTopic = uiState.joinedPostTopicId
                ApuTextAction(
                    label = when {
                            target == null -> stringResource(R.string.action_done)
                            postTopic != null -> stringResource(R.string.groups_open_post)
                            isChannel -> stringResource(R.string.menu_open_channel)
                            else -> stringResource(R.string.menu_open_chat)
                        },
                    onClick = {
                        viewModel.consumeJoinResult()
                        if (target != null) {
                            if (postTopic != null) {
                                onOpenPost(target, postTopic)
                            } else if (isChannel) {
                                onChannelClick(target)
                            } else {
                                onGroupClick(target)
                            }
                        }
                    },
                    enabled = !uiState.joining,
                )
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

    // QR, внешняя ссылка и отправка приглашения в личные чаты APU.
    inviteChoice?.let { group ->
        val what = if (group.isChannel) stringResource(R.string.word_channel_acc) else stringResource(R.string.word_group_acc)
        // Раунд 198: получателя ссылки может не быть с APU - по умолчанию
        // прикладываем установочный APK; галочка позволяет не таскать 40 МБ.
        var attachApk by remember { mutableStateOf(true) }
        ApuSettingsDialog(
            onDismissRequest = { inviteChoice = null },
            title = { Text(stringResource(R.string.invite_title, what)) },
            text = {
                Column {
                    ApuPremiumContentButton(
                        onClick = {
                            inviteChoice = null
                            inviteApu = group
                        },
                        modifier = Modifier.fillMaxWidth(),
                        style = DiagnosticsActionStyle.PRIMARY,
                    ) {
                        Icon(Icons.Filled.PersonAdd, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.contacts_send_in_apu),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        stringResource(R.string.invite_send_apu_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = ApuBubbleMutedColor,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.grp_qr_hint))
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { attachApk = !attachApk }
                            .padding(horizontal = 4.dp),
                    ) {
                        ApuPremiumCheckbox(checked = attachApk, onCheckedChange = { attachApk = it })
                        Text(
                            stringResource(R.string.contacts_attach_apk),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                ApuTextAction(
                    label = stringResource(R.string.groups_show_qr),
                    onClick = {
                    val chosen = group
                    inviteChoice = null
                    viewModel.prepareQrInvite(chosen.id) { title, link ->
                        qrInvite = title to link
                    }
                },
                )
            },
            dismissButton = {
                ApuTextAction(
                    label = stringResource(R.string.groups_send_link),
                    onClick = {
                    val chosen = group
                    inviteChoice = null
                    viewModel.shareInvite(chosen.id) { title, link ->

                        AppShare.shareGroupInvite(context, title, link, chosen.isChannel, attachApk)
                    }
                },
                )
            },
        )
    }

    inviteApu?.let { group ->
        GroupInviteRecipientsDialog(
            group = group,
            loadPersonalChats = viewModel::personalChatsOnce,
            loadMemberIds = viewModel::groupMemberIdsOnce,
            sendInvites = viewModel::sendGroupInviteToChats,
            onDismiss = { inviteApu = null },
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
                        stringResource(R.string.grp_qr_wait),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    InviteShareCard(link = link, displayName = title)
                }
            },
            confirmButton = {
                ApuTextAction(label = stringResource(R.string.action_done), onClick = { qrInvite = null })
            },
        )
    }

    // Подтверждение выхода или удаления.
    confirmLeave?.let { group ->
        val owner = group.myRole == GroupRole.OWNER
        val what = if (group.isChannel) stringResource(R.string.word_channel_acc) else stringResource(R.string.word_group_acc)
        ApuSettingsDialog(
            onDismissRequest = { confirmLeave = null },
            title = {
                Text(
                    when {
                        owner -> stringResource(R.string.confirm_delete_title, what)
                        group.isChannel -> stringResource(R.string.groups_unsubscribe_channel_q)
                        else -> stringResource(R.string.groups_leave_group_q)
                    }
                )
            },
            text = {
                Text(
                    when {
                        owner && group.isChannel ->
                            stringResource(R.string.grp_del_channel_all, group.title)
                        owner ->
                            stringResource(R.string.grp_del_group_all, group.title)
                        group.isChannel ->
                            stringResource(R.string.grp_unsub_channel_body, group.title)
                        else ->
                            stringResource(R.string.grp_leave_group_body, group.title)
                    }
                )
            },
            confirmButton = {
                ApuTextAction(
                    label = when {
                            owner -> stringResource(R.string.action_delete)
                            group.isChannel -> stringResource(R.string.menu_unsubscribe)
                            else -> stringResource(R.string.action_logout)
                        },
                    onClick = {
                    viewModel.leaveOrDelete(group)
                    confirmLeave = null
                },
                    danger = true,
                )
            },
            dismissButton = {
                ApuTextAction(label = stringResource(R.string.action_cancel), onClick = { confirmLeave = null })
            },
        )
    }

    if (showRankHint) {
        ApuSettingsDialog(
            onDismissRequest = { showRankHint = false },
            title = { Text(stringResource(R.string.groups_create_unavailable)) },
            text = {
                Text(
                    stringResource(R.string.groups_create_rank)
                )
            },
            confirmButton = { ApuTextAction(label = stringResource(R.string.groups_ok), onClick = { showRankHint = false }) },
        )
    }

    muteTarget?.let { group ->
        NotificationMuteDialog(
            targetName = group.title,
            mutedUntilMs = group.mutedUntilMs,
            onSelectUntil = { untilMs ->
                viewModel.setNotificationsMutedUntil(group.id, untilMs)
                muteTarget = null
            },
            onTurnOn = {
                viewModel.setNotificationsMutedUntil(group.id, 0L)
                muteTarget = null
            },
            onDismiss = { muteTarget = null },
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
            // Владелец 2026-10-07: «в таком стиле нужно переделать всё приложение».
            // Строка списка — та же премиальная поверхность, что шапки и диалоги:
            // подъём, единая подложка, золотая нить по кромке, блеск под текстом.
            .apuPremiumLift(5.dp)
            .apuBubbleSurface()
            .apuPremiumThread(inset = 16.dp)
            .apuPremiumGloss(intensity = 0.45f, topFraction = 0.55f)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Аватар со знаком вида: группа это или канал - видно с одного взгляда,
        // и в общем списке не приходится вычитывать подпись под названием.
        Box {
            GroupAvatar(
                groupId = group.id,
                title = group.title,
                size = 46.dp,
            )
            ApuCommunityKindBadge(
                isChannel = group.isChannel,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    group.title,
                    fontWeight = FontWeight.SemiBold,
                    color = ApuBubbleTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // Пауза уведомлений видна прямо в списке: иначе кажется, что
                // сообщество молчит само по себе.
                if (group.mutedUntilMs > System.currentTimeMillis()) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.NotificationsOff,
                        contentDescription = stringResource(R.string.menu_unmute),
                        tint = ApuBubbleMutedColor,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            // Что это - канал или группа - и какой доступ: видно СРАЗУ, иначе
            // в общем списке они неразличимы.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (group.isPublic) Icons.Filled.Public else Icons.Filled.Lock,
                    contentDescription = null,
                    tint = ApuBubbleAccentColor,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    buildString {
                        append(if (group.isChannel) stringResource(R.string.contacts_channel) else stringResource(R.string.contacts_group))
                        append(" · ").append(if (group.isPublic) stringResource(R.string.grp_public) else stringResource(R.string.grp_private))
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = ApuBubbleAccentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                buildString {
                    append(stringResource(R.string.members_count_short, group.memberCount))
                    if (group.topicsEnabled) append(" • ").append(stringResource(R.string.topics_tag))
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
            ApuNotificationBadge(group.pendingRequests)
            Spacer(Modifier.width(8.dp))
        }
        ApuNotificationBadge(group.unreadCount)
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
            Text(
                text = if (isChannel) stringResource(R.string.chat_new_channel) else stringResource(R.string.chat_new_group),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                // ApuSettingsDialog уже прокручивает карточку. Вложенный
                // verticalScroll здесь получает бесконечную высоту и роняет
                // приложение в момент открытия (плюс на «Сообществах»).
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
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
                    Text(
                        text = if (isChannel) {
                            stringResource(R.string.groups_channel_desc)
                        } else {
                            stringResource(R.string.groups_group_desc)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = ApuBubbleMutedColor,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Выбор вида сообщества сверху — как в современных мессенджерах:
                // сразу видно «Группа» или «Канал», без лишнего переключателя внизу.
                CommunityTypeChoices(
                    isChannel = isChannel,
                    onSelectChannel = { isChannel = it },
                )

                ApuBubbleField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(if (isChannel) stringResource(R.string.groups_name_channel) else stringResource(R.string.groups_name)) },
                    placeholder = {
                        Text(if (isChannel) stringResource(R.string.groups_example_channel) else stringResource(R.string.groups_example_group))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ApuBubbleField(
                    value = about,
                    onValueChange = { about = it },
                    label = { Text(if (isChannel) stringResource(R.string.groups_desc_channel) else stringResource(R.string.groups_desc)) },
                    placeholder = { Text(stringResource(R.string.groups_desc_placeholder)) },
                    minLines = 2,
                    maxLines = 4,
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
                                text = if (isChannel) stringResource(R.string.groups_public_channel) else stringResource(R.string.groups_public_group),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = ApuBubbleTextColor,
                            )
                            Text(
                                text = if (isChannel) {
                                    stringResource(R.string.groups_sub_no_approval)
                                } else {
                                    stringResource(R.string.groups_join_no_approval)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = ApuBubbleMutedColor,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        ApuPremiumSwitch(checked = isPublic, onCheckedChange = { isPublic = it })
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
                                    text = stringResource(R.string.groups_topics),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = ApuBubbleTextColor,
                                )
                                Text(
                                    text = stringResource(R.string.groups_topics_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ApuBubbleMutedColor,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            ApuPremiumSwitch(checked = topics, onCheckedChange = { topics = it })
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
            ApuPremiumContentButton(
                enabled = title.isNotBlank() && !creating,
                onClick = { onCreate(title, about, isPublic, topics, isChannel) },
                style = DiagnosticsActionStyle.PRIMARY,
            ) {
                if (creating) {
                    ApuPremiumSpinner(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = ApuGoldInk,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (creating) stringResource(R.string.groups_creating) else stringResource(R.string.chat_create))
            }
        },
        dismissButton = { ApuTextAction(label = stringResource(R.string.action_cancel), onClick = onDismiss) },
    )
}

@Composable
private fun CommunityTypeChoices(
    isChannel: Boolean,
    onSelectChannel: (Boolean) -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    val options = listOf(
        Triple(false, stringResource(R.string.contacts_group), stringResource(R.string.groups_chats_topics)),
        Triple(true, stringResource(R.string.contacts_channel), stringResource(R.string.groups_posts_comments)),
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
    val notInviteLinkText = stringResource(R.string.groups_not_invite_link)

    ApuSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.groups_join_by_link)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.groups_join_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = ApuBubbleMutedColor,
                )
                ApuBubbleField(
                    value = link,
                    onValueChange = {
                        link = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.chat_invite_link_label)) },
                    placeholder = { Text(stringResource(R.string.groups_link)) },
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
            ApuPremiumContentButton(
                onClick = {
                    // Проверяем, что это приглашение в группу, но отдаём ВСЮ
                    // ссылку: в ней id группы и адрес владельца, без них войти
                    // с другого телефона нельзя.
                    // Короткую ссылку /s/<код> принимаем тоже: что за ней -
                    // узнает репозиторий у сервиса.
                    if (GroupInviteLinks.parseTarget(link) == null && !ShortLinks.isShortLink(link)) {
                        error = notInviteLinkText
                    } else {
                        onSubmit(link.trim())
                    }
                },
                style = DiagnosticsActionStyle.PRIMARY,
            ) { Text(stringResource(R.string.groups_join)) }
        },
        dismissButton = {
            ApuTextAction(label = stringResource(R.string.action_cancel), onClick = onDismiss)
        },
    )
}

/** Заголовок раздела сообществ и сетевого каталога в фирменной капсуле. */
@Composable
private fun DirectoryHeader(title: String) {
    ApuSettingsSectionTitle(title)
}

/**
 * Тот же заголовок, но с числом сообществ в разделе: объём виден сразу, и
 * считать строки глазами не нужно. Ноль (раздел ещё пуст) - обычная капсула
 * без цифры: «0» в заголовке читается как поломка.
 */
@Composable
private fun DirectoryHeaderCounted(title: String, count: Int) {
    if (count <= 0) {
        DirectoryHeader(title)
    } else {
        ApuSettingsSectionTitle(stringResource(R.string.groups_section_count, title, count))
    }
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
            // Владелец 2026-10-07: та же премиальная поверхность, что у «моих»
            // сообществ — списки выглядят едино.
            .apuPremiumLift(5.dp)
            .apuBubbleSurface()
            .apuPremiumThread(inset = 16.dp)
            .apuPremiumGloss(intensity = 0.45f, topFraction = 0.55f)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Аватар со знаком вида: тот же медальон, что у «моих» сообществ.
        Box {
            GroupAvatar(
                groupId = entry.groupId,
                title = entry.title,
                size = 44.dp,
            )
            ApuCommunityKindBadge(
                isChannel = entry.isChannel,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
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
                if (entry.isChannel) stringResource(R.string.contacts_channel) else stringResource(R.string.contacts_group),
                style = MaterialTheme.typography.labelMedium,
                color = ApuBubbleAccentColor,
                maxLines = 1,
            )
            // Как вступают: сразу или по заявке. Значок отвечает на это
            // раньше, чем человек прочитает подпись.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (entry.needsApproval) Icons.Filled.Shield else Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = ApuBubbleMutedColor,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    if (entry.needsApproval) stringResource(R.string.groups_by_request) else stringResource(R.string.groups_direct_join),
                    style = MaterialTheme.typography.bodySmall,
                    color = ApuBubbleMutedColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Действие строки - та же объёмная кнопка APU. Подпись без значка:
        // «Подписаться» и «Вступить» длинные, и значок отнимал бы у названия
        // сообщества место на узких экранах.
        ApuTextAction(
            label = if (entry.isChannel) stringResource(R.string.groups_subscribe) else stringResource(R.string.groups_join_btn),
            onClick = {
                onJoin(
                    GroupInviteLinks.build(
                        slug = entry.slug,
                        groupId = entry.groupId,
                        ownerId = entry.ownerId,
                        isChannel = entry.isChannel,
                        requestApproval = entry.needsApproval,
                        title = entry.title,
                    )
                )
            },
        )
    }
}

/**
 * Пункты меню «⋮» в пузыре группы или канала.
 *
 * Набор тот же, что на главном экране: списки обязаны вести себя одинаково,
 * иначе владелец ищет привычное действие и не находит.
 */
private fun groupMenuActions(
    context: Context,
    group: GroupSummary,
    onOpen: () -> Unit,
    onAdmin: () -> Unit,
    onInvite: () -> Unit,
    onMute: () -> Unit,
    onMarkRead: () -> Unit,
    onLeaveOrDelete: () -> Unit,
): List<BubbleMenuAction> = buildList {
    add(
        BubbleMenuAction(
            title = if (group.isChannel) context.getString(R.string.menu_open_channel) else context.getString(R.string.menu_open_group),
            icon = Icons.Filled.Forum,
            onClick = onOpen,
        )
    )
    add(
        BubbleMenuAction(
            title = if (group.isChannel) context.getString(R.string.menu_invite_channel) else context.getString(R.string.menu_invite_group),
            icon = Icons.Filled.PersonAdd,
            onClick = onInvite,
        )
    )
    val muted = group.mutedUntilMs > System.currentTimeMillis()
    add(
        BubbleMenuAction(
            title = if (muted) context.getString(R.string.menu_unmute) else context.getString(R.string.menu_mute),
            icon = if (muted) Icons.Filled.NotificationsActive else Icons.Filled.NotificationsOff,
            onClick = onMute,
        )
    )
    if (GroupRole.isAdminOrOwner(group.myRole)) {
        add(
            BubbleMenuAction(
                title = context.getString(R.string.menu_manage),
                icon = Icons.Filled.Settings,
                onClick = onAdmin,
            )
        )
    }
    add(
        BubbleMenuAction(
            title = context.getString(R.string.menu_mark_read),
            icon = Icons.Filled.DoneAll,
            onClick = onMarkRead,
        )
    )
    add(
        BubbleMenuAction(
            title = when {
                group.myRole == GroupRole.OWNER ->
                    if (group.isChannel) context.getString(R.string.menu_delete_channel) else context.getString(R.string.menu_delete_group)
                group.isChannel -> context.getString(R.string.menu_unsubscribe)
                else -> context.getString(R.string.action_logout)
            },
            icon = Icons.Filled.Delete,
            destructive = true,
            onClick = onLeaveOrDelete,
        )
    )
}

/** Строка входа в «Мои заявки»: карточка с иконкой и стрелкой, как у других разделов списка. */
@Composable
private fun MyRequestsEntry(title: String, onClick: () -> Unit) {
    ApuSettingsCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ApuPremiumIconTile(icon = Icons.Filled.PersonAdd, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = ApuBubbleTextColor,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = ApuBubbleMutedColor,
            )
        }
    }
}
