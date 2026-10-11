package com.vladimir.messenger.ui.screens.chat

// =============================================================================
// CHATLISTSCREEN.KT — Главный экран со списком чатов
// =============================================================================
// Аналог главного экрана Telegram.
// Показывает список чатов, строку поиска, статус сети.
// FAB для добавления нового контакта.
// =============================================================================

import com.vladimir.messenger.ui.components.ApuPremiumSpinner
import com.vladimir.messenger.ui.components.ApuSearchField
import com.vladimir.messenger.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import com.vladimir.messenger.ui.components.ApuAction
import com.vladimir.messenger.ui.components.ApuBubbleAccentColor
import com.vladimir.messenger.ui.components.ApuBubbleMutedColor
import com.vladimir.messenger.ui.components.ApuBubbleShape
import com.vladimir.messenger.ui.components.ApuBubbleSurfaceColor
import com.vladimir.messenger.ui.components.ApuBubbleTextColor
import com.vladimir.messenger.ui.components.ApuFormTextField
import com.vladimir.messenger.ui.components.ApuPremiumDialog
import com.vladimir.messenger.ui.components.apuBubbleSurface
import com.vladimir.messenger.ui.components.apuPremiumGloss
import com.vladimir.messenger.ui.components.apuPremiumLift
import com.vladimir.messenger.ui.components.apuPremiumThread
import com.vladimir.messenger.ui.components.DiagnosticsActionStyle
import com.vladimir.messenger.ui.components.ApuActionsMenu
import com.vladimir.messenger.ui.components.ApuTabBar
import com.vladimir.messenger.ui.components.Avatar
import com.vladimir.messenger.ui.components.ApuMainTabBar
import com.vladimir.messenger.ui.components.ApuNotificationBadge
import com.vladimir.messenger.ui.components.ApuScrollbar
import com.vladimir.messenger.ui.components.ShareContactChooserDialog
import com.vladimir.messenger.ui.components.SearchOrb
import com.vladimir.messenger.ui.components.ApuTab
import com.vladimir.messenger.ui.components.ApuTabActions
import com.vladimir.messenger.ui.components.ChatWallpaper
import com.vladimir.messenger.ui.components.CoreWarmBar
import com.vladimir.messenger.ui.components.ApuVipBadge
import com.vladimir.messenger.ui.components.RankMedal
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
import com.vladimir.messenger.data.link.ShortShare
import com.vladimir.messenger.ui.components.InviteAttachDialog
import com.vladimir.messenger.ui.components.NotificationMuteDialog
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vladimir.messenger.ui.components.ApuPremiumContentButton
import com.vladimir.messenger.ui.components.ApuPremiumFloatingActionButton
import com.vladimir.messenger.ui.components.ApuActionsMenu
import com.vladimir.messenger.ui.components.ApuAction

private data class NotificationMuteTarget(
    val id: String,
    val title: String,
    val mutedUntilMs: Long,
    val isGroupOrChannel: Boolean,
)

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
    /** Профиль из нижней панели: отдельный экран, не список настроек. */
    onProfileClick: () -> Unit = {},
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
    // Как приглашать: QR при личной встрече или обычная ссылка.
    var inviteChoice by remember { mutableStateOf<InboxGroup?>(null) }
    // Раунд 200: галочка «приложить APK» перед отправкой приглашения.
    var showInviteShare by remember { mutableStateOf(false) }
    var groupLinkShare by remember { mutableStateOf<InboxGroup?>(null) }
    // Раунд 175: «Поделиться контактом» из списка чатов - выбор адресата в APU.
    var shareCardFor by remember { mutableStateOf<com.vladimir.messenger.domain.model.Chat?>(null) }
    var qrInvite by remember { mutableStateOf<Pair<String, String>?>(null) }
    var muteTarget by remember { mutableStateOf<NotificationMuteTarget?>(null) }

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
    // Список разделов может меняться вместе с непрочитанными; не перезапускаем
    // эффект на его изменение, иначе тот же page index будет принят за новый
    // выбор раздела. При этом эффект читает актуальные значения через state.
    val pagerSections by rememberUpdatedState(uiState.sections)
    val selectedSection by rememberUpdatedState(uiState.section)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            pagerSections.getOrNull(page)?.let { section ->
                if (section != selectedSection) viewModel.onSectionSelected(section)
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

    // «Назад» (смахивание от правого края или кнопка) на главном экране не
    // закрывает приложение: «Группы», «Каналы» и «Чаты» - всё та же главная
    // страница, поэтому жест возвращает на вкладку «Все». Приложение
    // закрывается как обычно, только когда уже стоишь на «Все»
    // (владелец, 2026-09-17).
    BackHandler(enabled = pagerState.settledPage != 0) {
        pagerScope.launch { pagerState.animateScrollToPage(0) }
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
                // Раунд 263: пока ядро доподнимается в фоне - тонкая честная
                // полоска, чтобы пустой список не выглядел поломкой.
                CoreWarmBar()

                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        // Прокрутка НЕ должна красить панель: под ней обои APU.
                        scrolledContainerColor = Color.Transparent,
                    ),
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
                                Row(
                                    modifier = Modifier.clickable(onClick = onRankClick),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // У VIP медаль особая (фиолетово-золотая лента
                                    // и кольцо элиты) и рядом со званием стоит
                                    // знак VIP - как награда рядом с именем в
                                    // топовых мессенджерах.
                                    RankMedal(size = 26.dp, vip = uiState.rankVip)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        uiState.rankBadge,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (uiState.rankVip) {
                                        // Полный знак «VIP» вместо одной звезды: у VIP
                                        // в шапке должно быть видно сам статус.
                                        Spacer(Modifier.width(6.dp))
                                        ApuVipBadge()
                                    }
                                }
                            }
                        }
                    },
                    actions = {
                        // Разгрузка панели: частые действия остаются иконками (поиск, скан QR),
                        // остальное уходит в меню «⋮». Заголовок + бейдж ранга помещаются целиком.
                        if (!isSearchVisible) {
                            // Объёмный шарик вместо плоского значка: поиск
                            // здесь главное действие и должен быть заметен.
                            SearchOrb(onClick = { isSearchVisible = true })
                            IconButton(onClick = onScanQrClick) {
                                Icon(Icons.Default.QrCodeScanner, stringResource(R.string.chat_scan_qr))
                            }
                            Box {
                                var menuOpen by remember { mutableStateOf(false) }
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Default.MoreVert, stringResource(R.string.chat_more))
                                }
                                ApuActionsMenu(
                                    expanded = menuOpen,
                                    onDismiss = { menuOpen = false },
                                    // Контакты, Группы, Профиль и Настройки живут в нижней панели,
                                    // «Избранное» — первым контактом в списке чатов.
                                    actions = listOf(
                                        ApuAction(stringResource(R.string.chat_connect_by_link), Icons.Default.Link) {
                                            showConnectDialog = true
                                        },
                                        ApuAction(stringResource(R.string.chat_invite_apu), Icons.Default.PersonAdd) {
                                            showInviteShare = true
                                        },
                                    ),
                                )
                                }
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )

                // Полоска разделов: во всю ширину экрана и пролистывается
                // пальцем вбок - разделов может стать больше, чем влезает.
                // Вкладка «Не прочитано» показывает бейдж с числом чатов с непрочитано >0.
                ApuTabBar(
                    titles = uiState.sections.map { stringResource(it.labelRes) },
                    selectedIndex = pagerState.currentPage,
                    offsetFraction = pagerState.currentPageOffsetFraction,
                    onSelect = { index ->
                        pagerScope.launch { pagerState.animateScrollToPage(index) }
                    },
                    badges = uiState.sections.map { sec ->
                        if (sec == InboxSection.Unread) uiState.unreadCount else 0
                    },
                )
            }
        },
        bottomBar = {
            // Один и тот же пузырь на всех разделах: с главного экрана видно,
            // куда можно уйти, и панель не пропадает после перехода.
            ApuMainTabBar(
                current = ApuTab.Chats,
                actions = ApuTabActions(
                    onContacts = onContactsClick,
                    onGroups = onGroupsClick,
                    onProfile = onProfileClick,
                    onSettings = onSettingsClick,
                ),
            )
        },
        floatingActionButton = {
            // FAB-карандаш: открывает фирменное меню создания чата, группы и канала.
            Box {
                ApuPremiumFloatingActionButton(
                    onClick = { fabMenuExpanded = true },
                    icon = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.chat_create),
                )
                ApuActionsMenu(
                    expanded = fabMenuExpanded,
                    onDismiss = { fabMenuExpanded = false },
                    actions = listOf(
                        ApuAction(
                            title = stringResource(R.string.chat_new_chat),
                            icon = Icons.Default.Person,
                            onClick = onAddContactClick,
                        ),
                        ApuAction(
                            title = stringResource(R.string.chat_new_group),
                            icon = Icons.Default.Group,
                            onClick = onCreateGroupClick,
                        ),
                        ApuAction(
                            title = stringResource(R.string.chat_new_channel),
                            icon = Icons.Default.Campaign,
                            onClick = onCreateChannelClick,
                        ),
                    ),
                )
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            // Плавная листалка: страницы едут за пальцем, в движении видно
            // сразу две вкладки, и чем быстрее движение, тем дальше долистает.
            if (uiState.isLoading) {
                ApuPremiumSpinner(modifier = Modifier.align(Alignment.Center))
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
                        vipPeerIds = uiState.vipPeerIds,
                        onChatClick = onChatClick,
                        onSavedClick = onSavedClick,
                        onAddContactClick = onAddContactClick,
                        onCallClick = onCallClick,
                        onGroupClick = onGroupClick,
                        onGroupAdminClick = onGroupAdminClick,
                        onChannelClick = onChannelClick,
                        onMarkChatRead = viewModel::markChatRead,
                        onMarkGroupRead = viewModel::markGroupRead,
                        onClearChat = { confirmClearChat = it },
                        onShareCard = { shareCardFor = it },
                        onDeleteChat = { confirmDeleteChat = it },
                        onGroupLeaveOrDelete = { confirmGroup = it },
                        onInviteToGroup = { group -> inviteChoice = group },
                        onTogglePersonalPin = viewModel::togglePersonalPin,
                        onToggleGroupPin = viewModel::toggleGroupPin,
                        onTogglePersonalArchive = { chat ->
                            viewModel.setChatArchived(chat.id, !chat.isArchived)
                        },
                        onTogglePersonalMute = { chat ->
                            muteTarget = NotificationMuteTarget(
                                id = chat.id,
                                title = chat.contactName,
                                mutedUntilMs = chat.mutedUntilMs,
                                isGroupOrChannel = false,
                            )
                        },
                        onToggleGroupArchive = { group ->
                            viewModel.setGroupArchived(group.id, !group.isArchived)
                        },
                        onToggleGroupMute = { group ->
                            muteTarget = NotificationMuteTarget(
                                id = group.id,
                                title = group.title,
                                mutedUntilMs = group.mutedUntilMs,
                                isGroupOrChannel = true,
                            )
                        },
                        onLoadMore = viewModel::loadMore,
                    )
                }
            }
        }
    }
    }

    // Раунд 158: группа/канал для рассылки приглашения контактам APU.
    var inviteApu by remember { mutableStateOf<InboxGroup?>(null) }

    // Как приглашать: QR при встрече или ссылка кому угодно.
    // Раунд 175: «Поделиться контактом» - выбор адресата внутри APU.
    shareCardFor?.let { shared ->
        ShareContactChooserDialog(
            sharedName = shared.contactName,
            contacts = viewModel.contacts.collectAsStateWithLifecycle(initialValue = emptyList())
                .value.filter { it.id != shared.contactId },
            onDismiss = { shareCardFor = null },
            onPick = { to ->
                shareCardFor = null
                viewModel.sendContactCard(to.id, to.displayName, shared.contactId, shared.contactName)
            },
        )
    }

    inviteChoice?.let { group ->
        val what = if (group.isChannel) stringResource(R.string.word_channel_acc) else stringResource(R.string.word_group_acc)
        ApuPremiumDialog(
            title = stringResource(R.string.invite_title, what),
            onDismiss = { inviteChoice = null },
            dismissLabel = null,
        ) {
                // Раунд 160: действия - тремя пузырями друг под другом
                // (владелец: «три горизонтальных пузыря ... с нашей
                // цветовой гаммой») вместо сжатых текстовых кнопок.
                Column {
                    Text(
                        stringResource(R.string.grp_qr_hint)
                    )
                    Spacer(Modifier.height(14.dp))
                    InviteActionBubble(stringResource(R.string.groups_show_qr), filled = true) {
                        val chosen = group
                        inviteChoice = null
                        viewModel.prepareQrGroupInvite(chosen.id) { title, link ->
                            qrInvite = title to link
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    InviteActionBubble(stringResource(R.string.groups_send_link), filled = true) {
                        val chosen = group
                        inviteChoice = null
                        groupLinkShare = chosen
                    }
                    Spacer(Modifier.height(8.dp))
                    InviteActionBubble(stringResource(R.string.contacts_send_in_apu), filled = true) {
                        val chosen = group
                        inviteChoice = null
                        inviteApu = chosen
                    }
                }
        }
    }

    // Раунд 158: «Отправить в APU» - выбираем адресатов галочками (до
    // ChatListViewModel.MAX_INVITE_RECIPIENTS), каждому приглашение
    // уходит в личный чат.
    inviteApu?.let { grp ->
        val what = if (grp.isChannel) "канал" else "группу"
        var contacts by remember { mutableStateOf<List<com.vladimir.messenger.domain.model.Chat>?>(null) }
        var selected by remember { mutableStateOf(setOf<String>()) }
        var sending by remember { mutableStateOf(false) }
        // Раунд 159: уже в группе - серым и не выбираются.
        var memberIds by remember { mutableStateOf(setOf<String>()) }
        val maxPick = ChatListViewModel.MAX_INVITE_RECIPIENTS
        LaunchedEffect(grp.id) {
            viewModel.personalChatsOnce { contacts = it }
            viewModel.groupMemberIdsOnce(grp.id) { memberIds = it }
        }
        ApuPremiumDialog(
            title = stringResource(R.string.chat_send_to),
            onDismiss = { if (!sending) inviteApu = null },
            confirmLabel = if (selected.isNotEmpty()) {
                if (sending) stringResource(R.string.chat_sending) else stringResource(R.string.action_send)
            } else {
                null
            },
            confirmEnabled = !sending,
            onConfirm = {
                sending = true
                val ids = selected.toList()
                viewModel.sendGroupInviteToChats(grp.id, what, ids) { sent, failed ->
                    sending = false
                    inviteApu = null
                    android.widget.Toast.makeText(
                        context,
                        if (failed == 0) context.getString(R.string.sent_count, sent) else context.getString(R.string.sent_failed, sent, failed),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            dismissEnabled = !sending,
        ) {
                Column {
                    val list = contacts
                    when {
                        list == null -> Text(stringResource(R.string.action_loading))
                        list.isEmpty() -> Text(stringResource(R.string.chat_no_personal))
                        else -> {
                            Text(
                                stringResource(R.string.selected_of, selected.size, maxPick),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF5A6472),
                            )
                            androidx.compose.foundation.lazy.LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 360.dp),
                                // Раунд 161: пузыри-абоненты с зазором.
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                items(list, key = { it.id }) { c ->
                                    val inGroup = c.contactId.isNotBlank() && c.contactId in memberIds
                                    val isSelected = c.id in selected
                                    // Раунд 161: каждый абонент - свой пузырь;
                                    // выбрали - пузырь золотой (наш стиль).
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(18.dp))
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primary
                                                else Color.White.copy(alpha = 0.85f)
                                            )
                                            .border(
                                                1.dp,
                                                if (isSelected) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                                                RoundedCornerShape(18.dp),
                                            )
                                            .clickable(enabled = !inGroup) {
                                                selected = if (isSelected) {
                                                    selected - c.id
                                                } else if (selected.size < maxPick) {
                                                    selected + c.id
                                                } else {
                                                    selected
                                                }
                                            }
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        // Круглый чек: рамка -> золотая заливка с галочкой.
                                        val checkTint = when {
                                            inGroup -> Color(0xFFE7EAF0)
                                            isSelected -> Color.White
                                            else -> Color.Transparent
                                        }
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    when {
                                                        inGroup -> Color(0xFF9AA3AF)
                                                        isSelected -> MaterialTheme.colorScheme.primary
                                                        else -> Color.Transparent
                                                    }
                                                )
                                                .border(
                                                    1.5.dp,
                                                    when {
                                                        inGroup -> Color(0xFF9AA3AF)
                                                        else -> MaterialTheme.colorScheme.primary
                                                    },
                                                    CircleShape,
                                                ),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (isSelected || inGroup) {
                                                Icon(
                                                    Icons.Filled.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                    tint = checkTint,
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            c.contactName.ifBlank { stringResource(R.string.chat_no_name) },
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = when {
                                                isSelected -> Color.White
                                                inGroup -> Color(0xFF9AA3AF)
                                                else -> Color.Unspecified
                                            },
                                        )
                                        if (inGroup) {
                                            Text(
                                                // Раунд 163: каналу - честное «уже в канале».
                                                if (grp.isChannel) stringResource(R.string.chat_already_channel) else stringResource(R.string.chat_already_group),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFF9AA3AF),
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
        }
    }

    qrInvite?.let { (title, link) ->
        // Премиальный общий вид диалога (стиль «Логов»): золотая капсула
        // заголовка, светлая подложка house style, наши кнопки.
        ApuPremiumDialog(
            title = title,
            onDismiss = { qrInvite = null },
            confirmLabel = stringResource(R.string.action_done),
            onConfirm = { qrInvite = null },
            confirmStyle = DiagnosticsActionStyle.GLASS,
            dismissLabel = null,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.grp_qr_wait),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ApuBubbleTextColor,
                )
                Spacer(Modifier.height(12.dp))
                com.vladimir.messenger.ui.components.InviteShareCard(
                    link = link,
                    displayName = title,
                )
            }
        }
    }

    // Подтверждение удаления чата.
    confirmDeleteChat?.let { chat ->
        ApuPremiumDialog(
            title = stringResource(R.string.chat_delete_q),
            onDismiss = { confirmDeleteChat = null },
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                viewModel.deleteChat(chat.id)
                confirmDeleteChat = null
            },
        ) {
            Text(
                stringResource(R.string.chat_delete_body, chat.contactName),
                style = MaterialTheme.typography.bodyMedium,
                color = ApuBubbleTextColor,
            )
        }
    }

    // Подтверждение очистки переписки.
    confirmClearChat?.let { chat ->
        ApuPremiumDialog(
            title = stringResource(R.string.chat_clear_q),
            onDismiss = { confirmClearChat = null },
            confirmLabel = stringResource(R.string.chat_clear),
            onConfirm = {
                viewModel.clearChatHistory(chat.id)
                confirmClearChat = null
            },
        ) {
            Text(
                stringResource(R.string.chat_clear_body, chat.contactName),
                style = MaterialTheme.typography.bodyMedium,
                color = ApuBubbleTextColor,
            )
        }
    }

    // Подтверждение выхода/удаления группы или канала.
    confirmGroup?.let { group ->
        val owner = group.myRole == GroupRole.OWNER
        val what = if (group.isChannel) stringResource(R.string.word_channel_acc) else stringResource(R.string.word_group_acc)
        ApuPremiumDialog(
            title = when {
                owner -> stringResource(R.string.confirm_delete_title, what)
                group.isChannel -> stringResource(R.string.groups_unsubscribe_channel_q)
                else -> stringResource(R.string.groups_leave_group_q)
            },
            onDismiss = { confirmGroup = null },
            confirmLabel = when {
                owner -> stringResource(R.string.action_delete)
                group.isChannel -> stringResource(R.string.menu_unsubscribe)
                else -> stringResource(R.string.action_logout)
            },
            onConfirm = {
                if (owner) viewModel.deleteGroup(group.id) else viewModel.leaveGroup(group.id)
                confirmGroup = null
            },
        ) {
            Text(
                when {
                    owner && group.isChannel -> stringResource(R.string.grp_del_channel_body, group.title)
                    owner -> stringResource(R.string.grp_del_group_body, group.title)
                    group.isChannel -> stringResource(R.string.grp_unsub_channel_body, group.title)
                    else -> stringResource(R.string.grp_leave_body, group.title)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = ApuBubbleTextColor,
            )
        }
    }

    // Диалог «Мой адрес для подключения» убран вместе с пунктом меню:
    // раздел QR (значок в шапке) показывает свой код, копирует ссылку и
    // делится ею — одно место вместо трёх.

    // Раунд 200: перед отправкой приглашения спрашиваем про APK.
    if (showInviteShare) {
        InviteAttachDialog(
            title = stringResource(R.string.chat_invite_apu),
            onDismiss = { showInviteShare = false },
            onShare = { attach ->
                showInviteShare = false
                val link = runCatching { OwnInvite.link(context) }.getOrNull()
                if (link.isNullOrBlank()) {
                    android.widget.Toast.makeText(
                        context, context.getString(R.string.chat_no_identity),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    ShortShare.shareInvite(
                        context,
                        OwnInvite.displayName(context),
                        link,
                        attach,
                    )
                }
            },
        )
    }
    if (groupLinkShare != null) {
        val chosenGroup = groupLinkShare!!
        InviteAttachDialog(
            title = if (chosenGroup.isChannel) stringResource(R.string.menu_invite_channel) else stringResource(R.string.menu_invite_community),
            onDismiss = { groupLinkShare = null },
            onShare = { attach ->
                groupLinkShare = null
                viewModel.shareGroupInvite(chosenGroup.id) { title, link ->
                    // Раунд 162: каналу - «канал», группе - «группа».
                    com.vladimir.messenger.util.AppShare.shareGroupInvite(
                        context, title, link, chosenGroup.isChannel, attach,
                    )
                }
            },
        )
    }

    muteTarget?.let { target ->
        NotificationMuteDialog(
            targetName = target.title,
            mutedUntilMs = target.mutedUntilMs,
            onSelectUntil = { untilMs ->
                if (target.isGroupOrChannel) {
                    viewModel.setGroupMutedUntil(target.id, untilMs)
                } else {
                    viewModel.setChatMutedUntil(target.id, untilMs)
                }
                muteTarget = null
            },
            onTurnOn = {
                if (target.isGroupOrChannel) {
                    viewModel.setGroupMutedUntil(target.id, 0L)
                } else {
                    viewModel.setChatMutedUntil(target.id, 0L)
                }
                muteTarget = null
            },
            onDismiss = { muteTarget = null },
        )
    }

    // Диалог подключения — отдельная фирменная карточка, а не стандартное
    // Material-окно с серо-лиловыми полями поверх обоев.
    if (showConnectDialog) {
        ApuConnectByLinkDialog(
            link = connectLink,
            onLinkChange = { connectLink = it },
            onDismiss = { showConnectDialog = false },
            onConnect = {
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
            },
        )
    }
}

/**
 * Ввод ссылки-приглашения в фирменной APU-карточке.
 *
 * Диалог сохраняет затемнение фона, но сам использует те же светлые пузыри,
 * золотые акценты и хорошо читаемый текст, что список чатов и формы контактов.
 */
@Composable
private fun ApuConnectByLinkDialog(
    link: String,
    onLinkChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp)
                .padding(horizontal = 20.dp),
            shape = RoundedCornerShape(28.dp),
            color = ApuBubbleSurfaceColor,
            contentColor = ApuBubbleTextColor,
            tonalElevation = 0.dp,
            shadowElevation = 16.dp,
            border = BorderStroke(
                1.dp,
                ApuBubbleAccentColor.copy(alpha = 0.42f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(ApuBubbleAccentColor.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = null,
                            tint = ApuBubbleAccentColor,
                            modifier = Modifier.size(25.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.chat_connect_by_link),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = ApuBubbleTextColor,
                        )
                        Text(
                            text = stringResource(R.string.chat_connect_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = ApuBubbleMutedColor,
                        )
                    }
                }

                ApuFormTextField(
                    value = link,
                    onValueChange = onLinkChange,
                    label = stringResource(R.string.chat_invite_link_label),
                    placeholder = stringResource(R.string.chat_invite_link_placeholder),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = null,
                            tint = ApuBubbleAccentColor,
                        )
                    },
                )

                Text(
                    text = stringResource(R.string.chat_connect_trust_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = ApuBubbleMutedColor,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ApuPremiumContentButton(
                        onClick = onDismiss,
                        style = DiagnosticsActionStyle.QUIET,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.action_cancel), fontWeight = FontWeight.SemiBold)
                    }
                    ApuPremiumContentButton(
                        onClick = onConnect,
                        style = DiagnosticsActionStyle.PRIMARY,
                        enabled = link.isNotBlank(),
                        modifier = Modifier.weight(1.35f),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.chat_connect),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Раунд 160: пузырь-кнопка диалога приглашения в гамме APU: золотая
 * заливка (главное действие) либо светлый пузырь с золотой рамкой.
 */
@Composable
private fun InviteActionBubble(
    label: String,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (filled) MaterialTheme.colorScheme.primary
                else Color.White.copy(alpha = 0.85f)
            )
            .border(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = if (filled) 1f else 0.4f),
                RoundedCornerShape(18.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontWeight = FontWeight.Bold,
            color = if (filled) Color.White else MaterialTheme.colorScheme.primary,
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
    /**
     * Узлы собеседников, сообщивших ранг VIP: у их имён — знак, у аватарки кольцо.
     * Множество приходит сверху, потому что решение живёт в базе и модели, а
     * страница лишь рисует строки.
     */
    vipPeerIds: Set<String>,
    onChatClick: (chatId: String, contactName: String, contactId: String) -> Unit,
    /** «Избранное» - постоянный личный контакт в разделах с личными чатами. */
    onSavedClick: () -> Unit,
    onAddContactClick: () -> Unit,
    onCallClick: (contactId: String, contactName: String) -> Unit,
    onGroupClick: (groupId: String) -> Unit,
    onGroupAdminClick: (groupId: String) -> Unit,
    onChannelClick: (channelId: String) -> Unit,
    onMarkChatRead: (String) -> Unit,
    onMarkGroupRead: (String) -> Unit,
    onClearChat: (com.vladimir.messenger.domain.model.Chat) -> Unit,
    onDeleteChat: (com.vladimir.messenger.domain.model.Chat) -> Unit,
    /** Раунд 175: «Поделиться контактом» - отдать чат наверх для выбора адресата. */
    onShareCard: (com.vladimir.messenger.domain.model.Chat) -> Unit = {},
    onGroupLeaveOrDelete: (InboxGroup) -> Unit,
    /** Позвать людей в группу или канал. */
    onInviteToGroup: (InboxGroup) -> Unit = {},
    /** Pin/unpin conversation rows on the home inbox. */
    onTogglePersonalPin: (com.vladimir.messenger.domain.model.Chat) -> Unit = {},
    onToggleGroupPin: (InboxGroup) -> Unit = {},
    /** р249: архив и «без звука» - личный чат. */
    onTogglePersonalArchive: (com.vladimir.messenger.domain.model.Chat) -> Unit = {},
    onTogglePersonalMute: (com.vladimir.messenger.domain.model.Chat) -> Unit = {},
    /** р249: архив и «без звука» - группа или канал. */
    onToggleGroupArchive: (InboxGroup) -> Unit = {},
    onToggleGroupMute: (InboxGroup) -> Unit = {},
    /** Прокрутка подошла к концу загруженного - пора досыпать страницу. */
    onLoadMore: () -> Unit = {},
) {
    val openAdmin = section == InboxSection.AdminGroups ||
        section == InboxSection.AdminChannels
    // «Избранное» ведёт на отдельное личное хранилище, поэтому логично живёт
    // рядом с личными чатами, но не засоряет списки групп, каналов и архив.
    val showSavedContact = !isSearchActive &&
        (section == InboxSection.All || section == InboxSection.Chats)

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
                        stringResource(R.string.chats_no_channels),
                        textAlign = TextAlign.Center,
                        style     = MaterialTheme.typography.bodyMedium,
                        color     = HintBubbleTextColor,
                    )
                }
            }

            // Вкладка «Не прочитано» пустая — всё прочитано.
            items.isEmpty() && section == InboxSection.Unread && !isSearchActive -> {
                HintBubble(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.DoneAll,
                            contentDescription = null,
                            modifier = Modifier.size(72.dp),
                            tint = HintBubbleMutedColor,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.chat_no_unread),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = HintBubbleTextColor,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.chat_unread_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = HintBubbleMutedColor,
                        )
                    }
                }
            }

            // В разделе групп пусто - подсказка должна быть про группы, а не
            // про чаты и контакты: иначе человек ищет не там.
            items.isEmpty() && !isSearchActive &&
                (section == InboxSection.Groups || section == InboxSection.AdminGroups) -> {
                HintBubble(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Groups,
                            contentDescription = null,
                            modifier = Modifier.size(72.dp),
                            tint = HintBubbleMutedColor,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            if (section == InboxSection.AdminGroups) {
                                stringResource(R.string.chat_no_groups_created)
                            } else {
                                stringResource(R.string.chat_no_groups)
                            },
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = HintBubbleTextColor,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.chats_create_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = HintBubbleMutedColor,
                        )
                    }
                }
            }

            // Даже на новом аккаунте «Избранное» доступно как первый личный
            // контакт: туда можно сразу складывать заметки и файлы.
            items.isEmpty() && showSavedContact -> {
                SavedContactCard(
                    onClick = onSavedClick,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
                EmptyChatList(
                    isSearchActive = false,
                    onAddContact = onAddContactClick,
                    modifier = Modifier.align(Alignment.Center),
                )
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
                    // Снизу - запас под плавающий «+»: иначе он закрывает
                    // последний чат (как было в ленте канала и в избранном).
                    contentPadding = PaddingValues(top = 4.dp, bottom = 88.dp),
                ) {
                    if (showSavedContact) {
                        item(key = "saved-contact") {
                            SavedContactCard(onClick = onSavedClick)
                        }
                    }
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
                                showPinnedIndicator = true,
                                // Знак VIP у имени собеседника: он сам сообщил
                                // ранг конвертом APURANK1 (см. PeerRankRouter).
                                peerVip = item.chat.contactId.lowercase() in vipPeerIds,
                                onClick = {
                                    onChatClick(
                                        item.chat.id,
                                        item.chat.contactName,
                                        item.chat.contactId,
                                    )
                                },
                                menuActions = listOf(
                                    BubbleMenuAction(
                                        title = if (item.chat.isPinned) stringResource(R.string.menu_unpin) else stringResource(R.string.menu_pin),
                                        icon = Icons.Filled.PushPin,
                                        onClick = { onTogglePersonalPin(item.chat) },
                                    ),
                                    BubbleMenuAction(
                                        title = stringResource(R.string.menu_open_chat),
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
                                        title = stringResource(R.string.menu_call),
                                        icon = Icons.Default.Call,
                                        onClick = {
                                            onCallClick(
                                                item.chat.contactId,
                                                item.chat.contactName,
                                            )
                                        },
                                    ),
                                    BubbleMenuAction(
                                        title = stringResource(R.string.menu_share_contact),
                                        icon = Icons.Default.Share,
                                        onClick = { onShareCard(item.chat) },
                                    ),
                                    // р249: архив и «без звука». Оба состояния
                                    // уезжают на второе устройство кадром
                                    // зеркала, поэтому пункт показывает то,
                                    // что будет после нажатия.
                                    BubbleMenuAction(
                                        title = if (item.chat.isArchived) stringResource(R.string.menu_unarchive) else stringResource(R.string.menu_archive),
                                        icon = Icons.Default.Archive,
                                        onClick = { onTogglePersonalArchive(item.chat) },
                                    ),
                                    BubbleMenuAction(
                                        title = if (item.chat.isMuted()) stringResource(R.string.menu_unmute) else stringResource(R.string.menu_mute),
                                        icon = if (item.chat.isMuted()) {
                                            Icons.Default.NotificationsActive
                                        } else {
                                            Icons.Default.NotificationsOff
                                        },
                                        onClick = { onTogglePersonalMute(item.chat) },
                                    ),
                                    BubbleMenuAction(
                                        title = stringResource(R.string.menu_mark_read),
                                        icon = Icons.Default.DoneAll,
                                        onClick = { onMarkChatRead(item.chat.id) },
                                    ),
                                    BubbleMenuAction(
                                        title = stringResource(R.string.menu_clear_chat),
                                        icon = Icons.Default.CleaningServices,
                                        onClick = { onClearChat(item.chat) },
                                    ),
                                    BubbleMenuAction(
                                        title = stringResource(R.string.menu_delete_chat),
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
                                            title = if (item.group.isPinned) stringResource(R.string.menu_unpin) else stringResource(R.string.menu_pin),
                                            icon = Icons.Filled.PushPin,
                                            onClick = { onToggleGroupPin(item.group) },
                                        )
                                    )
                                    // р249: архив и «без звука» - решение
                                    // личное, участникам не рассылается.
                                    add(
                                        BubbleMenuAction(
                                            title = if (item.group.isArchived) stringResource(R.string.menu_unarchive) else stringResource(R.string.menu_archive),
                                            icon = Icons.Default.Archive,
                                            onClick = { onToggleGroupArchive(item.group) },
                                        )
                                    )
                                    add(
                                        BubbleMenuAction(
                                            title = if (item.group.isMuted()) stringResource(R.string.menu_unmute) else stringResource(R.string.menu_mute),
                                            icon = if (item.group.isMuted()) {
                                                Icons.Default.NotificationsActive
                                            } else {
                                                Icons.Default.NotificationsOff
                                            },
                                            onClick = { onToggleGroupMute(item.group) },
                                        )
                                    )
                                    add(
                                        BubbleMenuAction(
                                            title = if (item.group.isChannel) stringResource(R.string.menu_open_channel) else stringResource(R.string.menu_open_group),
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
                                    add(
                                        BubbleMenuAction(
                                            title = if (item.group.isChannel) {
                                                stringResource(R.string.menu_invite_channel)
                                            } else {
                                                stringResource(R.string.menu_invite_community)
                                            },
                                            icon = Icons.Default.PersonAdd,
                                            onClick = { onInviteToGroup(item.group) },
                                        )
                                    )
                                    if (item.group.myRole == GroupRole.OWNER ||
                                        item.group.myRole == GroupRole.ADMIN
                                    ) {
                                        add(
                                            BubbleMenuAction(
                                                title = stringResource(R.string.menu_manage),
                                                icon = Icons.Default.Settings,
                                                onClick = { onGroupAdminClick(item.group.id) },
                                            )
                                        )
                                    }
                                    add(
                                        BubbleMenuAction(
                                            title = stringResource(R.string.menu_mark_read),
                                            icon = Icons.Default.DoneAll,
                                            onClick = { onMarkGroupRead(item.group.id) },
                                        )
                                    )
                                    add(
                                        BubbleMenuAction(
                                            title = when {
                                                item.group.myRole == GroupRole.OWNER ->
                                                    if (item.group.isChannel) stringResource(R.string.menu_delete_channel) else stringResource(R.string.menu_delete_group)
                                                item.group.isChannel -> stringResource(R.string.menu_unsubscribe)
                                                else -> stringResource(R.string.action_logout)
                                            },
                                            icon = Icons.Default.Delete,
                                            destructive = true,
                                            onClick = { onGroupLeaveOrDelete(item.group) },
                                        )
                                    )
                                },
                                openAdmin = openAdmin,
                                onClick = {
                                    // Нажатие ВСЕГДА открывает саму группу или
                                    // канал, даже в разделах «Админ». Раньше
                                    // оттуда попадали сразу в управление, хотя
                                    // чаще нужно просто зайти и почитать;
                                    // кабинет остаётся в меню «...» и внутри.
                                    if (item.group.isChannel) {
                                        onChannelClick(item.group.id)
                                    } else {
                                        onGroupClick(item.group.id)
                                    }
                                },
                            )
                        }
                    }
                }
                // Бегунок справа: показывает, где мы в длинном списке.
                ApuScrollbar(state = listState)
            }
        }
    }
}

// Поле поиска в TopAppBar: общее премиальное поле поиска APU.
@Composable
private fun SearchTextField(
    query: String,
    onQueryChanged: (String) -> Unit,
    onClose: () -> Unit,
) {
    ApuSearchField(
        value = query,
        onValueChange = onQueryChanged,
        placeholder = stringResource(R.string.chat_search_placeholder),
        modifier = Modifier.fillMaxWidth(),
        trailing = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, stringResource(R.string.chat_close_search))
            }
        },
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
                stringResource(R.string.chat_nothing_found),
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
                stringResource(R.string.chat_no_chats),
                style     = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color     = HintBubbleTextColor,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.chat_add_contact_hint),
                style     = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color     = HintBubbleMutedColor,
            )
            Spacer(modifier = Modifier.height(24.dp))
            ApuPremiumContentButton(
                onClick = onAddContact,
                style = DiagnosticsActionStyle.PRIMARY,
            ) {
                Icon(Icons.Default.PersonAdd, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.chat_add_contact))
            }
        }
    }

}

/**
 * «Избранное» - не сетевой собеседник, а личное хранилище. В главном списке
 * выглядит как отдельный контакт, чтобы открыть заметки/файлы можно было тем
 * же привычным тапом, что и обычную переписку.
 */
@Composable
private fun SavedContactCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            // Владелец 2026-10-07: строки списка — в том же премиальном стиле,
            // что шапки, пузыри и диалоги: подъём, единая подложка, золотая
            // нить по кромке и глянец ПОД содержимым.
            .apuPremiumLift(5.dp)
            .apuBubbleSurface()
            .apuPremiumThread(inset = 16.dp)
            .apuPremiumGloss(intensity = 0.45f, topFraction = 0.55f)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Bookmark,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.chat_saved),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF1E2430),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.chat_saved_subtitle),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.chat_saved_hint),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF5A6472),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
    // Разбор картинки - в фоне и один раз на строку base64 (AvatarBitmaps).
    val groupAvatarBitmap = com.vladimir.messenger.ui.components.AvatarBitmaps
        .rememberAvatar(storeAvatars["g:" + group.id])
    // Раунд 255: недописанный текст поля ввода виден прямо в пузыре списка.
    val drafts by com.vladimir.messenger.data.draft.DraftStore.drafts.collectAsState()
    val draftText = drafts[
        com.vladimir.messenger.data.draft.DraftStore.groupKey(group.id)
    ].orEmpty()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            // Владелец 2026-10-07: строки списка — в том же премиальном стиле,
            // что шапки, пузыри и диалоги.
            .apuPremiumLift(5.dp)
            .apuBubbleSurface()
            .apuPremiumThread(inset = 16.dp)
            .apuPremiumGloss(intensity = 0.45f, topFraction = 0.55f)
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
                        text = if (group.myRole == GroupRole.OWNER) stringResource(R.string.chat_role_owner) else stringResource(R.string.chat_role_admin),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // Подпись пузыря — как у личных чатов: сразу видно, что это.
            Text(
                text = if (group.isChannel) stringResource(BubbleKind.Channel.labelRes) else stringResource(BubbleKind.Group.labelRes),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF5A6472),
                maxLines = 1,
            )
            if (draftText.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.contact_draft_prefix, draftText),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFC62828),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    // Раунд 155: без служебных строк (гифки/стикеры).
                    text = com.vladimir.messenger.util.ChatPreviews.human(group.preview)
                        ?: if (group.isPublic) {
                        stringResource(R.string.group_public_members, group.memberCount)
                    } else {
                        stringResource(R.string.group_private_members, group.memberCount)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF5A6472),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (group.timeMs != null) {
                    Text(
                        // Раунд 184 (аудит-5): кэш вместо нового SimpleDateFormat
                        // на каждую перерисовку строки группы.
                        text = remember(group.timeMs, System.currentTimeMillis() / 3_600_000L) {
                            formatGroupTime(group.timeMs)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF5A6472),
                    )
                }
                if (group.isPinned) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Filled.PushPin,
                        contentDescription = stringResource(R.string.chat_pinned_desc),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            ApuNotificationBadge(group.unreadCount)
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
