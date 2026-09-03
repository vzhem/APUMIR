package com.vladimir.messenger.ui.screens.chat

// =============================================================================
// CHATLISTSCREEN.KT — Главный экран со списком чатов
// =============================================================================
// Аналог главного экрана Telegram.
// Показывает список чатов, строку поиска, статус сети.
// FAB для добавления нового контакта.
// =============================================================================

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.vladimir.messenger.ui.theme.AvatarStore
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vladimir.messenger.ui.components.ApuTabBar
import com.vladimir.messenger.ui.components.Avatar
import com.vladimir.messenger.ui.components.ChatWallpaper
import com.vladimir.messenger.data.group.GroupRole
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.ui.components.BubbleKind
import com.vladimir.messenger.ui.components.BubbleMenuAction
import com.vladimir.messenger.ui.components.BubbleOverflowMenu
import com.vladimir.messenger.ui.components.ContactCard
import com.vladimir.messenger.ui.components.HintBubble
import com.vladimir.messenger.ui.components.HintBubbleMutedColor
import com.vladimir.messenger.ui.components.HintBubbleTextColor
import com.vladimir.messenger.ui.components.NetworkStatusBar
import com.vladimir.messenger.ui.components.ShimmerIcon
import com.vladimir.messenger.data.RustBridge
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.ui.platform.LocalContext
import com.vladimir.messenger.ui.components.InviteShareCard
import com.vladimir.messenger.util.OwnInvite

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    onChatClick: (chatId: String, contactName: String, contactId: String) -> Unit,
    onAddContactClick: () -> Unit,
    onCreateGroupClick: () -> Unit = {},
    onCreateChannelClick: () -> Unit = {},
    onSettingsClick: () -> Unit,
    onScanQrClick: () -> Unit = {},
    onShowMyQrClick: () -> Unit = {},
    onRankClick: () -> Unit = {},
    onSavedClick: () -> Unit = {},
    onContactsClick: () -> Unit = {},
    onGroupsClick: () -> Unit = {},
    onGroupClick: (groupId: String) -> Unit = {},
    onGroupAdminClick: (groupId: String) -> Unit = {},
    onChannelClick: (channelId: String) -> Unit = {},
    onCallClick: (contactId: String, contactName: String) -> Unit = { _, _ -> },
    viewModel: ChatListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var isSearchVisible by remember { mutableStateOf(false) }

    var showConnectDialog by remember { mutableStateOf(false) }
    var connectLink by remember { mutableStateOf("") }
    val context = LocalContext.current

    // Меню создания у кнопки-карандаша: чат, группа, канал.
    var fabMenuExpanded by remember { mutableStateOf(false) }

    // Подтверждения опасных действий из меню «⋮» в пузырях.
    var confirmDeleteChat by remember { mutableStateOf<com.vladimir.messenger.domain.model.Chat?>(null) }
    var confirmClearChat by remember { mutableStateOf<com.vladimir.messenger.domain.model.Chat?>(null) }
    var confirmGroup by remember { mutableStateOf<InboxGroup?>(null) }

    // Листалка разделов. Страницы едут за пальцем, поэтому выбранный раздел и
    // страница обязаны ходить парой: тап по чипсу листает страницу, а
    // остановившаяся страница выбирает раздел.
    val sectionCount = uiState.sections.size.coerceAtLeast(1)
    val pagerState = rememberPagerState(
        initialPage = uiState.sections.indexOf(uiState.section).coerceAtLeast(0),
        pageCount = { sectionCount },
    )
    // Листание запускается из обработчика нажатия, а НЕ из LaunchedEffect по
    // разделу. Раньше на середине анимации страница успевала смениться, это
    // меняло раздел, LaunchedEffect перезапускался и отменял свою же
    // анимацию - метка застревала между вкладками.
    val pagerScope = rememberCoroutineScope()
    // Раздел выбирает только ОСТАНОВИВШАЯСЯ страница (settledPage): пока палец
    // ведёт, промежуточные значения не должны трогать состояние экрана.
    LaunchedEffect(pagerState, uiState.sections) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            uiState.sections.getOrNull(page)?.let { section ->
                if (section != uiState.section) viewModel.onSectionSelected(section)
            }
        }
    }
    // Раздел могли сменить не жестом (например, экран открылся заново) -
    // подтягиваем страницу, но только когда листалка стоит.
    LaunchedEffect(uiState.section, uiState.sections) {
        val target = uiState.sections.indexOf(uiState.section)
        if (target >= 0 && target != pagerState.currentPage && !pagerState.isScrollInProgress) {
            pagerState.scrollToPage(target)
        }
    }

    // Подложка на весь экран, в том числе под верхней панелью.
    Box(modifier = Modifier.fillMaxSize()) {
        ChatWallpaper()
        Scaffold(
        containerColor = Color.Transparent,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                // Полоска статуса сети (появляется только при проблемах)
                NetworkStatusBar(status = uiState.networkStatus)

                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
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
                            // Бейдж ранга обычным Text: AssistChip (рамка, отступы,
                            // минимальная высота) не влезал в высоту TopAppBar и
                            // сжимал текст до обрезанной последней буквы.
                            // Слово «Сообщения» убрано по просьбе владельца:
                            // заголовок - это сразу бейдж ранга, тап по нему
                            // открывает, что уже доступно и как расти дальше.
                            if (uiState.rankBadge.isNotBlank()) {
                                Text(
                                    uiState.rankBadge,
                                    modifier = Modifier.clickable(onClick = onRankClick),
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
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
                                    // Скруглённый пузырь и чуть больше воздуха:
                                    // меню в одном стиле с остальными пузырями APU.
                                    shape = RoundedCornerShape(20.dp),
                                    tonalElevation = 3.dp,
                                    shadowElevation = 8.dp,
                                ) {
                                    // «Мой QR-код» и «Поделиться приглашением»
                                    // убраны: раздел QR (значок в шапке) уже
                                    // показывает свой код, копирует ссылку и
                                    // делится ею. Три пункта на одно действие
                                    // только запутывали.
                                    DropdownMenuItem(
                                        text = { Text("Контакты") },
                                        leadingIcon = { ShimmerIcon(Icons.Default.People) },
                                        onClick = {
                                            menuOpen = false
                                            onContactsClick()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Группы") },
                                        leadingIcon = { ShimmerIcon(Icons.Filled.Groups) },
                                        onClick = {
                                            menuOpen = false
                                            onGroupsClick()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Избранное") },
                                        leadingIcon = { ShimmerIcon(Icons.Default.Bookmark) },
                                        onClick = {
                                            menuOpen = false
                                            onSavedClick()
                                        },
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Подключиться по ссылке") },
                                        leadingIcon = { ShimmerIcon(Icons.Default.Link) },
                                        onClick = {
                                            menuOpen = false
                                            showConnectDialog = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Настройки") },
                                        leadingIcon = { ShimmerIcon(Icons.Default.Settings) },
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

                // Полоска разделов: во всю ширину экрана и пролистывается
                // пальцем вбок - разделов может стать больше, чем влезает.
                ApuTabBar(
                    titles = uiState.sections.map { it.title },
                    selectedIndex = pagerState.currentPage,
                    offsetFraction = pagerState.currentPageOffsetFraction,
                    onSelect = { index ->
                        pagerScope.launch { pagerState.animateScrollToPage(index) }
                    },
                )
            }
        },
        floatingActionButton = {
            // FAB-карандаш: открывает меню создания чата, группы и канала.
            FloatingActionButton(
                onClick = { fabMenuExpanded = true },
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Создать",
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
            DropdownMenu(
                expanded = fabMenuExpanded,
                onDismissRequest = { fabMenuExpanded = false },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 96.dp),
            ) {
                DropdownMenuItem(
                    text = { Text("Новый чат") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    onClick = {
                        fabMenuExpanded = false
                        onAddContactClick()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Новая группа") },
                    leadingIcon = { Icon(Icons.Default.Group, contentDescription = null) },
                    onClick = {
                        fabMenuExpanded = false
                        onCreateGroupClick()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Новый канал") },
                    leadingIcon = { Icon(Icons.Default.Campaign, contentDescription = null) },
                    onClick = {
                        fabMenuExpanded = false
                        onCreateChannelClick()
                    },
                )
            }
            // Плавная листалка: страницы едут за пальцем, в движении видно
            // сразу две вкладки, и чем быстрее движение, тем дальше долистает.
            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    key = { page -> uiState.sections.getOrNull(page)?.name ?: page.toString() },
                    // Пролистываем по одной вкладке за жест, как в Телеграме.
                    pageSize = PageSize.Fill,
                    beyondViewportPageCount = 1,
                ) { page ->
                    val section = uiState.sections.getOrNull(page) ?: return@HorizontalPager
                    SectionPage(
                        section = section,
                        // Готовый список раздела, посчитанный в фоне. Раньше
                        // здесь шёл пересчёт прямо во время отрисовки - при
                        // сотнях чатов это подвешивало жест листания.
                        items = uiState.itemsBySection[section].orEmpty(),
                        isSearchActive = uiState.searchQuery.isNotEmpty(),
                        onChatClick = onChatClick,
                        onAddContactClick = onAddContactClick,
                        onCallClick = onCallClick,
                        onGroupClick = onGroupClick,
                        onGroupAdminClick = onGroupAdminClick,
                        onChannelClick = onChannelClick,
                        onMarkChatRead = viewModel::markChatRead,
                        onMarkGroupRead = viewModel::markGroupRead,
                        onClearChat = { confirmClearChat = it },
                        onDeleteChat = { confirmDeleteChat = it },
                        onGroupLeaveOrDelete = { confirmGroup = it },
                        onLoadMore = viewModel::loadMore,
                    )
                }
            }
        }
    }
    }

    // Подтверждение удаления чата.
    confirmDeleteChat?.let { chat ->
        AlertDialog(
            onDismissRequest = { confirmDeleteChat = null },
            title = { Text("Удалить чат?") },
            text = { Text("Чат с «${chat.contactName}» и вся переписка будут удалены на этом телефоне.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteChat(chat.id)
                    confirmDeleteChat = null
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteChat = null }) { Text("Отмена") }
            },
        )
    }

    // Подтверждение очистки переписки.
    confirmClearChat?.let { chat ->
        AlertDialog(
            onDismissRequest = { confirmClearChat = null },
            title = { Text("Очистить переписку?") },
            text = { Text("Сообщения чата с «${chat.contactName}» будут удалены, сам чат останется.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearChatHistory(chat.id)
                    confirmClearChat = null
                }) { Text("Очистить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearChat = null }) { Text("Отмена") }
            },
        )
    }

    // Подтверждение выхода/удаления группы или канала.
    confirmGroup?.let { group ->
        val owner = group.myRole == GroupRole.OWNER
        val what = if (group.isChannel) "канал" else "группу"
        AlertDialog(
            onDismissRequest = { confirmGroup = null },
            title = { Text(if (owner) "Удалить $what?" else "Выйти из $what?") },
            text = {
                Text(
                    if (owner) {
                        "«${group.title}» будет удалён у всех участников."
                    } else {
                        "Вы перестанете получать сообщения «${group.title}»."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (owner) viewModel.deleteGroup(group.id) else viewModel.leaveGroup(group.id)
                    confirmGroup = null
                }) { Text(if (owner) "Удалить" else "Выйти") }
            },
            dismissButton = {
                TextButton(onClick = { confirmGroup = null }) { Text("Отмена") }
            },
        )
    }

    // Диалог «Мой адрес для подключения» убран вместе с пунктом меню:
    // раздел QR (значок в шапке) показывает свой код, копирует ссылку и
    // делится ею — одно место вместо трёх.

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
                        // Подключение идёт в ядро по сети: на главном потоке
                        // это задерживало бы отрисовку, поэтому в фон.
                        val link = connectLink
                        pagerScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            RustBridge.connectViaInvite(link)
                        }
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

/**
 * Одна страница листалки: список выбранного раздела либо объяснение пустоты.
 *
 * Вынесена отдельно, потому что во время движения пальцем на экране живут сразу
 * две страницы, и каждая рисует свой раздел независимо от выбранного.
 */
@Composable
private fun SectionPage(
    section: InboxSection,
    items: List<InboxItem>,
    isSearchActive: Boolean,
    onChatClick: (chatId: String, contactName: String, contactId: String) -> Unit,
    onAddContactClick: () -> Unit,
    onCallClick: (contactId: String, contactName: String) -> Unit,
    onGroupClick: (groupId: String) -> Unit,
    onGroupAdminClick: (groupId: String) -> Unit,
    onChannelClick: (channelId: String) -> Unit,
    onMarkChatRead: (String) -> Unit,
    onMarkGroupRead: (String) -> Unit,
    onClearChat: (com.vladimir.messenger.domain.model.Chat) -> Unit,
    onDeleteChat: (com.vladimir.messenger.domain.model.Chat) -> Unit,
    onGroupLeaveOrDelete: (InboxGroup) -> Unit,
    /** Прокрутка подошла к концу загруженного - пора досыпать страницу. */
    onLoadMore: () -> Unit = {},
) {
    val openAdmin = section == InboxSection.AdminGroups ||
        section == InboxSection.AdminChannels

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            // В разделе каналов пусто - объясняем, где их взять.
            items.isEmpty() && section == InboxSection.Channels -> {
                HintBubble(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                ) {
                    Text(
                        "Каналов пока нет. Создайте свой в разделе «Группы» " +
                            "(кнопка «+», переключатель «Это канал») или войдите по ссылке.",
                        textAlign = TextAlign.Center,
                        style     = MaterialTheme.typography.bodyMedium,
                        color     = HintBubbleTextColor,
                    )
                }
            }

            items.isEmpty() -> {
                EmptyChatList(
                    isSearchActive = isSearchActive,
                    onAddContact   = onAddContactClick,
                    modifier       = Modifier.align(Alignment.Center),
                )
            }

            else -> {
                val listState = rememberLazyListState()
                // Догрузка по прокрутке: как только до конца загруженного
                // остаётся меньше страницы, просим следующую. snapshotFlow
                // не дёргает пересборку экрана на каждый сдвиг пальца.
                LaunchedEffect(listState, items.size) {
                    snapshotFlow {
                        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                        last >= items.size - LOAD_MORE_THRESHOLD
                    }
                        .distinctUntilChanged()
                        .collect { near -> if (near && items.isNotEmpty()) onLoadMore() }
                }

                LazyColumn(
                    state          = listState,
                    modifier       = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(
                        items = items,
                        key   = { item ->
                            when (item) {
                                is InboxItem.Personal -> "chat:" + item.chat.id
                                is InboxItem.Group -> "group:" + item.group.id
                            }
                        },
                    ) { item ->
                        when (item) {
                            is InboxItem.Personal -> ContactCard(
                                chat    = item.chat,
                                kind    = BubbleKind.Personal,
                                onClick = {
                                    onChatClick(
                                        item.chat.id,
                                        item.chat.contactName,
                                        item.chat.contactId,
                                    )
                                },
                                menuActions = listOf(
                                    BubbleMenuAction(
                                        title = "Открыть чат",
                                        icon = Icons.Default.Forum,
                                        onClick = {
                                            onChatClick(
                                                item.chat.id,
                                                item.chat.contactName,
                                                item.chat.contactId,
                                            )
                                        },
                                    ),
                                    BubbleMenuAction(
                                        title = "Позвонить",
                                        icon = Icons.Default.Call,
                                        onClick = {
                                            onCallClick(
                                                item.chat.contactId,
                                                item.chat.contactName,
                                            )
                                        },
                                    ),
                                    BubbleMenuAction(
                                        title = "Отметить прочитанным",
                                        icon = Icons.Default.DoneAll,
                                        onClick = { onMarkChatRead(item.chat.id) },
                                    ),
                                    BubbleMenuAction(
                                        title = "Очистить переписку",
                                        icon = Icons.Default.CleaningServices,
                                        onClick = { onClearChat(item.chat) },
                                    ),
                                    BubbleMenuAction(
                                        title = "Удалить чат",
                                        icon = Icons.Default.Delete,
                                        destructive = true,
                                        onClick = { onDeleteChat(item.chat) },
                                    ),
                                ),
                            )

                            // В админ-разделах тап открывает сразу админ-кабинет.
                            is InboxItem.Group -> GroupCard(
                                group   = item.group,
                                menuActions = buildList {
                                    add(
                                        BubbleMenuAction(
                                            title = if (item.group.isChannel) "Открыть канал" else "Открыть группу",
                                            icon = Icons.Default.Forum,
                                            onClick = {
                                                if (item.group.isChannel) {
                                                    onChannelClick(item.group.id)
                                                } else {
                                                    onGroupClick(item.group.id)
                                                }
                                            },
                                        )
                                    )
                                    if (item.group.myRole == GroupRole.OWNER ||
                                        item.group.myRole == GroupRole.ADMIN
                                    ) {
                                        add(
                                            BubbleMenuAction(
                                                title = "Управление",
                                                icon = Icons.Default.Settings,
                                                onClick = { onGroupAdminClick(item.group.id) },
                                            )
                                        )
                                    }
                                    add(
                                        BubbleMenuAction(
                                            title = "Отметить прочитанным",
                                            icon = Icons.Default.DoneAll,
                                            onClick = { onMarkGroupRead(item.group.id) },
                                        )
                                    )
                                    add(
                                        BubbleMenuAction(
                                            title = if (item.group.myRole == GroupRole.OWNER) {
                                                if (item.group.isChannel) "Удалить канал" else "Удалить группу"
                                            } else {
                                                "Выйти"
                                            },
                                            icon = Icons.Default.Delete,
                                            destructive = true,
                                            onClick = { onGroupLeaveOrDelete(item.group) },
                                        )
                                    )
                                },
                                openAdmin = openAdmin,
                                onClick = {
                                    when {
                                        openAdmin -> onGroupAdminClick(item.group.id)
                                        item.group.isChannel -> onChannelClick(item.group.id)
                                        else -> onGroupClick(item.group.id)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
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
    // Раунд 48: пустое состояние тоже в пузыре HintBubble - на обоях и в ночной
    // теме текст цвета onSurfaceVariant читался плохо.
    HintBubble(
        modifier = modifier.padding(32.dp),
    ) {
        if (isSearchActive) {
            Icon(
                Icons.Default.SearchOff,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint     = HintBubbleMutedColor,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Ничего не найдено",
                style = MaterialTheme.typography.titleMedium,
                color = HintBubbleTextColor,
            )
        } else {
            Icon(
                Icons.Default.Forum,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint     = HintBubbleMutedColor,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                "Нет чатов",
                style     = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color     = HintBubbleTextColor,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Добавьте контакт через QR-код или пригласительную ссылку",
                style     = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color     = HintBubbleMutedColor,
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

// =============================================================================
// ПОЛОСКА РАЗДЕЛОВ И СТРОКА ГРУППЫ
// =============================================================================

/** Строка группы в общем списке: аватар, название, предпросмотр, счётчик. */
@Composable
private fun GroupCard(
    group: InboxGroup,
    openAdmin: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    menuActions: List<BubbleMenuAction> = emptyList(),
) {
    // Раунд 42: светлая полосочка со скруглениями и тонкой золотой рамкой -
    // тёмный текст виден на любой подложке и в день, и в ночь.
    val storeAvatars by AvatarStore.avatars.collectAsState()
    val groupAvatarBitmap = remember(storeAvatars["g:" + group.id]) {
        storeAvatars["g:" + group.id]?.let { b64 ->
            try {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                null
            }
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFF5F7FA).copy(alpha = 0.92f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                shape = RoundedCornerShape(18.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (groupAvatarBitmap != null) {
            Image(
                bitmap = groupAvatarBitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(52.dp).clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Avatar(name = group.title, modifier = Modifier.size(52.dp))
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1E2430),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // Пометка, что это своя группа: в разделе «Админ группы» тап
                // открывает админ-кабинет.
                if (openAdmin) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (group.myRole == GroupRole.OWNER) "владелец" else "админ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // Подпись пузыря — как у личных чатов: сразу видно, что это.
            Text(
                text = if (group.isChannel) BubbleKind.Channel.label else BubbleKind.Group.label,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF8A93A2),
                maxLines = 1,
            )
            Text(
                text = group.preview ?: if (group.isPublic) {
                    "Публичная группа - ${group.memberCount} уч."
                } else {
                    "Частная группа - ${group.memberCount} уч."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF5A6472),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(horizontalAlignment = Alignment.End) {
            if (group.timeMs != null) {
                Text(
                        text = formatGroupTime(group.timeMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF5A6472),
                )
            }
            if (group.unreadCount > 0) {
                Box(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (group.unreadCount > 99) "99+" else group.unreadCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }

        BubbleOverflowMenu(actions = menuActions)
    }
}

/** Время строки группы: сегодня - часы и минуты, раньше - дата. */
private fun formatGroupTime(timestamp: Long): String {
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return if (timestamp > today.timeInMillis) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    } else {
        SimpleDateFormat("dd.MM", Locale.getDefault()).format(Date(timestamp))
    }
}

/**
 * За сколько строк до конца загруженного просить следующую страницу.
 * Запас нужен, чтобы список догрузился ДО того, как человек упрётся в конец.
 */
private const val LOAD_MORE_THRESHOLD = 10
