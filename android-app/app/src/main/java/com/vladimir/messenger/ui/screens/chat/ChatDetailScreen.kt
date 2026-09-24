package com.vladimir.messenger.ui.screens.chat

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
    viewModel: ChatDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var activeMessage by remember { mutableStateOf<Message?>(null) }
    var showCopyDialog by remember { mutableStateOf<Message?>(null) }
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
            onSticker = { viewModel.sendSticker(it) },
            onAddSticker = { uri -> viewModel.addSticker(uri) },
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
        Scaffold(
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
                    // Раунд 41: имя контакта/группы на белой полосочке со
                    // скруглениями и золотой рамкой - читается на любой подложке.
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xFFF5F7FA).copy(alpha = 0.92f))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                RoundedCornerShape(18.dp),
                            )
                            // Тап по имени открывает карточку собеседника.
                            .clickable { showPeerProfile = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Column {
                            Text(
                                contactName,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF1E2430),
                            )
                            Text(
                                text  = if (uiState.isContactOnline) "в сети" else "не в сети",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (uiState.isContactOnline)
                                    MaterialTheme.colorScheme.primary
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
                                contentDescription = "Rename",
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
                uiState.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                uiState.messages.isEmpty() -> {
                    Column(
                        modifier            = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint     = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Сообщения зашифрованы E2E",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Напишите первое сообщение",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                    // Раунд 128: гифки ходят ТИХО (по ссылкам) -
                                    // служебную передачу байтов в ленте не показываем.
                                    !it.mediaType.equals("image/gif", ignoreCase = true)
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
                    LazyColumn(
                        state          = listState,
                        modifier       = Modifier.fillMaxSize(),
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
                                if (com.vladimir.messenger.data.gif.GifLibrary.isGifRef(message.content)) {
                                    com.vladimir.messenger.ui.components.GifRefCard(
                                        content = message.content,
                                        modifier = Modifier.align(
                                            if (message.isFromMe) Alignment.End else Alignment.Start
                                        ),
                                        onEnsure = { sha -> viewModel.ensureGifRef(sha) },
                                    )
                                } else MessageBubble(
                                    message = message,
                                    isSelected = activeMessage?.id == message.id,
                                    linkColor = if (message.isFromMe) Color.White else Color(0xFF4A90E2),
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
                                    }
                                )
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
                    Text(
                        message.content.take(100) +
                            if (message.content.length > 100) "..." else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
                            val clip = ClipData.newPlainText("Сообщение", message.content)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                            showCopyDialog = null
                            resetSelection()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Копировать всё", modifier = Modifier.fillMaxWidth())
                    }
                    TextButton(
                        onClick = {
                            showCopyDialog = null
                            selectTextOf = message.content
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Выделить часть текста", modifier = Modifier.fillMaxWidth())
                    }
                    // Раунд 124: у файла/гифки в избранное кладётся САМ ФАЙЛ
                    // (ссылка на принятую передачу), а не пустой текст.
                    val savedMessageFile = uiState.transfers.firstOrNull {
                        it.messageId == message.id &&
                            it.direction == "INCOMING" &&
                            it.state == "COMPLETE"
                    }
                    if (savedMessageFile != null) {
                        TextButton(
                            onClick = {
                                viewModel.saveToFavorites(savedMessageFile, contactName)
                                showCopyDialog = null
                                resetSelection()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Файл в избранное", modifier = Modifier.fillMaxWidth())
                        }
                    }
                    if (message.content.isNotBlank()) {
                        TextButton(
                            onClick = {
                                viewModel.saveTextToFavorites(message.content, contactName)
                                showCopyDialog = null
                                resetSelection()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("В избранное", modifier = Modifier.fillMaxWidth())
                        }
                    }
                    TextButton(
                        onClick = {
                            showCopyDialog = null
                            reactionFor = message.id
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Поставить реакцию", modifier = Modifier.fillMaxWidth())
                    }
                    // Раунд 135: удаление своего сообщения - у себя и у всех.
                    if (message.isFromMe) {
                        TextButton(
                            onClick = {
                                showCopyDialog = null
                                viewModel.deleteMessageForMe(message.id)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "Удалить у себя",
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        TextButton(
                            onClick = {
                                showCopyDialog = null
                                deleteForAllTarget = message
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "Удалить у всех",
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCopyDialog = null }) { Text("Закрыть") }
            },
        )
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
    Surface(
        // Раунд 149: imePadding - панель поднимается над клавиатурой
        // (edge-to-edge: adjustResize сам не работает, владелец прислал
        // скрин с закрытой клавиатурой поля и «Отправить»).
        modifier  = modifier.fillMaxWidth().imePadding(),
        shadowElevation = 8.dp,
        color     = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
    ) {
        // Раунд 148: как в темах - при наборе скрепка и GIF уходят НАД
        // полем, «Отправить» - своим пузырём во всю ширину ПОД полем.
        var inputFocused by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
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
                                .background(
                                    Color.White,
                                    MaterialTheme.shapes.extraLarge,
                                )
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

            TextButton(
                onClick = onSend,
                enabled = text.isNotBlank() && !isSending,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.85f))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                        RoundedCornerShape(18.dp),
                    )
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    "Отправить",
                    fontWeight = FontWeight.SemiBold,
                    color = if (text.isNotBlank() && !isSending) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color(0xFF9AA3AF)
                    },
                )
            }
        }
    }
}
