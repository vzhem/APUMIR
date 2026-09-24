package com.vladimir.messenger.ui.screens.groups

// =============================================================================
// GROUPCHATSCREEN.KT — чат группы: слева значки групп, справа темы пузырями
// =============================================================================
// Раскладка по требованию владельца (2026-08-31, как в мессенджере со
// скриншота): после входа в группу слева остаётся вертикальная колонка
// значков всех групп и каналов с бейджами непрочитанных, а справа темы
// выбранной группы идут вертикальным списком, каждая в своём пузыре,
// и у каждой — бейдж непрочитанных. Нажатие на тему открывает ленту.

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import com.vladimir.messenger.ui.components.swipeBack
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import coil.compose.AsyncImage
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Search
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import com.vladimir.messenger.util.GroupFileMarker
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.data.group.GroupSummary
import com.vladimir.messenger.data.group.TopicSummary
import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.ui.components.AnimatedTopicIcon
import com.vladimir.messenger.ui.components.ChatWallpaper
import com.vladimir.messenger.ui.components.FileCardState
import com.vladimir.messenger.ui.components.GifCatalogDialog
import com.vladimir.messenger.ui.components.InputPanelDialog
import com.vladimir.messenger.ui.components.GroupFileCard
import com.vladimir.messenger.ui.components.fileIconFor
import com.vladimir.messenger.ui.components.ImagePreview
import com.vladimir.messenger.ui.components.TopicIconCatalog
import com.vladimir.messenger.ui.components.TopicIconView
import com.vladimir.messenger.util.ImageLinkDetector
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(
    onOpenAdmin: (groupId: String) -> Unit,
    onBackClick: () -> Unit,
    /** Нажатие на значок другой группы в левой колонке. */
    onSwitchGroup: (groupId: String) -> Unit = {},
    /** Нажатие на значок канала в левой колонке. */
    onSwitchChannel: (channelId: String) -> Unit = {},
    viewModel: GroupChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    var showNewTopic by remember { mutableStateOf(false) }
    // Файл к сообщению (рой, этап 9): системный выбор → хэш и копия →
    // визитка в тексте; сам файл участники просят у автора и друг у друга.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::onFileSelected)
    }
    val savePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> viewModel.onSaveTargetPicked(uri) }
    val pendingSave = uiState.pendingSave
    LaunchedEffect(pendingSave) {
        pendingSave?.let { transfer -> savePicker.launch(transfer.displayName) }
    }
    // Открыта ли лента конкретной темы. Пока не открыта и темы есть —
    // показываем вертикальный список тем пузырями, как просил владелец.
    var showFeed by remember { mutableStateOf(uiState.startInTopic) }
    // Раунд 143: заявки на вступление - пузырь над содержимым для админов.
    val joinRequests by viewModel.joinRequests.collectAsStateWithLifecycle()
    var showRequestsSheet by remember { mutableStateOf(false) }
    var requestQuery by remember { mutableStateOf("") }
    val canDecideRequests = uiState.me?.let {
        com.vladimir.messenger.data.group.GroupRole.isAdminOrOwner(it.role)
    } == true
    // Каталог GIF (кнопка «GIF» у скрепки).
    var showGifCatalog by remember { mutableStateOf(false) }
    // Раунд 135: подтверждение «удалить у всех» - действие необратимое.
    var deleteForAllTarget by remember { mutableStateOf<com.vladimir.messenger.data.local.entity.MessageEntity?>(null) }
    deleteForAllTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteForAllTarget = null },
            title = { Text("Удалить у всех?") },
            text = { Text("Сообщение исчезнет у всех участников группы. У кого старая версия приложения - там останется: обновите телефоны.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteForAllTarget = null
                    viewModel.deleteMessageForAll(target.id)
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteForAllTarget = null }) { Text("Отмена") }
            },
        )
    }

    if (showGifCatalog) {
        // Раунд 138: единая панель ввода - Эмодзи / Гиф / Стикеры в одном
        // пузыре, сверху лента пузырей разделов, следящая за прокруткой.
        val stickerEntries by viewModel.stickerEntries.collectAsStateWithLifecycle()
        val stickerRecents by viewModel.stickerRecents.collectAsStateWithLifecycle()
        val swarmStickers by viewModel.swarmStickers.collectAsStateWithLifecycle()
        InputPanelDialog(
            myGifs = uiState.myGifs,
            swarmGifs = uiState.swarmGifs,
            swarmStatus = uiState.swarmStatus,
            items = uiState.gifItems,
            next = uiState.gifNext,
            loading = uiState.gifLoading,
            error = uiState.gifError,
            onSearch = { viewModel.searchGifs(it) },
            onMore = { viewModel.searchGifs("", more = true) },
            onAttach = { item ->
                showGifCatalog = false
                viewModel.attachGif(item) { }
            },
            onAttachLocal = { entry ->
                showGifCatalog = false
                viewModel.attachLocalGif(entry.sha256)
            },
            onRequestSwarm = { swarm ->
                viewModel.requestSwarmGif(swarm) { showGifCatalog = false }
            },
            onRequestThumbs = { viewModel.requestPeerThumbs(it) },
            onAddOwnGif = { uri -> viewModel.addOwnGif(uri) },
            stickers = stickerEntries,
            stickerRecents = stickerRecents,
            swarmStickers = swarmStickers,
            onSticker = { viewModel.sendSticker(it) },
            onAddSticker = { uri -> viewModel.addSticker(uri) },
            onRequestSwarmSticker = { swarm -> viewModel.requestSwarmSticker(swarm) },
            onEmoji = { emoji -> draft += emoji },
            onOpened = { viewModel.refreshStickers() },
            onDismiss = {
                showGifCatalog = false
                viewModel.closeGifCatalog()
            },
        )
    }
    // В КАНАЛЕ список тем не показываем. Пост и комментарии к нему устроены
    // как тема внутри, но человеку это знать незачем: он открыл комментарии к
    // конкретному посту и должен видеть обычную переписку, а «Назад» обязано
    // вернуть его к ленте постов. Раньше здесь всплывал список «тем» -
    // одинаковые пузыри-оболочки постов, и выйти к ленте можно было только
    // вторым нажатием.
    val isChannel = uiState.group?.isChannel == true
    val hasTopics = !isChannel &&
        uiState.group?.topicsEnabled == true &&
        uiState.topics.isNotEmpty()
    val showTopicsList = hasTopics && !showFeed
    val selectedTopic = uiState.topics.firstOrNull { it.id == uiState.selectedTopicId }
    val selectedTopicName = selectedTopic?.name
    // Внутри темы группы наверху крупно - сама тема (значок и имя), а
    // название группы уходит в подзаголовок: раньше имя темы шло мелким
    // серым «дом · 3 участн.», и было не видно, куда зашёл.
    val topicHeader = !isChannel && hasTopics && showFeed && selectedTopic != null
    // Системный жест «Назад» (смахивание от края экрана, в т.ч. справа
    // налево) и кнопка «Назад» телефона. Без этого перехватчика Android
    // закрывал весь экран группы, и из темы человек попадал сразу в список
    // групп, минуя список тем (владелец, 2026-09-15). Внутри темы - к списку
    // тем; в списке тем и в группе без тем перехватчик выключен, и жест, как
    // и прежде, закрывает экран.
    BackHandler(enabled = hasTopics && showFeed) { showFeed = false }
    val senderNames = remember(uiState.members) {
        uiState.members.associate { it.nodeId to it.displayName }
    }

    // Подложка на весь экран, в том числе под верхней панелью.
            val feedListState = androidx.compose.foundation.lazy.rememberLazyListState()
            val feedScope = rememberCoroutineScope()
            LaunchedEffect(uiState.selectedTopicId, uiState.messages.size) {
                val jump = viewModel.feedJump.value ?: return@LaunchedEffect
                if (jump.topicId != uiState.selectedTopicId || uiState.messages.isEmpty()) {
                    return@LaunchedEffect
                }
                val offset = if (uiState.moreComments > 0) 1 else 0
                feedListState.scrollToItem(
                    (offset + jump.index).coerceIn(0, offset + uiState.messages.size - 1),
                )
                viewModel.consumeFeedJump()
            }
            // Раунд 150: отправил сообщение - лента доехала до него
            // (раньше оно оставалось за полем ввода, владелец, скрин).
            LaunchedEffect(uiState.messages.size, uiState.messages.lastOrNull()?.id) {
                val last = uiState.messages.lastOrNull() ?: return@LaunchedEffect
                if (last.isFromMe) {
                    val offset = if (uiState.moreComments > 0) 1 else 0
                    feedListState.animateScrollToItem(offset + uiState.messages.size - 1)
                }
            }
            val feedRemaining by remember(uiState.messages.size, uiState.moreComments) {
                derivedStateOf {
                    val last = feedListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    (uiState.messages.size + (if (uiState.moreComments > 0) 1 else 0) - 1 - last)
                        .coerceAtLeast(0)
                }
            }

    Box(modifier = Modifier.fillMaxSize()) {
        ChatWallpaper()
        Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    // Прокрутка НЕ должна красить панель: под ней обои APU.
                    scrolledContainerColor = Color.Transparent,
                ),
                title = {
                    // Название группы на белой полосочке со скруглениями и
                    // золотой рамкой - читается на любой подложке.
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xFFF5F7FA).copy(alpha = 0.92f))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                RoundedCornerShape(18.dp),
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        // Аватар группы слева от названия, если задан.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val storeAvatars by com.vladimir.messenger.ui.theme.AvatarStore.avatars
                                .collectAsState()
                            val gid = uiState.group?.id.orEmpty()
                            // Через общий кэш: разбор base64 идёт в фоне и
                            // результат переиспользуется. Раньше картинка
                            // раскодировалась ПРЯМО В ОТРИСОВКЕ на главном
                            // потоке - на входе в группу это давало зависание.
                            val bmp = com.vladimir.messenger.ui.components.AvatarBitmaps
                                .rememberAvatar(storeAvatars["g:$gid"])
                            if (bmp != null && !topicHeader) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape),
                                    contentScale = ContentScale.Crop,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            if (topicHeader && selectedTopic != null) {
                                // Открыта тема: её значок вместо аватара группы.
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFE8EEF5)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    TopicIconView(
                                        selectedTopic.iconEmoji.ifBlank { TopicIconCatalog.DEFAULT },
                                        26.dp,
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Column {
                            Text(
                                if (topicHeader && selectedTopic != null) {
                                    selectedTopic.name
                                } else {
                                    uiState.group?.title ?: "Группа"
                                },
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF1E2430),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                // В канале это комментарии к посту: показываем
                                // название поста, а не число участников -
                                // человек пришёл из ленты и должен видеть,
                                // под чем он находится.
                                when {
                                    isChannel -> selectedTopicName?.let { "Комментарии - $it" }
                                        ?: "Комментарии"
                                    // В теме: «Тема · группа», участники - на
                                    // списке тем, там они и нужны.
                                    topicHeader -> "Тема · " + (uiState.group?.title ?: "Группа")
                                    else -> (uiState.group?.memberCount ?: 0).toString() + " участн."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF5A6472),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            }
                        }
                    }
                },
                navigationIcon = {
                    TextButton(onClick = {
                        // Из ленты темы «Назад» возвращает к списку тем,
                        // а не сразу из группы.
                        if (hasTopics && showFeed) showFeed = false else onBackClick()
                    }) { Text("Назад") }
                },
                actions = {
                    IconButton(onClick = { onOpenAdmin(uiState.groupId) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Управление группой")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                // Смахивание вправо: внутри темы - назад к списку тем,
                // в списке тем и в группе без тем - назад к группам.
                .swipeBack {
                    if (hasTopics && showFeed) showFeed = false else onBackClick()
                },
        ) {
            Row(modifier = Modifier.fillMaxSize()) {

            // ── Левая колонка. В списке тем — значки групп и каналов,
            // а внутри открытой темы — значки тем текущей группы.
            // Если тем нет, колонки тоже нет: просто чат на всю ширину.
            if (hasTopics) {
                if (showTopicsList) {
                    GroupRail(
                        groups = uiState.allGroups,
                        currentGroupId = uiState.groupId,
                        onGroupClick = onSwitchGroup,
                        onChannelClick = onSwitchChannel,
                    )
                } else {
                    TopicRail(
                        topics = uiState.topics,
                        currentTopicId = uiState.selectedTopicId,
                        onTopicClick = { viewModel.selectTopic(it) },
                    )
                }
            }

            // ── Правая часть: список тем пузырями либо лента выбранной темы.
            Column(modifier = Modifier.weight(1f).fillMaxSize()) {

            // Раунд 143: пузырь заявок - на главной группы/канала, в темах
            // и в комментариях; содержимое НЕ перекрывает (оно уезжает вниз).
            if (canDecideRequests && joinRequests.isNotEmpty()) {
                JoinRequestsBanner(
                    count = joinRequests.size,
                    expanded = showRequestsSheet,
                    onClick = { showRequestsSheet = !showRequestsSheet },
                )
            }

            if (uiState.error != null) {
                Text(
                    uiState.error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            if (showTopicsList) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(uiState.topics, key = { it.id }) { topic ->
                        TopicBubble(topic = topic) {
                            viewModel.selectTopic(topic.id)
                            showFeed = true
                        }
                    }
                    if (uiState.canManageTopics) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showNewTopic = true },
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFFF5F7FA).copy(alpha = 0.6f),
                                ),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = null,
                                        tint = Color(0xFF5A6472),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Новая тема", color = Color(0xFF5A6472))
                                }
                            }
                        }
                    }
                }
            } else {

            // ── Закреплённые сообщения
            if (uiState.pinned.isNotEmpty()) {
                // Закрепы у каждой темы свои, поэтому показываем и имя темы.
                val pinnedTopicName = uiState.topics
                    .firstOrNull { it.id == uiState.selectedTopicId }
                    ?.name
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.PushPin, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Закреплённые" +
                                    (pinnedTopicName?.let { " · $it" }.orEmpty()) +
                                    if (uiState.pinned.size > 1) {
                                        " (" + uiState.pinned.size + ")"
                                    } else {
                                        ""
                                    },
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        // Каждое закреплённое сообщение — своей строкой, и рядом
                        // кнопка «Открепить»: снять закреп можно прямо отсюда,
                        // не разыскивая сообщение в ленте.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 168.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            uiState.pinned.forEach { m ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // Закреплённый файл (этап 9-10): значок по
                                    // типу и подпись «📎 имя (размер)» - она и
                                    // так в тексте, служебная строка визитки
                                    // отрезается вместе со строками фото.
                                    val pinnedFile = remember(m.content) { GroupFileMarker.parse(m.content) }
                                    if (pinnedFile != null) {
                                        Icon(
                                            fileIconFor(pinnedFile.mediaType),
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    Text(
                                        // Без служебных строк фото и длинного текста;
                                        // р156: и без гифка-ссылок/стикер-конвертов.
                                        com.vladimir.messenger.util.ChatPreviews.human(
                                            com.vladimir.messenger.util.InlineImage.stripImage(m.content)
                                                .ifBlank { pinnedFile?.let { GroupFileMarker.caption(it) }.orEmpty() }
                                        ) ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (uiState.canPin) {
                                        IconButton(
                                            onClick = { viewModel.togglePin(m.id, false) },
                                        ) {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = "Открепить",
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Лента темы
            // Раунд 144: при входе в тема открывается на первом непрочитанном
            // (или внизу, если всё прочитано), ниже - остальные непрочитанные.
            LazyColumn(
                state = feedListState,
                // Раунд 151: лента сжимается над клавиатурой (edge-to-edge:
                // окно само не сжимается - переписка пряталась за пузырями
                // ввода; запас 200dp снизу отводит место под пузыри).
                modifier = Modifier.weight(1f).fillMaxWidth().imePadding(),
                // Раунд 147: снизу запас под оверлей поля ввода.
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 200.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Большой канал: комментарии приходят от владельца по запросу,
                // и здесь может быть не вся ветка. Кнопка тянет более ранние.
                if (isChannel && uiState.moreComments > 0) {
                    item(key = "more-comments") {
                        TextButton(
                            onClick = { viewModel.loadOlderComments() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Показать ещё " + uiState.moreComments)
                        }
                    }
                }
                items(uiState.messages, key = { it.id }) { message ->
                    val card = remember(message.content) { GroupFileMarker.parse(message.content) }
                    val cardState = if (card == null) {
                        null
                    } else {
                        FileCardState.of(
                            chatId = uiState.groupId,
                            info = card,
                            isFromMe = message.isFromMe,
                            transfers = uiState.transfers,
                            pendingKeys = uiState.pendingFiles,
                            receivedFileFor = { viewModel.receivedFileFor(it) },
                            authorCopyFor = { viewModel.authorCopyFor(it) },
                            onDownload = { viewModel.requestFile(message, card) },
                            onSave = { viewModel.requestSaveReceivedFile(it) },
                            onShare = { f -> viewModel.shareFile(f, card.displayName, card.mediaType) },
                            onFavorite = { viewModel.saveFileToFavorites(it) },
                            servedCount = uiState.servedFiles[GroupFileMarker.key(uiState.groupId, card.sha256)] ?: 0,
                        )
                    }
                    MessageBubble(
                        message = message,
                        senderName = senderNames[message.senderId]?.takeIf { it.isNotBlank() }
                            ?: "Участник " + message.senderId.takeLast(4),
                        canPin = uiState.canPin,
                        onTogglePin = { viewModel.togglePin(message.id, !message.isPinned) },
                        onSaveToFavorites = { viewModel.saveToFavorites(message.content) },
                        reactions = uiState.reactions[message.id].orEmpty(),
                        onToggleReaction = { emoji -> viewModel.toggleReaction(message.id, emoji) },
                        onRemoveReaction = { viewModel.removeReaction(message.id) },
                        fileCard = cardState,
                        onEnsureGif = { sha -> viewModel.ensureGifRef(sha) },
                        onDeleteForMe = { viewModel.deleteMessageForMe(message.id) },
                        onDeleteForAll = { deleteForAllTarget = message },
                    )
                }
            }

            // ── Приложенный файл (этап 9): карточка над полем ввода до отправки.
            val staged = uiState.stagedFile
            if (staged != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(fileIconFor(staged.mediaType), contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(staged.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(GroupFileMarker.formatSize(staged.sizeBytes), style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = { viewModel.clearStagedFile() }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Убрать файл", modifier = Modifier.size(16.dp))
                    }
                }
            }

            } // else: лента темы
            } // правая колонка
            } // Row: левая колонка + правая
        }
    }
    // Показ - только ВНУТРИ темы (или в группе без тем): на списке
    // тем поля ввода и «Отправить» нет (владелец, раунд 148).
    if (!showTopicsList) {
    // Раунд 147: поле ввода - оверлей НА ВЕСЬ экран, включая область
    // пузырей тем слева (просьба владельца); imePadding поднимает всё
    // над клавиатурой, «Отправить» всегда видна.
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .imePadding(),
    ) {
            // Раунд 144: сколько сообщений осталось ниже - тап прокручивает вниз.
            if (feedRemaining > 0) {
                val lastIndex = (if (uiState.moreComments > 0) 1 else 0) + uiState.messages.size - 1
                Text(
                    "↓  Ещё " + messagesLabel(feedRemaining),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFF5F7FA).copy(alpha = 0.96f))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                            RoundedCornerShape(16.dp),
                        )
                        .clickable {
                            feedScope.launch {
                                feedListState.animateScrollToItem(lastIndex.coerceAtLeast(0))
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }

            // ── Поле ввода: подложка следует теме и пропускает обои (раунд 45).
            // Раунд 146: пузырь поля - от края до края экрана; «Отправить» -
            // своим пузырём той же ширины под полем (просьба владельца).
            var inputFocused by remember { mutableStateOf(false) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
                        )
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                            RoundedCornerShape(18.dp),
                        )
                        .padding(6.dp),
                ) {
                    if (inputFocused) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    if (uiState.canAttach) filePicker.launch(arrayOf("*/*")) else viewModel.onAttachLocked()
                                },
                                enabled = !uiState.isPreparingFile && !uiState.sending,
                                modifier = Modifier.size(40.dp),
                            ) {
                                if (uiState.isPreparingFile) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(
                                        Icons.Filled.AttachFile,
                                        contentDescription = if (uiState.canAttach) "Прикрепить файл" else "Вложения недоступны",
                                        tint = if (uiState.canAttach) Color(0xFF5A6472) else Color(0xFF9AA3AF),
                                    )
                                }
                            }
                            TextButton(
                                onClick = {
                                    if (uiState.canAttach) {
                                        showGifCatalog = true
                                        viewModel.onGifCatalogOpened()
                                        if (uiState.gifItems.isEmpty() && !uiState.gifLoading) {
                                            viewModel.searchGifs("")
                                        }
                                    } else {
                                        viewModel.onAttachLocked()
                                    }
                                },
                                enabled = !uiState.isPreparingFile && !uiState.sending,
                            ) {
                                Text(
                                    "GIF",
                                    fontWeight = FontWeight.Bold,
                                    color = if (uiState.canAttach) MaterialTheme.colorScheme.primary else Color(0xFF9AA3AF),
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { inputFocused = it.isFocused },
                        placeholder = { Text("Сообщение") },
                        minLines = 1,
                        maxLines = 6,
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFF1E2430),
                            unfocusedTextColor = Color(0xFF1E2430),
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White,
                            focusedPlaceholderColor = Color(0xFF5A6472),
                            unfocusedPlaceholderColor = Color(0xFF5A6472),
                        ),
                    )
                }
                Spacer(Modifier.height(6.dp))
                // Раунд 152: активная «Отправить» - золотая заливка и белый
                // текст: сразу видно, что сообщение можно отправить (раньше
                // менялся только оттенок текста - владелец не замечал).
                val canSend = (draft.isNotBlank() || uiState.stagedFile != null) &&
                    !uiState.sending && !uiState.isPreparingFile
                TextButton(
                    enabled = canSend,
                    onClick = {
                        viewModel.send(draft)
                        draft = ""
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            if (canSend) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
                        )
                        .border(
                            1.dp,
                            if (canSend) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                            RoundedCornerShape(18.dp),
                        )
                        .padding(vertical = 8.dp),
                ) {
                    Text(
                        "Отправить",
                        fontWeight = FontWeight.Bold,
                        color = if (canSend) Color.White else Color(0xFF9AA3AF),
                    )
                }
            }

    }

    }
    }


    // Раунд 143: список заявок поверх экрана (как в привычном мессенджере):
    // поиск-пузырь, прокрутка, «Принять в группу» / «Отклонить».
    if (showRequestsSheet && joinRequests.isNotEmpty()) {
        JoinRequestsSheet(
            requests = joinRequests,
            query = requestQuery,
            onQuery = { requestQuery = it },
            onDecide = { nodeId, approve ->
                viewModel.decideJoinRequest(nodeId, approve)
                if (joinRequests.size <= 1) showRequestsSheet = false
            },
            onDismiss = { showRequestsSheet = false },
        )
    }

    if (showNewTopic) {
        NewTopicDialog(
            onDismiss = { showNewTopic = false },
            onCreate = { name, icon ->
                viewModel.createTopic(name, icon)
                showNewTopic = false
            },
        )
    }
}

// =============================================================================
// Левая колонка: значки групп и каналов с непрочитанными
// =============================================================================

@Composable
private fun GroupRail(
    groups: List<GroupSummary>,
    currentGroupId: String,
    onGroupClick: (String) -> Unit,
    onChannelClick: (String) -> Unit,
) {
    val storeAvatars by com.vladimir.messenger.ui.theme.AvatarStore.avatars
        .collectAsState()
    LazyColumn(
        modifier = Modifier
            .width(76.dp)
            .fillMaxHeight()
            .background(Color(0xFFF5F7FA).copy(alpha = 0.55f)),
        contentPadding = PaddingValues(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(groups, key = { it.id }) { group ->
            val selected = group.id == currentGroupId
            Box(
                modifier = Modifier.clickable {
                    if (!selected) {
                        if (group.isChannel) onChannelClick(group.id) else onGroupClick(group.id)
                    }
                },
            ) {
                GroupRailAvatar(
                    groupId = group.id,
                    title = group.title,
                    avatarB64 = storeAvatars["g:" + group.id],
                    selected = selected,
                )
                if (group.unreadCount > 0) {
                    Box(modifier = Modifier.align(Alignment.TopEnd)) {
                        UnreadBadge(group.unreadCount)
                    }
                }
            }
        }
    }
}

/** Круглый аватар группы: картинка из хранилища либо первая буква названия. */
@Composable
private fun GroupRailAvatar(
    groupId: String,
    title: String,
    avatarB64: String?,
    selected: Boolean,
) {
    // Колонка показывает ВСЕ группы разом, поэтому раскодировать картинки в
    // отрисовке нельзя: десяток аватаров подряд подвешивал главный поток на
    // входе в группу и на возврате назад.
    val bmp = com.vladimir.messenger.ui.components.AvatarBitmaps.rememberAvatar(avatarB64)
    Box(
        modifier = Modifier
            .size(52.dp)
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                } else {
                    Modifier
                }
            )
            .padding(2.dp)
            .clip(CircleShape)
            .then(if (bmp == null) Modifier.background(Color(0xFFE8EEF5)) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                title.take(1).uppercase(),
                color = Color(0xFF1E2430),
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// =============================================================================
// Левая колонка внутри открытой темы: значки тем текущей группы
// =============================================================================

@Composable
private fun TopicRail(
    topics: List<TopicSummary>,
    currentTopicId: String?,
    onTopicClick: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .width(76.dp)
            .fillMaxHeight()
            .background(Color(0xFFF5F7FA).copy(alpha = 0.55f)),
        contentPadding = PaddingValues(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(topics, key = { it.id }) { topic ->
            val selected = topic.id == currentTopicId
            Box(
                modifier = Modifier.clickable {
                    if (!selected) onTopicClick(topic.id)
                },
            ) {
                TopicRailIcon(topic = topic, selected = selected)
                if (topic.unreadCount > 0) {
                    Box(modifier = Modifier.align(Alignment.TopEnd)) {
                        UnreadBadge(topic.unreadCount)
                    }
                }
            }
        }
    }
}

/** Кружок темы: её эмодзи, открытая тема обведена золотым. */
@Composable
private fun TopicRailIcon(topic: TopicSummary, selected: Boolean) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                } else {
                    Modifier
                }
            )
            .padding(2.dp)
            .clip(CircleShape)
            .background(Color(0xFFE8EEF5)),
        contentAlignment = Alignment.Center,
    ) {
        TopicIconView(
            topic.iconEmoji.ifBlank { TopicIconCatalog.DEFAULT },
            30.dp,
        )
    }
}

/** Кружок с числом непрочитанных: тёмная цифра на золоте, как в списках. */
@Composable
private fun UnreadBadge(count: Int) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF1E2430),
            fontWeight = FontWeight.Bold,
        )
    }
}

// =============================================================================
// Тема в своём пузыре: иконка, название, превью, время и непрочитанные
// =============================================================================

/**
 * Раунд 143: пузырь «N заявок на вступление» - виден только владельцу и
 * админам, живёт над содержимым (не перекрывает его) на главной
 * группы/канала, в темах и в комментариях. Нажатие раскрывает список.
 */
@Composable
private fun JoinRequestsBanner(count: Int, expanded: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFF5F7FA).copy(alpha = 0.94f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                RoundedCornerShape(18.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text("👥", fontSize = 18.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            requestsLabel(count) + " на вступление",
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            if (expanded) "▲" else "▼",
            fontSize = 14.sp,
            color = Color(0xFF5A6472),
        )
    }
}

/**
 * Раунд 143: список заявок снизу поверх экрана: поиск-пузырь и
 * прокручиваемые карточки с решениями.
 */
@Composable
private fun JoinRequestsSheet(
    requests: List<com.vladimir.messenger.data.group.JoinRequestSummary>,
    query: String,
    onQuery: (String) -> Unit,
    onDecide: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Затемнение: нажатие вне списка закрывает его.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable { onDismiss() },
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Color(0xFFF7F9FC))
                .padding(12.dp),
        ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text("Поиск заявок") },
            singleLine = true,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        val clean = query.trim()
        val filtered = if (clean.isEmpty()) requests else requests.filter {
            it.displayName.contains(clean, ignoreCase = true) ||
                it.note.contains(clean, ignoreCase = true)
        }
        if (filtered.isEmpty()) {
            Text(
                if (requests.isEmpty()) "Заявок пока нет" else "Никого не нашли по «" + clean + "»",
                color = Color(0xFF5A6472),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                items(filtered, key = { it.nodeId }) { request ->
                    JoinRequestRow(request = request, onDecide = onDecide)
                }
            }
        }
        }
    }
}

/** Карточка одной заявки: кто, когда и кнопки решения. */
@Composable
private fun JoinRequestRow(
    request: com.vladimir.messenger.data.group.JoinRequestSummary,
    onDecide: (String, Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE8EEF5)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    request.displayName.trim().take(1).uppercase().ifBlank { "?" },
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF5A6472),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    request.displayName.ifBlank { request.nodeId.take(8) },
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1E2430),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "подал(а) заявку " + topicTimeLabel(request.requestedAtMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF5A6472),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { onDecide(request.nodeId, true) }) {
                Text("Принять в группу")
            }
            TextButton(onClick = { onDecide(request.nodeId, false) }) {
                Text("Отклонить", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** «1 заявка / 2 заявки / 28 заявок». */
private fun requestsLabel(count: Int): String = when {
    count % 10 == 1 && count % 100 != 11 -> "$count заявка"
    count % 10 in 2..4 && count % 100 !in 12..14 -> "$count заявки"
    else -> "$count заявок"
}

/** Раунд 143: «1 сообщение / 3 сообщения / 653 сообщения / 5 сообщений». */
private fun messagesLabel(count: Int): String = when {
    count <= 0 -> "Нет сообщений"
    count % 10 == 1 && count % 100 != 11 -> "$count сообщение"
    count % 10 in 2..4 && count % 100 !in 12..14 -> "$count сообщения"
    else -> "$count сообщений"
}

@Composable
private fun TopicBubble(topic: TopicSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFF5F7FA).copy(alpha = 0.92f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE8EEF5)),
                contentAlignment = Alignment.Center,
            ) {
                TopicIconView(
                    topic.iconEmoji.ifBlank { TopicIconCatalog.DEFAULT },
                    30.dp,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        topic.name,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1E2430),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (topic.isClosed) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = "Тема закрыта",
                            modifier = Modifier.size(12.dp),
                            tint = Color(0xFF5A6472),
                        )
                    }
                }
                // Раунд 143: счётчик сообщений под названием темы (как в
                // привычном мессенджере: «653 сообщения»).
                Text(
                    messagesLabel(topic.messageCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF5A6472),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!topic.lastMessagePreview.isNullOrBlank()) {
                    Text(
                        // Раунд 155: без служебных строк (гифки/стикеры).
                        com.vladimir.messenger.util.ChatPreviews.human(topic.lastMessagePreview)
                            ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8A93A2),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    topicTimeLabel(topic.lastMessageAtMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF5A6472),
                )
                if (topic.unreadCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    UnreadBadge(topic.unreadCount)
                }
            }
        }
    }
}

/** Как в мессенджерах: сегодня — время, неделя — день недели, дальше — дата. */
private fun topicTimeLabel(ms: Long?): String {
    if (ms == null || ms <= 0L) return ""
    val now = System.currentTimeMillis()
    return when {
        isSameDay(ms, now) -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
        now - ms < 7L * 86_400_000L -> SimpleDateFormat("EEE", Locale("ru")).format(Date(ms))
        else -> SimpleDateFormat("d MMM", Locale("ru")).format(Date(ms))
    }
}

private fun isSameDay(a: Long, b: Long): Boolean {
    val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
    val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) &&
        ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    // Картинки и гифки в темах показываем так же, как в личном чате.
    message: MessageEntity,
    senderName: String,
    canPin: Boolean,
    onTogglePin: () -> Unit,
    onSaveToFavorites: () -> Unit = {},
    reactions: List<com.vladimir.messenger.data.reaction.ReactionSummary> = emptyList(),
    onToggleReaction: (String) -> Unit = {},
    onRemoveReaction: () -> Unit = {},
    /** Файл, приложенный к сообщению (этап 9), и ход его приёма/раздачи. */
    fileCard: FileCardState? = null,
    /** Раунд 130: карточка-ссылка просит VM тихо подтянуть байты гифки. */
    onEnsureGif: (String) -> Unit = {},
    /** Раунд 135: удалить сообщение - у себя или у всех. */
    onDeleteForMe: () -> Unit = {},
    onDeleteForAll: () -> Unit = {},
) {
    // Долгое нажатие - «В избранное» и «Реакция»: у сообщения темы нет своего
    // меню, а отдельная кнопка у каждого пузыря засорила бы ленту.
    var showMenu by remember { mutableStateOf(false) }
    var showReactions by remember { mutableStateOf(false) }
    val time = remember(message.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isFromMe) Arrangement.End else Arrangement.Start,
    ) {
        // Кнопке «Закрепить» справа нужно своё место: пузырь с карточкой
        // файла растягивается на все 300 dp, и на узком экране (лента рядом
        // с колонкой тем) кнопка выдавливалась за край - файл нельзя было
        // закрепить. Вес без заполнения: пузырь занимает не больше остатка.
        Box(modifier = if (canPin) Modifier.weight(1f, fill = false) else Modifier) {
        Card(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .combinedClickable(
                    // Одно нажатие - пузырь с реакциями, долгое - меню действий.
                    onClick = { showReactions = true },
                    onLongClick = { showMenu = true },
                ),
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                if (!message.isFromMe) {
                    Text(senderName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
                // Вложенная картинка едет отдельной служебной строкой внутри
                // текста. Раньше её печатали как есть, и в комментариях под
                // постом вместо снимка тянулись экраны «букв».
                val attachedB64 = remember(message.content) {
                    com.vladimir.messenger.util.InlineImage.extractB64(message.content)
                }
                val bodyText = remember(message.content, fileCard?.info) {
                    val words = com.vladimir.messenger.util.InlineImage.stripImage(message.content)
                    // Подпись «📎 имя (размер)» - для старых версий; здесь её
                    // заменяет карточка файла.
                    if (fileCard != null) GroupFileMarker.stripCaption(words, fileCard.info) else words
                }
                val attachedBitmap = com.vladimir.messenger.ui.components.AvatarBitmaps
                    .rememberAvatar(attachedB64)
                val imageUrl = remember(bodyText) {
                    ImageLinkDetector.directImageUrl(bodyText)
                }
                // Нажатие на картинку - на весь экран с увеличением.
                var showFullImage by remember(message.id) { mutableStateOf(false) }
                if (showFullImage && attachedB64 != null) {
                    com.vladimir.messenger.ui.components.PhotoViewer(
                        photos = listOf(com.vladimir.messenger.ui.components.PhotoSource.Encoded(attachedB64)),
                        onDismiss = { showFullImage = false },
                    )
                }
                when {
                    // Раунд 130: ССЫЛКА на гифку - карточка с анимацией;
                    // байты каждый телефон тихо тянет с хранителей сети.
                    com.vladimir.messenger.data.gif.GifLibrary.isGifRef(message.content) -> {
                        com.vladimir.messenger.ui.components.GifRefCard(
                            content = message.content,
                            onEnsure = onEnsureGif,
                        )
                    }
                    attachedBitmap != null -> {
                        androidx.compose.foundation.Image(
                            bitmap = attachedBitmap.asImageBitmap(),
                            contentDescription = "Картинка из сообщения",
                            contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .clickable { showFullImage = true },
                        )
                        if (bodyText.isNotBlank()) {
                            Text(bodyText, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                    imageUrl != null -> ImagePreview(
                        model = imageUrl,
                        contentDescription = "Картинка из сообщения",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                    )
                    // Пост из одних фотографий: сами фото - в ленте канала, а
                    // здесь, над комментариями, пузырь не должен быть пустым.
                    bodyText.isBlank() &&
                        com.vladimir.messenger.util.InlineImage.photoCount(message.content) > 0 ->
                        Text(
                            "Фото: " + com.vladimir.messenger.util.InlineImage.photoCount(message.content),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    bodyText.isBlank() && fileCard != null -> Unit
                    else -> Text(bodyText)
                }
                if (fileCard != null) {
                    // Долгое нажатие на самой картинке = меню пузыря
                    // (реакции/закрепить): раньше область карточки
                    // «проглатывала» жест, и реакцию поставить не выходило.
                    GroupFileCard(
                        state = fileCard,
                        isFromMe = message.isFromMe,
                        onLongPress = { showMenu = true },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(time, style = MaterialTheme.typography.labelSmall)
                    if (message.isPinned) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.PushPin, contentDescription = "Закреплено", modifier = Modifier.size(12.dp))
                    }
                }
                com.vladimir.messenger.ui.components.ReactionRow(
                    reactions = reactions,
                    onToggle = { showReactions = true },
                )
            }
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text("Поставить реакцию") },
                onClick = {
                    showMenu = false
                    showReactions = true
                },
            )
            DropdownMenuItem(
                text = { Text("В избранное") },
                onClick = {
                    showMenu = false
                    onSaveToFavorites()
                },
            )
            // Закреп и из меню долгого нажатия - на случай, если кнопка
            // справа не поместилась или её не заметили.
            if (canPin) {
                DropdownMenuItem(
                    text = { Text(if (message.isPinned) "Открепить" else "Закрепить") },
                    onClick = {
                        showMenu = false
                        onTogglePin()
                    },
                )
            }
            // Раунд 135: удаление своего сообщения - у себя и у всех.
            if (message.isFromMe) {
                DropdownMenuItem(
                    text = { Text("Удалить у себя", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        showMenu = false
                        onDeleteForMe()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Удалить у всех", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        showMenu = false
                        onDeleteForAll()
                    },
                )
            }
        }
        if (showReactions) {
            val mine = reactions.firstOrNull { it.mine }?.emoji
            com.vladimir.messenger.ui.components.ReactionPickerDialog(
                onDismiss = { showReactions = false },
                myEmoji = mine,
                onRemove = {
                    showReactions = false
                    onRemoveReaction()
                },
                onPick = { emoji ->
                    showReactions = false
                    onToggleReaction(emoji)
                },
            )
        }
        }
        if (canPin) {
            IconButton(onClick = onTogglePin, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.PushPin,
                    contentDescription = if (message.isPinned) "Открепить" else "Закрепить",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun NewTopicDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf(TopicIconCatalog.DEFAULT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая тема") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название темы") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TopicIconView(icon, 34.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Значок темы: " + TopicIconCatalog.describe(icon),
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(0xFF5A6472),
                    )
                }
                // Живые значки уже анимированы прямо в сетке выбора; сетка
                // ленивая, чтобы 105 анимаций не тормозили телефон.
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(4.dp),
                ) {
                    gridItems(TopicIconCatalog.kinds, key = { it }) { kind ->
                        val selected = kind == icon
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFE8EEF5))
                                .then(
                                    if (selected) {
                                        Modifier.border(
                                            2.dp,
                                            MaterialTheme.colorScheme.primary,
                                            RoundedCornerShape(12.dp),
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                                .clickable { icon = kind },
                            contentAlignment = Alignment.Center,
                        ) {
                            AnimatedTopicIcon(kind, Modifier.size(36.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onCreate(name, icon) },
            ) { Text("Создать") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } },
    )
}

