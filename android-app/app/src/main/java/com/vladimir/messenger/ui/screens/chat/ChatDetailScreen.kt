package com.vladimir.messenger.ui.screens.chat

import com.vladimir.messenger.ui.components.ApuBubbleTextColor
import com.vladimir.messenger.ui.components.ApuBubbleMutedColor
import com.vladimir.messenger.ui.components.ApuBubbleAccentColor
import com.vladimir.messenger.ui.components.ApuHeaderBubble
import com.vladimir.messenger.ui.components.ApuBubbleCard
import com.vladimir.messenger.ui.components.ApuBubbleLinkColor
import com.vladimir.messenger.ui.components.apuBubbleSurface
import com.vladimir.messenger.ui.components.swipeBack
import androidx.compose.foundation.layout.*
import com.vladimir.messenger.ui.components.ChatWallpaper
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import com.vladimir.messenger.ui.components.PeerProfileSheet
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.SolidColor
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.ui.components.FileTransferBubble
import com.vladimir.messenger.ui.components.MessageBubble
import com.vladimir.messenger.domain.model.Message
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

private data class ChatRow(
    val key: String,
    val message: Message?,
    val transfer: FileTransferEntity?,
    val orderMs: Long,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(
    chatId: String,
    contactName: String,
    contactId: String = "",
    onBackClick: () -> Unit,
    onRenameClick: (contactId: String, currentName: String) -> Unit = { _, _ -> },
    onCallClick: (contactId: String, contactName: String) -> Unit = { _, _ -> },
    /** Раунд 176: открыть добавление контакта по ссылке из карточки в чате. */
    onAddContactInvite: (String) -> Unit = {},
    /** Раунд 203: тап по источнику пересылки - открыть чат друга. */
    onOpenChat: (chatId: String, contactName: String, contactId: String) -> Unit = { _, _, _ -> },
    /** Раунд 203: тап по источнику пересылки - открыть группу/канал (тему). */
    onOpenGroup: (groupId: String, topicId: String?) -> Unit = { _, _ -> },
    /** Раунд 203: источника нет на телефоне - карточка «Вступить»/«Подписаться». */
    onJoinByLink: (link: String) -> Unit = {},
    viewModel: ChatDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Раунд 189: результат «Вступить/Подписаться» - всплывающей подсказкой.
    val inviteStatus by viewModel.inviteStatus.collectAsStateWithLifecycle()
    val toastContext = LocalContext.current
    LaunchedEffect(inviteStatus) {
        inviteStatus?.let {
            android.widget.Toast.makeText(toastContext, it, android.widget.Toast.LENGTH_LONG).show()
            viewModel.consumeInviteStatus()
        }
    }
    val listState = rememberLazyListState()
    var activeMessage by remember { mutableStateOf<Message?>(null) }
    var showCopyDialog by remember { mutableStateOf<Message?>(null) }
    // Раунд 203: «Поделиться в APU» - пересылка с указанием источника.
    var forwardMessage by remember { mutableStateOf<Message?>(null) }
    val fwdScope = androidx.compose.runtime.rememberCoroutineScope()
    // Раунд 203: подтверждение пересылки - плашка по центру экрана.
    var fwdSent by remember { mutableStateOf(false) }
    if (fwdSent) {
        com.vladimir.messenger.ui.components.ForwardSentOverlay(visible = true, onTimeout = { fwdSent = false })
    }

    // Раунд 135: подтверждение «удалить у всех» - действие необратимое.
    var deleteForAllTarget by remember { mutableStateOf<Message?>(null) }
    // Сообщение, для которого открыт выбор реакции.
    var reactionFor by remember { mutableStateOf<String?>(null) }
    // Текст, открытый в окне выделения части.
    var selectTextOf by remember { mutableStateOf<String?>(null) }

    // Сброс выделения и диалога копирования
    fun resetSelection() {
        activeMessage = null
        showCopyDialog = null
    }

    val context = LocalContext.current

    // F3: системный выбор файла (SAF) → зашифрованная подготовка → durable отправка
    // Каталог GIF (кнопка «GIF» у скрепки).
    var showGifCatalog by remember { mutableStateOf(false) }

    if (showGifCatalog) {
        // Раунд 138: единая панель ввода - Эмодзи / Гиф / Стикеры в одном
        // пузыре, сверху лента пузырей разделов, следящая за прокруткой.
        val stickerEntries by viewModel.stickerEntries.collectAsStateWithLifecycle()
        val stickerRecents by viewModel.stickerRecents.collectAsStateWithLifecycle()
        val swarmStickers by viewModel.swarmStickers.collectAsStateWithLifecycle()
        com.vladimir.messenger.ui.components.InputPanelDialog(
            myGifs = uiState.myGifs,
            swarmGifs = uiState.swarmGifs,
            swarmStatus = uiState.swarmStatus,
            items = uiState.gifItems,
            next = uiState.gifNext,
            loading = uiState.gifLoading,
            error = uiState.gifError,
            notice = uiState.gifNotice,
            onSearch = { viewModel.searchGifs(it) },
            onMore = { viewModel.searchGifs("", more = true) },
            onAttach = { item ->
                showGifCatalog = false
                viewModel.attachGif(item)
            },
            onAttachLocal = { entry ->
                showGifCatalog = false
                viewModel.attachLocalGif(entry.sha256)
            },
            onRequestSwarm = { swarm ->
                // Раунд 129: выбрал - окно закрылось, показан чат с карточкой.
                showGifCatalog = false
                viewModel.requestSwarmGif(swarm)
            },
            onRequestThumbs = { viewModel.requestPeerThumbs(it) },
            onAddOwnGif = { uri -> viewModel.addOwnGif(uri) },
            stickers = stickerEntries,
            stickerRecents = stickerRecents,
            swarmStickers = swarmStickers,
            onSticker = {
                // Раунд 170: выбрал стикер - окно закрывается, видно чат.
                showGifCatalog = false
                viewModel.sendSticker(it)
            },
            onAddSticker = { uri -> viewModel.addSticker(uri) },
            onAddStickerZip = { uri -> viewModel.addStickerZip(uri) },
            onRemoveSticker = { viewModel.removeSticker(it) },
            // Раунд 206: пустые плитки сами докачиваются; ↻ - всем держателям.
            onAutoFetchSticker = { viewModel.autoFetchSticker(it) },
            onRetryFetchSticker = { viewModel.retryFetchSticker(it) },
            onRemoveGif = { viewModel.removeOwnGif(it.sha256) },
            onRequestSwarmSticker = { swarm ->
                showGifCatalog = false
                viewModel.requestSwarmSticker(swarm)
            },
            onEmoji = { emoji -> viewModel.onInputTextChanged(uiState.inputText + emoji) },
            onOpened = { viewModel.refreshStickers() },
            onDismiss = {
                showGifCatalog = false
                viewModel.closeGifCatalog()
            },
        )
    }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(viewModel::onFileSelected)
    }

    // F3: экспорт принятого файла — системный диалог «куда сохранить»
    val savePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        viewModel.onSaveTargetPicked(uri)
    }
    val pendingSave = uiState.pendingSave
    LaunchedEffect(pendingSave) {
        pendingSave?.let { transfer -> savePicker.launch(transfer.displayName) }
    }

    // Прокрутка к последнему сообщению
    LaunchedEffect(uiState.scrollToBottom, uiState.messages.size) {
        if (uiState.scrollToBottom && uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
            viewModel.onScrolledToBottom()
        }
    }

    // SnackBar для ошибок
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Карточка собеседника: открывается тапом по имени в шапке.
    var showPeerProfile by remember { mutableStateOf(false) }

    // Карточка собеседника поверх переписки.
    if (showPeerProfile) {
        PeerProfileSheet(
            name = contactName,
            contactId = contactId,
            isOnline = uiState.isContactOnline,
            username = uiState.contactUsername,
            heartCount = uiState.heartCount,
            heartMine = uiState.heartMine,
            onHeartClick = if (contactId.startsWith("pk_")) {
                { viewModel.onHeartClick(contactId) }
            } else {
                null
            },
            onDismiss = { showPeerProfile = false },
            onRename = if (contactId.isNotBlank()) {
                {
                    showPeerProfile = false
                    onRenameClick(contactId, contactName)
                }
            } else {
                null
            },
            onCall = if (contactId.startsWith("pk_")) {
                {
                    showPeerProfile = false
                    onCallClick(contactId, contactName)
                }
            } else {
                null
            },
            onCopyId = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Узел", contactId))
                Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
            },
        )
    }

    // Подложка на весь экран, в том числе под верхней панелью.
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Смахивание вправо работает как «Назад».
            .swipeBack(onBack = onBackClick),
    ) {
        ChatWallpaper()
        // Раунд 266: клавиатура не закрывает переписку - список сообщений
        // сжимается над клавиатурой, последние сообщения видны сразу.
        Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    // Прокрутка НЕ должна красить панель: под ней обои APU.
                    scrolledContainerColor = Color.Transparent,
                ),
                title = {
                    // Общий пузырь шапки, как в группах и каналах.
                    ApuHeaderBubble(onClick = { showPeerProfile = true }) {
                        Column {
                            Text(
                                contactName,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF1E2430),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            // р235: «печатает…» важнее статуса сети и гаснет само.
                            val peerTyping = uiState.isPeerTyping
                            Text(
                                text = when {
                                    peerTyping -> "печатает…"
                                    uiState.isContactOnline -> "в сети"
                                    else -> "не в сети"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (peerTyping || uiState.isContactOnline)
                                    ApuBubbleAccentColor
                                else
                                    Color(0xFF5A6472),
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, "Назад")
                    }
                },
                actions = {
                    if (contactId.isNotBlank()) {
                        IconButton(onClick = { onRenameClick(contactId, contactName) }) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Переименовать",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // Аудиозвонок: активен только у контакта с node id (pk_…).
                    IconButton(
                        onClick = { onCallClick(contactId, contactName) },
                        enabled = contactId.startsWith("pk_"),
                    ) {
                        Icon(
                            Icons.Default.Call,
                            contentDescription = "Позвонить",
                            tint = if (contactId.startsWith("pk_")) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color(0xFF9AA3AF)
                            },
                        )
                    }
                },
            )
        },
        bottomBar = {
            MessageInputBar(
                text         = uiState.inputText,
                onTextChange = viewModel::onInputTextChanged,
                onSend       = viewModel::onSendMessage,
                isSending    = uiState.isSending,
                isSelfChat   = uiState.isSelfChat,
                canAttach    = uiState.canSendAttachments,
                onAttach     = {
                    if (uiState.canSendAttachments) {
                        filePicker.launch(arrayOf("*/*"))
                    } else {
                        viewModel.onAttachmentsLocked()
                    }
                },
                isPreparingFile = uiState.isPreparingFile,
                onPastedMedia = viewModel::onFileSelected,
                onPasteLocked = viewModel::onAttachmentsLocked,
                onGifClick = {
                    showGifCatalog = true
                    viewModel.onGifCatalogOpened()
                    if (uiState.gifItems.isEmpty() && !uiState.gifLoading) {
                        viewModel.searchGifs("")
                    }
                },
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { resetSelection() }
                    )
                },
        ) {
            when {
                uiState.isLoading && uiState.messages.isEmpty() -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                uiState.historyError != null && uiState.messages.isEmpty() -> {
                    ChatHistoryError(
                        message = uiState.historyError.orEmpty(),
                        onRetry = viewModel::retryMessages,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                uiState.messages.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center)
                            .padding(horizontal = 16.dp)
                            .apuBubbleSurface()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint     = ApuBubbleAccentColor.copy(alpha = 0.75f),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Сообщения зашифрованы E2E",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ApuBubbleMutedColor,
                        )
                        Text(
                            "Напишите первое сообщение",
                            style = MaterialTheme.typography.bodySmall,
                            color = ApuBubbleMutedColor,
                        )
                    }
                }

                else -> {
                    val transfersByMessageId = remember(uiState.transfers) {
                        uiState.transfers.associateBy { it.messageId }
                    }
                    val chatRows = remember(uiState.messages, uiState.transfers) {
                        val knownMessageIds = uiState.messages.map { it.id }.toSet()
                        // Раунд 123: одну гифку может везти несколько телефонов
                        // (просьба уходит троим). Показываем только первичную
                        // (самую раннюю) полосу - без трёх одинаковых пузырей.
                        val shadowed = uiState.transfers
                            .filter { it.direction == "INCOMING" && it.state != "COMPLETE" }
                            .groupBy { it.fileSha256 }
                            .flatMap { (_, group) ->
                                group.sortedBy { it.createdAtMs }.drop(1).map { it.transferId }
                            }
                            .toSet()
                        val rows = uiState.messages.map { message ->
                            ChatRow(
                                key = message.id,
                                message = message,
                                transfer = transfersByMessageId[message.id],
                                orderMs = message.timestamp,
                            )
                        } + uiState.transfers
                            .filter {
                                it.direction == "INCOMING" &&
                                    it.state != "COMPLETE" &&
                                    it.messageId !in knownMessageIds &&
                                    it.transferId !in shadowed &&
                                    // Раунд 128/170: гифки и стикеры ходят ТИХО -
                                    // служебную передачу байтов в ленте не показываем.
                                    !it.mediaType.equals("image/gif", ignoreCase = true) &&
                                    !it.displayName.startsWith("Стикер")
                            }
                            .map { transfer ->
                                ChatRow(
                                    key = "t-${transfer.transferId}",
                                    message = null,
                                    transfer = transfer,
                                    orderMs = transfer.createdAtMs,
                                )
                            }
                        rows.sortedBy { it.orderMs }
                    }
                    // Раунд 173: закреплённые сообщения личного чата; тап -
                    // лента прыгает к самому сообщению.
                    // Раунд 246: закреп и лента живут в Column. Раньше оба были
                    // детьми Box, и лента с fillMaxSize рисовалась ПОВЕРХ
                    // закрепа - закреп «проваливался под ленту» (скрин владельца
                    // 01.10: стикер наезжал на плашку «Закреплённые»).
                    Column(modifier = Modifier.fillMaxSize()) {
                    if (uiState.historyError != null) {
                        ChatHistoryError(
                            message = uiState.historyError.orEmpty(),
                            onRetry = viewModel::retryMessages,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (uiState.pinned.isNotEmpty()) {
                        val feedScope = androidx.compose.runtime.rememberCoroutineScope()
                        ApuBubbleCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Icon(
                                        androidx.compose.material.icons.Icons.Filled.PushPin,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "Закреплённые (" + uiState.pinned.size + "/" +
                                            com.vladimir.messenger.data.local.MessagePinPolicy.MAX_PINNED_PER_SCOPE + ")",
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                                uiState.pinned.forEach { m ->
                                    val pinnedIndex = chatRows.indexOfFirst { it.message?.id == m.id }
                                    val rowModifier = if (pinnedIndex >= 0) {
                                        Modifier.fillMaxWidth().clickable {
                                            feedScope.launch { listState.animateScrollToItem(pinnedIndex) }
                                        }
                                    } else {
                                        Modifier.fillMaxWidth()
                                    }
                                    Row(
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                        modifier = rowModifier,
                                    ) {
                                        Text(
                                            com.vladimir.messenger.util.ChatPreviews.human(
                                                com.vladimir.messenger.util.InlineImage.stripImage(m.content)
                                            ) ?: m.content.take(80),
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        IconButton(onClick = { viewModel.togglePin(m.id, false) }) {
                                            Icon(
                                                androidx.compose.material.icons.Icons.Filled.Close,
                                                contentDescription = "Открепить",
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    LazyColumn(
                        state          = listState,
                        // Раунд 246: лента занимает остаток Column под закрепом.
                        modifier       = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        reverseLayout  = false,
                    ) {
                        items(
                            items = chatRows,
                            key   = { it.key },
                        ) { row ->
                            val transfer = row.transfer
                            if (transfer != null) {
                                FileTransferBubble(
                                    transfer = transfer,
                                    isFromMe = transfer.direction == "OUTGOING",
                                    previewFile = viewModel.previewFileFor(transfer),
                                    onShareClick = { viewModel.shareTransferFile(transfer) },
                                    onSaveClick = if (
                                        transfer.direction == "INCOMING" &&
                                        transfer.state == "COMPLETE"
                                    ) {
                                        { viewModel.requestSaveReceivedFile(transfer) }
                                    } else {
                                        null
                                    },
                                    onSaveToFavorites = if (transfer.state == "COMPLETE") {
                                        { viewModel.saveToFavorites(transfer, contactName) }
                                    } else {
                                        null
                                    },
                                    // Раунд 120: долгое нажатие на картинке/гифке -
                                    // то же меню действий, что у текстового пузыря:
                                    // оттуда и «Поставить реакцию». Раньше реакцию
                                    // на гифке поставить было нельзя никому.
                                    onLongPress = row.message?.let { message ->
                                        {
                                            activeMessage = message
                                            showCopyDialog = message
                                        }
                                    },
                                )
                                // Реакции файла/гифки - той же строкой под пузырём,
                                // что и у текстовых сообщений.
                                row.message?.let { message ->
                                    com.vladimir.messenger.ui.components.ReactionRow(
                                        reactions = uiState.reactions[message.id].orEmpty(),
                                        onToggle = { reactionFor = message.id },
                                        modifier = Modifier.padding(horizontal = 14.dp),
                                    )
                                }
                            } else {
                                val message = row.message!!
                                Column(
                                    horizontalAlignment = if (message.isFromMe) {
                                        Alignment.End
                                    } else {
                                        Alignment.Start
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                // Раунд 128: ССЫЛКА на гифку - карточка с
                                // анимацией (байты подтягиваются тихо с сети).
                                // Раунд 203: у пересланного служебные строки
                                // (маркер+шапка) впереди - узнаём гифку в теле.
                                val fwdBody = remember(message.id) { com.vladimir.messenger.util.ForwardMarker.stripHeader(message.content) }
                                val onForwardTap: (com.vladimir.messenger.util.ForwardMarker.Ref) -> Unit = { ref ->
                                    fwdScope.launch {
                                        when (val open = viewModel.resolveForwardTap(ref)) {
                                            is com.vladimir.messenger.util.ForwardMarker.Open.Chat ->
                                                onOpenChat(open.chatId, open.contactName, open.contactId)
                                            is com.vladimir.messenger.util.ForwardMarker.Open.Group ->
                                                onOpenGroup(open.groupId, open.topicId)
                                            is com.vladimir.messenger.util.ForwardMarker.Open.Join ->
                                                onJoinByLink(open.link)
                                            is com.vladimir.messenger.util.ForwardMarker.Open.Missing ->
                                                Toast.makeText(toastContext, open.message, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                                val fwdSource = remember(message.id) { com.vladimir.messenger.util.ForwardMarker.parseRef(message.content) }
                                val fwdHasHeader = remember(message.id) { com.vladimir.messenger.util.ForwardMarker.hasHeader(message.content) }
                                // Раунд 203: над карточками (гифка, файл/стикер) шапку
                                // рисует сам экран - карточки про источник не знают.
                                @Composable fun CardForwardHeader() {
                                    if (!fwdHasHeader) return
                                    val fwdLabel = fwdSource?.label
                                        ?: com.vladimir.messenger.util.ForwardMarker.plainHeaderLabel(message.content)
                                    Text(
                                        "↩ Переслано из «" + fwdLabel + "»",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        textDecoration = TextDecoration.Underline,
                                        color = ApuBubbleLinkColor,
                                        modifier = Modifier
                                            .apuBubbleSurface()
                                            .clickable(enabled = fwdSource != null) { if (fwdSource != null) onForwardTap(fwdSource) }
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                                // Раунд 203: пересланный файл/стикер - визитка без
                                // передачи: рисуем стикер из локальной библиотеки,
                                // иначе честную карточку, но не «код».
                                val fwdFile = remember(fwdBody) { com.vladimir.messenger.util.GroupFileMarker.parse(fwdBody) }
                                val fwdLocal = remember(message.id, fwdFile?.sha256) {
                                    fwdFile?.let { viewModel.localSwarmFile(it.sha256) }
                                }
                                when {
                                    fwdFile != null -> {
                                        CardForwardHeader()
                                        if (fwdLocal != null &&
                                            (com.vladimir.messenger.util.GroupFileMarker.isAnimatedImage(fwdFile) ||
                                                com.vladimir.messenger.util.GroupFileMarker.isSticker(fwdFile))
                                        ) {
                                            var showFullFwd by remember(message.id) { mutableStateOf(false) }
                                            com.vladimir.messenger.ui.components.StickerAnimated(
                                                file = fwdLocal,
                                                contentDescription = fwdFile.displayName,
                                                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                                modifier = Modifier
                                                    .align(if (message.isFromMe) Alignment.End else Alignment.Start)
                                                    .sizeIn(maxWidth = 340.dp, maxHeight = 320.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .clickable { showFullFwd = true },
                                            )
                                            if (showFullFwd) {
                                                com.vladimir.messenger.ui.components.PhotoViewer(
                                                    photos = listOf(
                                                        com.vladimir.messenger.ui.components.PhotoSource.File(fwdLocal.path)
                                                    ),
                                                    onDismiss = { showFullFwd = false },
                                                )
                                            }
                                        } else {
                                            Text(
                                                com.vladimir.messenger.util.GroupFileMarker.caption(fwdFile) +
                                                    "\nфайла нет на этом телефоне",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (message.isFromMe)
                                                    com.vladimir.messenger.ui.theme.LocalMessengerColors.current.messageBubbleOwnText
                                                else ApuBubbleTextColor,
                                                modifier = Modifier.align(if (message.isFromMe) Alignment.End else Alignment.Start)
                                                    .apuBubbleSurface(color = if (message.isFromMe)
                                                        com.vladimir.messenger.ui.theme.LocalMessengerColors.current.messageBubbleOwn
                                                    else com.vladimir.messenger.ui.theme.LocalMessengerColors.current.messageBubbleOther)
                                                    .padding(10.dp),
                                            )
                                        }
                                    }
                                    com.vladimir.messenger.data.gif.GifLibrary.isGifRef(fwdBody) -> {
                                        CardForwardHeader()
                                    com.vladimir.messenger.ui.components.GifRefCard(
                                        content = fwdBody,
                                        modifier = Modifier.align(
                                            if (message.isFromMe) Alignment.End else Alignment.Start
                                        ),
                                        onEnsure = { sha -> viewModel.ensureGifRef(sha) },
                                        // Раунд 210: точки на гифке - то же меню
                                        // «Действия с сообщением», что по удержанию.
                                        onMenu = {
                                            activeMessage = message
                                            showCopyDialog = message
                                        },
                                    )
                                    }
                                    else -> MessageBubble(
                                    message = message,
                                    isSelected = activeMessage?.id == message.id,
                                    linkColor = ApuBubbleLinkColor,
                                    onContactInvite = onAddContactInvite,
                                    onGroupInvite = { viewModel.joinByInviteLink(it) },
                                    // Одно нажатие - сразу пузырь с реакциями:
                                    // владелец просил ставить их в один тап.
                                    // Остальные действия - долгое нажатие.
                                    onTap = { reactionFor = message.id },
                                    // Долгое нажатие открывает действия сразу -
                                    // как в группе. Раньше требовалось нажать
                                    // дважды, и «В избранное» никто не находил.
                                    onLongClick = {
                                        activeMessage = message
                                        showCopyDialog = message
                                    },
                                    onOpenForward = onForwardTap,
                                    // Раунд 211: «три точки» на пузыре - то же
                                    // меню «Действия с сообщением».
                                    onMenu = {
                                        activeMessage = message
                                        showCopyDialog = message
                                    },
                                )
                                }
                                // Реакции живут отдельной строкой под пузырём -
                                // внутрь его класть нельзя, там своя ширина.
                                com.vladimir.messenger.ui.components.ReactionRow(
                                    reactions = uiState.reactions[message.id].orEmpty(),
                                    // Нажатие на сам значок открывает тот же
                                    // пузырь: там и меняют, и убирают.
                                    onToggle = { reactionFor = message.id },
                                    modifier = Modifier.padding(horizontal = 14.dp),
                                )
                                }
                            }
                        }
                    }
                    } // Раунд 246: закрыли Column «закреп + лента».
                }
            }
        }
    }

    // Окно действий над сообщением. Раньше оно называлось «Копировать
    // сообщение?» и «В избранное» пряталось на месте кнопки «Отмена» - её там
    // никто не искал. Теперь это список действий, а отмена закрывает окно.
    showCopyDialog?.let { message ->
        AlertDialog(
            onDismissRequest = { showCopyDialog = null },
            title = { Text("Действия с сообщением") },
            text = {
                Column {
                    // Раунд 203: превью без служебных строк пересылки.
                    // Раунд 222 (владелец): служебные маркеры - по-человечески:
                    // гифка - словом «Гифка» (а не «APUGIFREF1|хэш»), файл -
                    // «📎 имя (размер)», как на карточке.
                    val previewText = remember(message.id) {
                        val stripped = com.vladimir.messenger.util.ForwardMarker.stripHeader(message.content)
                        when {
                            com.vladimir.messenger.data.gif.GifLibrary.isGifRef(stripped) -> "Гифка"
                            else -> com.vladimir.messenger.util.GroupFileMarker.parse(stripped)
                                ?.let { com.vladimir.messenger.util.GroupFileMarker.caption(it) }
                                ?: stripped
                        }
                    }
                    Text(
                        previewText.take(100) +
                            if (previewText.length > 100) "..." else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    // Раунд 211: пункты - золотыми пузырями, как в «точках»
                    // групп и каналов (владелец: единый стиль). Опасное -
                    // красным. Состав действий прежний.
                    com.vladimir.messenger.ui.components.ApuActionBubble(
                        "Поделиться в APU",
                        androidx.compose.material.icons.Icons.Filled.Send,
                    ) {
                        showCopyDialog = null
                        forwardMessage = message
                    }
                    com.vladimir.messenger.ui.components.ApuActionBubble(
                        "Копировать всё",
                        androidx.compose.material.icons.Icons.Filled.ContentCopy,
                    ) {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as ClipboardManager
                        val clip = ClipData.newPlainText("Сообщение", message.content)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                        showCopyDialog = null
                        resetSelection()
                    }
                    // Раунд 173: закрепить/открепить сообщение личного чата.
                    com.vladimir.messenger.ui.components.ApuActionBubble(
                        if (message.isPinned) "Открепить" else "Закрепить",
                        androidx.compose.material.icons.Icons.Filled.PushPin,
                    ) {
                        showCopyDialog = null
                        viewModel.togglePin(message.id, !message.isPinned)
                    }
                    com.vladimir.messenger.ui.components.ApuActionBubble(
                        "Выделить часть текста",
                        androidx.compose.material.icons.Icons.Filled.Edit,
                    ) {
                        showCopyDialog = null
                        selectTextOf = message.content
                    }
                    // Раунд 124: у файла/гифки в избранное кладётся САМ ФАЙЛ
                    // (ссылка на принятую передачу), а не пустой текст.
                    val savedMessageFile = uiState.transfers.firstOrNull {
                        it.messageId == message.id &&
                            it.direction == "INCOMING" &&
                            it.state == "COMPLETE"
                    }
                    if (savedMessageFile != null) {
                        com.vladimir.messenger.ui.components.ApuActionBubble(
                            "Файл в избранное",
                            androidx.compose.material.icons.Icons.Filled.Star,
                        ) {
                            viewModel.saveToFavorites(savedMessageFile, contactName)
                            showCopyDialog = null
                            resetSelection()
                        }
                    }
                    if (message.content.isNotBlank()) {
                        com.vladimir.messenger.ui.components.ApuActionBubble(
                            "В избранное",
                            androidx.compose.material.icons.Icons.Filled.Star,
                        ) {
                            // Раунд 203: в избранное - без служебных строк.
                            viewModel.saveTextToFavorites(com.vladimir.messenger.util.ForwardMarker.stripHeader(message.content), contactName)
                            showCopyDialog = null
                            resetSelection()
                        }
                    }
                    com.vladimir.messenger.ui.components.ApuActionBubble(
                        "Поставить реакцию",
                        androidx.compose.material.icons.Icons.Filled.EmojiEmotions,
                    ) {
                        showCopyDialog = null
                        reactionFor = message.id
                    }
                    // Раунд 135: удаление своего сообщения - у себя и у всех.
                    if (message.isFromMe) {
                        com.vladimir.messenger.ui.components.ApuActionBubble(
                            "Удалить у себя",
                            androidx.compose.material.icons.Icons.Filled.Delete,
                            destructive = true,
                        ) {
                            showCopyDialog = null
                            viewModel.deleteMessageForMe(message.id)
                        }
                        com.vladimir.messenger.ui.components.ApuActionBubble(
                            "Удалить у всех",
                            androidx.compose.material.icons.Icons.Filled.Delete,
                            destructive = true,
                        ) {
                            showCopyDialog = null
                            deleteForAllTarget = message
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCopyDialog = null }) { Text("Закрыть") }
            },
        )
    }

    // Раунд 203: «Поделиться в APU» - выбрать друга, группу+тему или
    // канал+пост; над пересылаемым текстом пишется ссылка на источник.
    forwardMessage?.let { original ->
        val fCtx = androidx.compose.ui.platform.LocalContext.current
        var fLoading by remember { mutableStateOf(true) }
        var fTargets by remember {
            mutableStateOf<List<com.vladimir.messenger.ui.components.ForwardTarget>>(emptyList())
        }
        var fPicked by remember { mutableStateOf<com.vladimir.messenger.ui.components.ForwardTarget?>(null) }
        var fTopics by remember {
            mutableStateOf<List<com.vladimir.messenger.ui.components.ForwardTopic>>(emptyList())
        }
        var fTopicsLoading by remember { mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(original.id) {
            fTargets = viewModel.forwardTargets()
            fLoading = false
        }
        androidx.compose.runtime.LaunchedEffect(fPicked?.id) {
            val picked = fPicked ?: return@LaunchedEffect
            fTopicsLoading = true
            fTopics = viewModel.forwardTopics(picked.id)
            fTopicsLoading = false
        }
        val sourceLabel = contactName.ifBlank { "чат" }
        com.vladimir.messenger.ui.components.ForwardChooserDialog(
            targets = fTargets,
            loading = fLoading,
            onDismiss = { forwardMessage = null },
            onPick = { target ->
                if (target.kind == com.vladimir.messenger.ui.components.ForwardKind.FRIEND) {
                    forwardMessage = null
                    viewModel.forwardMessage(original.content, sourceLabel, target) { ok ->
                        if (ok) {
                            fwdSent = true
                        } else {
                            Toast.makeText(fCtx, "Не удалось переслать", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    fPicked = target
                }
            },
        )
        fPicked?.let { target ->
            com.vladimir.messenger.ui.components.ForwardTopicPickerDialog(
                targetTitle = target.title,
                isChannel = target.isChannel,
                topics = fTopics,
                loading = fTopicsLoading,
                onDismiss = { fPicked = null },
                onPick = { topic ->
                    fPicked = null
                    forwardMessage = null
                    viewModel.forwardMessage(
                        original.content,
                        sourceLabel,
                        target.copy(topicId = topic.id, topicTitle = topic.name),
                    ) { ok ->
                        if (ok) {
                            fwdSent = true
                        } else {
                            Toast.makeText(fCtx, "Не удалось переслать", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
        }
    }

    // Раунд 135: подтверждение удаления у всех - сообщение пропадёт и у
    // собеседника (он должен быть на связи; иначе - честный отказ).
    deleteForAllTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteForAllTarget = null },
            title = { Text("Удалить у всех?") },
            text = { Text("Сообщение исчезнет и у вас, и у собеседника. Отменить будет нельзя.") },
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

    selectTextOf?.let { body ->
        com.vladimir.messenger.ui.components.SelectTextDialog(
            text = body,
            onDismiss = {
                selectTextOf = null
                resetSelection()
            },
        )
    }

    reactionFor?.let { messageId ->
        val mine = uiState.reactions[messageId].orEmpty()
            .firstOrNull { it.mine }?.emoji
        com.vladimir.messenger.ui.components.ReactionPickerDialog(
            onDismiss = { reactionFor = null },
            myEmoji = mine,
            onRemove = {
                viewModel.removeReaction(messageId)
                reactionFor = null
                resetSelection()
            },
            onPick = { emoji ->
                viewModel.toggleReaction(messageId, emoji)
                reactionFor = null
                resetSelection()
            },
        )
    }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun MessageInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isSending: Boolean,
    /** р241: переписка с собственным узлом - отправлять здесь нечего. */
    isSelfChat: Boolean = false,
    onAttach: () -> Unit = {},
    isPreparingFile: Boolean = false,
    canAttach: Boolean = true,
    onPastedMedia: (android.net.Uri) -> Unit = {},
    onPasteLocked: () -> Unit = {},
    onGifClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Раунд 42: новый BasicTextField(state) - только он проводит картинки со
    // стикер-клавиатуры в contentReceiver. Старый TextField их не принимал, и
    // система показывала тост «Приложение не поддерживает вставку изображений».
    val inputState = rememberTextFieldState(text)
    LaunchedEffect(text) {
        if (inputState.text.toString() != text) {
            inputState.edit { replace(0, length, text) }
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { inputState.text.toString() }.collect { onTextChange(it) }
    }
    // Раунд 45: подложка панели следует теме (светлая/тёмная) и полупрозрачна -
    // обои (фирменные или свои) проходят сквозь неё. Белые пузыри скрепки,
    // поля и стрелки читаются на любом фоне.
    // Раунд 266: фирменный пузырь панели ввода - светлая полупрозрачная
    // подложка с золотой рамкой вместо серого «квадрата» на обоях.
    // Раунд 149: imePadding - панель поднимается над клавиатурой.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFF5F7FA).copy(alpha = 0.92f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                shape = RoundedCornerShape(18.dp),
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        // Раунд 148: как в темах - при наборе скрепка и GIF уходят НАД
        // полем, «Отправить» - своим пузырём во всю ширину ПОД полем.
        var inputFocused by remember { mutableStateOf(false) }
        Column {
            if (inputFocused) {
                Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                        RoundedCornerShape(14.dp),
                    ),
            ) {
            IconButton(
                onClick = onAttach,
                enabled = !isPreparingFile && !isSending,
                modifier = Modifier.size(48.dp),
            ) {
                if (isPreparingFile) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        Icons.Default.AttachFile,
                        contentDescription = if (canAttach) {
                            "Прикрепить файл"
                        } else {
                            "Вложения откроются с ранга «Круг друзей»"
                        },
                        tint = if (canAttach) {
                            Color(0xFF5A6472)
                        } else {
                            Color(0xFF9AA3AF)
                        },
                    )
                }
            }
            }

            // Каталог GIF (v11.74.15): ОТДЕЛЬНАЯ кнопка рядом со скрепкой,
            // те же права, что у вложений. Раньше кнопка была вложена внутрь
            // IconButton скрепки и накладывалась на неё.
                TextButton(
                    onClick = onGifClick,
                    enabled = !isPreparingFile && !isSending && canAttach,
                ) {
                    Text(
                        "GIF",
                        fontWeight = FontWeight.Bold,
                        color = if (canAttach) MaterialTheme.colorScheme.primary else Color(0xFF9AA3AF),
                    )
                }
                }
            }

            BasicTextField(
                state     = inputState,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = Color(0xFF1E2430),
                ),
                cursorBrush = SolidColor(Color(0xFF1E2430)),
                decorator   = object : TextFieldDecorator {
                    @Composable
                    override fun Decoration(content: @Composable () -> Unit) {
                        Box(
                            modifier = Modifier
                                .apuBubbleSurface(color = Color.White)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            if (inputState.text.isEmpty()) {
                                Text(
                                    "Сообщение...",
                                    color = Color(0xFF5A6472),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                            content()
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { inputFocused = it.isFocused }
                    // Раунд 41: стикеры/картинки/гифки с клавиатуры (Gboard и
                    // др.) вставляются прямо в поле. Раньше система писала
                    // «приложение не поддерживает вставку изображений».
                    // Вложения открываются с ранга «Круг друзей» (3) - тем же
                    // правилом, что и кнопка скрепки.
                    .contentReceiver { transferableContent ->
                        if (!transferableContent.hasMediaType(MediaType.Image)) {
                            return@contentReceiver transferableContent
                        }
                        if (!canAttach || isPreparingFile || isSending) {
                            onPasteLocked()
                            // Забираем картинки себе - системный тост не нужен.
                            return@contentReceiver transferableContent.consume { it.uri != null }
                        }
                        transferableContent.consume { item ->
                            if (item.uri != null) {
                                onPastedMedia(item.uri!!)
                                true
                            } else {
                                false
                            }
                        }
                    },
            )

            Spacer(modifier = Modifier.height(6.dp))

            // р241: переписка с собственным узлом. Такое случается, если в
            // контакты попал свой же адрес (в профиле есть «Мой QR» - его
            // легко отсканировать самому). Объясняем честно: собеседника
            // здесь нет, а устройства одной личности синхронизируются сами.
            if (isSelfChat) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFFFF3CD))
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = "Это ваш собственный узел — собеседника здесь нет. " +
                            "Устройства одного аккаунта синхронизируются сами " +
                            "(Настройки → «Диагностика синхронизации»).",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF1E2430),
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Раунд 152: активность читаем НАПРЯМУЮ из inputState - раньше
            // она шла через родителя (snapshotFlow -> onTextChange ->
            // recomposition), и кнопка активировалась с задержкой; плюс
            // золотая заливка и белый текст (как в темах) - видно сразу.
            val canSend = inputState.text.isNotBlank() && !isSending && !isSelfChat
            TextButton(
                onClick = onSend,
                enabled = canSend,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        if (canSend) MaterialTheme.colorScheme.primary
                        else Color.White.copy(alpha = 0.85f)
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

@Composable
private fun ChatHistoryError(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    ApuBubbleCard(modifier = modifier.padding(16.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(message, color = ApuBubbleTextColor, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Попробуйте открыть историю ещё раз",
                color = ApuBubbleMutedColor,
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick = onRetry,
                colors = ButtonDefaults.textButtonColors(contentColor = ApuBubbleLinkColor),
            ) { Text("Повторить") }
        }
    }
}
