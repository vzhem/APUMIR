package com.vladimir.messenger.ui.screens.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.file.FileTransferRankPolicy
import com.vladimir.messenger.data.file.FileTransferRouter
import com.vladimir.messenger.data.file.OutgoingFilePreparationService
import com.vladimir.messenger.data.local.dao.FileTransferDao
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import com.vladimir.messenger.data.referral.ReferralRankStore
import com.vladimir.messenger.data.repository.ChatRepository
import com.vladimir.messenger.domain.model.Message
import com.vladimir.messenger.domain.usecase.GetMessagesUseCase
import com.vladimir.messenger.domain.usecase.SendMessageUseCase
import com.vladimir.messenger.domain.usecase.MarkAsReadUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ChatDetailUiState(
    val messages: List<Message> = emptyList(),
    /** Раунд 173: закреплённые сообщения чата (свежие вверху). */
    val pinned: List<Message> = emptyList(),
    val transfers: List<FileTransferEntity> = emptyList(),
    val inputText: String       = "",
    val isLoading: Boolean      = true,
    val isSending: Boolean      = false,
    val isPreparingFile: Boolean = false,
    val error: String?          = null,
    val isContactOnline: Boolean = false,
    /** @никнейм собеседника: показывает карточка профиля. */
    val contactUsername: String = "",
    /** Сердечки профиля собеседника: рейтинг популярности. */
    val heartCount: Int = 0,
    val heartMine: Boolean = false,
    val scrollToBottom: Boolean = false,
    val pendingSave: FileTransferEntity? = null,
    /** Ранг ещё не открыл вложения: кнопка объяснит это сразу, а не после выбора файла. */
    val canSendAttachments: Boolean = true,
    val attachmentsLockedHint: String = "",
    /** Каталог GIF (наш сервер): гифки, курсор «ещё», состояние. */
    val gifItems: List<com.vladimir.messenger.data.gif.GifItem> = emptyList(),
    val gifNext: String = "",
    val gifLoading: Boolean = false,
    val gifError: String? = null,
    /** Раунд 121: свой каталог роя. tab: "swarm" | "external". */
    val gifTab: String = "swarm",
    val myGifs: List<com.vladimir.messenger.data.gif.GifLibEntry> = emptyList(),
    val swarmGifs: List<com.vladimir.messenger.data.gif.SwarmGif> = emptyList(),
    val swarmStatus: String? = null,
    /** Реакции по сообщениям: ключ - id сообщения. */
    val reactions: Map<String, List<com.vladimir.messenger.data.reaction.ReactionSummary>> = emptyMap(),
)

@HiltViewModel
class ChatDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getMessagesUseCase: GetMessagesUseCase,
    private val sendMessageUseCase: SendMessageUseCase,
    private val markAsReadUseCase: MarkAsReadUseCase,
    private val chatRepository: ChatRepository,
    private val filePreparation: OutgoingFilePreparationService,
    private val fileTransferDao: FileTransferDao,
    private val fileTransferRouter: FileTransferRouter,
    private val botApi: com.vladimir.messenger.service.BotApi,
    private val savedItems: com.vladimir.messenger.data.repository.SavedItemsRepository,
    private val reactionRepository: com.vladimir.messenger.data.reaction.ReactionRepository,
    private val contactDao: com.vladimir.messenger.data.local.dao.ContactDao,
    private val readReceipts: com.vladimir.messenger.data.receipt.ReadReceiptRepository,
    private val hearts: com.vladimir.messenger.data.heart.HeartRepository,
    private val messageDeletion: com.vladimir.messenger.data.repository.MessageDeletionRepository,
    private val stickerLibrary: com.vladimir.messenger.data.sticker.StickerLibrary,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    // chatId передаётся через навигацию (SavedStateHandle)
    private val chatId: String = checkNotNull(savedStateHandle["chatId"])

    private val _uiState = MutableStateFlow(ChatDetailUiState())
    val uiState: StateFlow<ChatDetailUiState> = _uiState.asStateFlow()

    init {
        refreshAttachmentRights()
        loadMessages()
        observePinned()
        observeContactPresence()
        observeTransfers()
        observeReactions()
        markAsRead()
        observeGifArrivals()
        observeStickerArrivals()
    }

    /** Раунд 121: гифка, которую ждали из роя, пришла - сразу отправить. */
    private fun observeGifArrivals() {
        viewModelScope.launch {
            // Раунд 128: гифка приезжает ТИХО (с хранителей) - карточки-ссылки
            // в чате оживают сами (GifRefCard слушает arrivals). Здесь только
            // гасим статус в окне каталога.
            com.vladimir.messenger.data.gif.GifLibrary.arrivalsFlow().collect { _ ->
                _uiState.update { it.copy(swarmStatus = null) }
            }
        }
    }

    /** Реакции чата: поток уже уведён на IO внутри репозитория. */
    private fun observeReactions() {
        viewModelScope.launch {
            reactionRepository.observeChat(chatId).collect { map ->
                _uiState.update { it.copy(reactions = map) }
            }
        }
    }

    /** Поставить или снять реакцию на сообщение. */
    fun toggleReaction(messageId: String, emoji: String) {
        viewModelScope.launch { reactionRepository.toggle(chatId, messageId, emoji) }
    }

    /** Убрать свою реакцию, какой бы она ни была. */
    fun removeReaction(messageId: String) {
        viewModelScope.launch { reactionRepository.removeMine(chatId, messageId) }
    }

    /**
     * Шапка лички раньше навсегда рисовала «не в сети»: статус в uiState
     * никем не обновлялся, а peer_discovered писал только в таблицу contacts.
     * Слушаем строку чата в БД — peer_discovered/peer_lost её же и обновляют.
     */
    private fun observeContactPresence() {
        viewModelScope.launch {
            chatRepository.observeChat(chatId).collect { chat ->
                if (chat != null) {
                    // Заодно подтягиваем @никнейм: он живёт в таблице контактов
                    // и нужен карточке профиля. В списке чатов его намеренно
                    // нет - там только имя.
                    val nick = runCatching {
                        contactDao.getContactById(chat.contactId)?.username.orEmpty()
                    }.getOrDefault("")
                    _uiState.update {
                        it.copy(isContactOnline = chat.isContactOnline, contactUsername = nick)
                    }
                    // Сердечки заводим здесь: только тут точно известен адрес
                    // собеседника (в личном чате это contactId).
                    if (!heartsWatched && chat.contactId.isNotBlank()) {
                        heartsWatched = true
                        observeHearts(chat.contactId)
                    }
                }
            }
        }
    }

    /**
     * Отправка файлов, фото, видео, GIF и стикеров открывается с ранга
     * «Круг друзей» (3 подтверждённых приглашения). Текст доступен всегда.
     */
    private fun refreshAttachmentRights() {
        val qualified = ReferralRankStore.qualifiedDirectCount(appContext)
        val allowed = FileTransferRankPolicy.canSendAttachments(qualified)
        _uiState.update {
            it.copy(
                canSendAttachments = allowed,
                attachmentsLockedHint = if (allowed) {
                    ""
                } else {
                    "Отправка файлов, фото и видео открывается с ранга «Круг друзей» — " +
                        "это 3 подтверждённых приглашения. Сейчас подтверждено: $qualified. " +
                        "Текстовые сообщения доступны без ограничений."
                },
            )
        }
    }

    /** Тап по скрепке при закрытых вложениях: объясняем, а не открываем выбор файла. */
    fun onAttachmentsLocked() {
        _uiState.update { it.copy(error = it.attachmentsLockedHint) }
    }

    /** Раунд 173: закреплённые сообщения - живой поток в шапку чата. */
    private fun observePinned() {
        viewModelScope.launch {
            chatRepository.observePinnedChatMessages(chatId).collect { pinned ->
                _uiState.update { it.copy(pinned = pinned) }
            }
        }
    }

    /** Раунд 173: закрепить/открепить сообщение личного чата. */
    fun togglePin(messageId: String, pinned: Boolean) {
        viewModelScope.launch {
            runCatching { chatRepository.setMessagePinned(messageId, pinned) }
        }
    }

    private fun loadMessages() {
        android.util.Log.i("ChatDetailVM", "🔍 loadMessages for chatId=$chatId")
        viewModelScope.launch {
            getMessagesUseCase(chatId)
                .collect { messages ->
                    android.util.Log.i("ChatDetailVM", "📥 Received ${messages.size} messages for chatId=$chatId")
                    // Показать последние 5 сообщений
                    messages.takeLast(5).forEach { msg ->
                        android.util.Log.i("ChatDetailVM", "  🔹 msg: id=${msg.id.take(8)} isFromMe=${msg.isFromMe} status=${msg.status} content=${msg.content.take(20)}")
                    }
                    val wasEmpty = _uiState.value.messages.isEmpty()
                    _uiState.update { state ->
                        state.copy(
                            messages      = messages,
                            isLoading     = false,
                            // Автопрокрутка при первой загрузке или новом сообщении
                            scrollToBottom = wasEmpty || messages.lastOrNull()?.isFromMe == true
                        )
                    }
                }
        }
    }

    private fun observeTransfers() {
        viewModelScope.launch {
            fileTransferDao.observeForChat(chatId).collect { transfers ->
                _uiState.update { state ->
                    state.copy(
                        transfers = transfers,
                        scrollToBottom = state.scrollToBottom ||
                            transfers.any { it.state == "OFFERED" || it.state == "PREPARING" },
                    )
                }
            }
        }
    }

    /**
     * Сердечки собеседника. Счётчик слушаем: голоса приходят по сети в любой
     * момент, и карточка должна обновляться сама.
     */
    /** Чтобы не подписаться на счётчик дважды при каждом обновлении чата. */
    private var heartsWatched = false

    private fun observeHearts(peerId: String) {
        if (peerId.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(heartMine = hearts.isMine(peerId)) }
        }
        viewModelScope.launch {
            hearts.observeCount(peerId).collect { count ->
                _uiState.update { it.copy(heartCount = count) }
            }
        }
    }

    /** Поставить или снять сердечко профилю собеседника. */
    fun onHeartClick(peerId: String) {
        if (peerId.isBlank()) return
        viewModelScope.launch {
            val mine = hearts.toggle(peerId)
            _uiState.update { it.copy(heartMine = mine) }
        }
    }

    private fun markAsRead() {
        viewModelScope.launch {
            markAsReadUseCase(chatId)
            // И сообщаем собеседнику: у него галочки станут синими. Сбой не
            // должен мешать открытию чата, поэтому ошибка только в журнал.
            runCatching { readReceipts.reportRead(chatId) }
        }
    }

    fun onInputTextChanged(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun onSendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true, inputText = "") }

            sendMessageUseCase(chatId, text)
                .onSuccess {
                    _uiState.update { it.copy(
                        isSending      = false,
                        scrollToBottom = true
                    )}
                }
                .onFailure { e ->
                    _uiState.update { it.copy(
                        isSending = false,
                        inputText = text,  // Восстанавливаем текст при ошибке
                        error     = "Ошибка отправки: ${e.message}"
                    )}
                }
        }
    }

    /**
     * F3: pick → rank-checked encrypted preparation (manifest, key envelope, durable chunks) →
     * local chat placeholder → immediate pump. Offline multi-day delivery is owned by the
     * durable transport, not by this UI path.
     */
    /** Раунд 43: превью картинки для пузыря передачи файла. */
    fun previewFileFor(transfer: com.vladimir.messenger.data.local.entity.FileTransferEntity): java.io.File? {
        // Раунд 172: стикеру нужен ПОЛНЫЙ файл (webm/webp анимация). Прежний
        // порядок сначала отдавал jpg-снимок роутера - чёрный квадрат вместо
        // анимации; теперь библиотечный стикер - первый источник.
        if (transfer.displayName.startsWith("Стикер")) {
            stickerLibrary.fileOf(transfer.fileSha256)?.let { return it }
        }
        val routerFile = fileTransferRouter.previewFileFor(transfer)
        if (routerFile != null) return routerFile
        if (transfer.direction == "OUTGOING" && transfer.displayName.startsWith("Стикер")) {
            return stickerLibrary.fileOf(transfer.fileSha256)
        }
        return null
    }

    /**
     * Раунд 44: «Поделиться» картинкой из пузыря: копирую файл в cache и
     * отдаю системному меню через FileProvider.
     */
    fun shareTransferFile(transfer: com.vladimir.messenger.data.local.entity.FileTransferEntity) {
        val src = fileTransferRouter.previewFileFor(transfer) ?: return
        val ctx = appContext
        viewModelScope.launch {
            runCatching {
                val dir = java.io.File(ctx.cacheDir, "shared").apply { mkdirs() }
                val dst = java.io.File(dir, transfer.displayName)
                src.copyTo(dst, overwrite = true)
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    ctx, ctx.packageName + ".fileprovider", dst,
                )
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType(transfer.mediaType)
                    .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ctx.startActivity(
                    android.content.Intent.createChooser(intent, "Поделиться")
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { _uiState.update { st -> st.copy(error = "Не удалось поделиться") } }
        }
    }

    fun onFileSelected(uri: Uri) {
        if (_uiState.value.isPreparingFile) return
        // Второй рубеж: даже если кнопку обошли, подготовка файла не пройдёт.
        if (!_uiState.value.canSendAttachments) {
            _uiState.update { it.copy(error = it.attachmentsLockedHint) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isPreparingFile = true) }
            var targetRecipientId: String? = null
            try {
                val chat = chatRepository.getChatById(chatId)
                    ?: error("Чат недоступен")
                val recipientId = chat.contactId
                targetRecipientId = recipientId
                check(recipientId.startsWith("pk_")) { "У контакта нет ключа для передачи файлов" }
                val messageId = UUID.randomUUID().toString()
                val prepared = filePreparation.prepare(
                    source = uri,
                    messageId = messageId,
                    chatId = chatId,
                    recipientNodeId = recipientId,
                    qualifiedDirectReferrals = ReferralRankStore.qualifiedDirectCount(appContext),
                )
                chatRepository.insertLocalFileMessage(
                    chatId = chatId,
                    recipientId = recipientId,
                    messageId = messageId,
                    content = FileTransferRouter.formatPlaceholder(
                        prepared.displayName,
                        prepared.mediaType,
                        prepared.totalBytes,
                    ),
                    timestamp = System.currentTimeMillis(),
                )
                _uiState.update { it.copy(scrollToBottom = true) }
                fileTransferRouter.pumpOutgoing()
            } catch (e: Exception) {
                android.util.Log.w("ChatDetailVM", "File prepare failed", e)
                val message = e.message.orEmpty()
                if (message.contains("binding is not pinned")) {
                    // First contact between these phones for files: push our signed HELLO so the
                    // recipient can pin us and reply; durable transport delivers it when online.
                    targetRecipientId?.let { fileTransferRouter.requestExchangeBinding(it) }
                    _uiState.update {
                        it.copy(error = "Ключ получателя ещё не закреплён. Отправил запрос — попробуйте снова через пару минут.")
                    }
                } else {
                    _uiState.update { it.copy(error = "Файл не отправлен: ${e.message}") }
                }
            } finally {
                _uiState.update { it.copy(isPreparingFile = false) }
            }
        }
    }

    // ── Каталог GIF (наш сервер -> Tenor/Giphy; отправка как файл) ──

    fun searchGifs(query: String, more: Boolean = false) {
        if (_uiState.value.gifLoading) return
        if (!more) lastGifQuery = query
        val pos = if (more) _uiState.value.gifNext else ""
        _uiState.update {
            it.copy(
                gifLoading = true,
                gifError = null,
                gifItems = if (more) it.gifItems else emptyList(),
            )
        }
        viewModelScope.launch {
            val result = runCatching { botApi.gifSearch(query, pos) }.getOrNull()
            _uiState.update { state ->
                if (result == null) {
                    state.copy(
                        gifLoading = false,
                        gifError = "Каталог недоступен: сервер не отвечает или ключ GIF ещё не настроен",
                    )
                } else {
                    val (items, next) = result
                    if (items.isEmpty() && state.gifItems.isEmpty()) {
                        state.copy(gifLoading = false, gifError = "Ничего не нашлось")
                    } else {
                        state.copy(
                            gifLoading = false,
                            gifError = null,
                            gifItems = (state.gifItems + items).distinctBy { it.id },
                            gifNext = next,
                        )
                    }
                }
            }
        }
    }

    fun closeGifCatalog() {
        _uiState.update { it.copy(gifItems = emptyList(), gifNext = "", gifError = null) }
    }

    // ── Свой каталог роя (раунд 121) ────────────────────────────────────

    private var lastGifQuery: String = ""

    /** Открыли окно гифок: подтянуть мою библиотеку и каталог роя. */
    // ── Стикеры (раунд 138): единая панель ввода ────────────────────────────

    /** Мои стикеры для панели. */
    private val _stickerEntries = MutableStateFlow<List<com.vladimir.messenger.data.sticker.StickerLibrary.StickerEntry>>(emptyList())
    val stickerEntries: StateFlow<List<com.vladimir.messenger.data.sticker.StickerLibrary.StickerEntry>> = _stickerEntries.asStateFlow()

    /** Недавние стикеры для панели. */
    private val _stickerRecents = MutableStateFlow<List<com.vladimir.messenger.data.sticker.StickerLibrary.StickerEntry>>(emptyList())
    val stickerRecents: StateFlow<List<com.vladimir.messenger.data.sticker.StickerLibrary.StickerEntry>> = _stickerRecents.asStateFlow()

    /** Стикеры других телефонов роя для панели («Из сети»). */
    private val _swarmStickers = MutableStateFlow<List<com.vladimir.messenger.data.sticker.SwarmSticker>>(emptyList())
    val swarmStickers: StateFlow<List<com.vladimir.messenger.data.sticker.SwarmSticker>> = _swarmStickers.asStateFlow()

    /** sha стикера из сети, который ждём, чтобы сразу отправить (раунд 139). */
    private var pendingSwarmStickerSha: String? = null

    /** Перечитать библиотеку стикеров (панель открылась / добавили). */
    fun refreshStickers() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _stickerEntries.value = stickerLibrary.all()
            _stickerRecents.value = stickerLibrary.recents()
            // Раунд 139: каталог роя - рассказать о себе и спросить чужие,
            // слить каталоги хранителей, подтянуть миниатюры для сетки.
            runCatching {
                stickerLibrary.syncWithSwarm(chatRepository, force = false)
            }
            val mine = _stickerEntries.value.map { it.sha256 }.toSet()
            val swarm = runCatching {
                com.vladimir.messenger.data.sticker.StickerLibrary.swarmCatalog(appContext, mine)
            }.getOrDefault(emptyList())
            _swarmStickers.value = swarm
            for (s in swarm.take(40)) {
                if (com.vladimir.messenger.data.sticker.StickerLibrary
                    .tinyThumbFile(appContext, s.sha256) == null
                ) {
                    runCatching {
                        com.vladimir.messenger.data.sticker.StickerLibrary.requestThumb(
                            appContext, chatRepository, s.sha256, s.holders,
                        )
                    }
                }
            }
        }
    }

    /** Добавить свой стикер из хранилища телефона. */
    fun addSticker(uri: Uri) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { stickerLibrary.add(uri) }
            announceAdded()
            refreshStickers()
        }
    }

    /** Раунд 165: альбом стикеров .zip - в библиотеку, сетка обновится. */
    fun addStickerZip(uri: Uri) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { stickerLibrary.addZip(uri) }
            announceAdded()
            refreshStickers()
        }
    }

    /**
     * Раунд 167: добавили стикеры (.zip или по одному) - объявить свой
     * каталог рою СРАЗУ: абоненты увидят их в «Из сети» без ожидания.
     */
    private suspend fun announceAdded() {
        runCatching { stickerLibrary.syncWithSwarm(chatRepository, force = true) }
    }

    /**
     * Раунд 174: удалить свой стикер случайно закинули). Из библиотеки,
     * каталог роя переобъявляется сразу - у абонентов исчезнет из
     * «Из сети». Кто уже скачал - у того остаётся (E2E).
     */
    fun removeSticker(entry: com.vladimir.messenger.data.sticker.StickerLibrary.StickerEntry) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { stickerLibrary.deleteBySha(entry.sha256) }
            announceAdded()
            refreshStickers()
        }
    }

    /**
     * Раунд 174: удалить свою гифку из библиотеки и роевого каталога.
     */
    fun removeOwnGif(sha256: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                com.vladimir.messenger.data.gif.GifLibrary.deleteOwn(appContext, sha256)
            }
            runCatching {
                com.vladimir.messenger.data.gif.GifLibrary.syncWithSwarm(
                    appContext, chatRepository, force = true,
                )
            }
            onGifCatalogOpened()
        }
    }

    /**
     * Отправить стикер в личный чат: картинкой (та же файловая машина, что у
     * скрепки), но сразу - без диалога выбора. Стикер встаёт в «Недавние».
     */
    fun sendSticker(entry: com.vladimir.messenger.data.sticker.StickerLibrary.StickerEntry) {
        if (_uiState.value.isPreparingFile) return
        if (!_uiState.value.canSendAttachments) {
            _uiState.update { it.copy(error = it.attachmentsLockedHint) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isPreparingFile = true, error = null) }
            var targetRecipientId: String? = null
            try {
                val chat = chatRepository.getChatById(chatId) ?: error("Чат недоступен")
                val recipientId = chat.contactId
                targetRecipientId = recipientId
                check(recipientId.startsWith("pk_")) { "У контакта нет ключа для передачи файлов" }
                val messageId = UUID.randomUUID().toString()
                // Раунд 166/170: честный тип и имя - на карточке и в
                // уведомлениях «Стикер.webp»/«Стикер.webm», не sha-строка.
                val webm = com.vladimir.messenger.data.sticker.StickerLibrary.isWebmFile(entry.file)
                val prepared = filePreparation.prepareFromFile(
                    source = entry.file,
                    displayName = if (webm) "Стикер.webm" else "Стикер.webp",
                    mediaType = if (webm) "video/webm" else "image/webp",
                    messageId = messageId,
                    chatId = chatId,
                    recipientNodeId = recipientId,
                )
                chatRepository.insertLocalFileMessage(
                    chatId = chatId,
                    recipientId = recipientId,
                    messageId = messageId,
                    content = FileTransferRouter.formatPlaceholder(
                        prepared.displayName,
                        prepared.mediaType,
                        prepared.totalBytes,
                    ),
                    timestamp = System.currentTimeMillis(),
                )
                _uiState.update { it.copy(scrollToBottom = true) }
                fileTransferRouter.pumpOutgoing()
                // Раунд 171: запись recents - файловый ввод-вывод, в IO.
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    stickerLibrary.touch(entry.sha256)
                }
                refreshStickers()
            } catch (e: Exception) {
                android.util.Log.w("ChatDetailVM", "sticker send failed", e)
                targetRecipientId?.takeIf { _uiState.value.error == null }?.let {
                    fileTransferRouter.requestExchangeBinding(it)
                }
                _uiState.update {
                    it.copy(error = "Стикер не отправлен: ${e.message.orEmpty()}")
                }
            } finally {
                _uiState.update { it.copy(isPreparingFile = false) }
            }
        }
    }

    /**
     * Раунд 139: выбрал стикер в «Из сети». Есть локально - сразу в чат;
     * нет - тихая просьба трём хранителям, байты приедут - отправим сами.
     */
    fun requestSwarmSticker(swarm: com.vladimir.messenger.data.sticker.SwarmSticker) {
        if (_uiState.value.isPreparingFile) return
        if (!_uiState.value.canSendAttachments) {
            _uiState.update { it.copy(error = it.attachmentsLockedHint) }
            return
        }
        viewModelScope.launch {
            val local = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                stickerLibrary.entryOf(swarm.sha256)
            }
            if (local != null) {
                sendSticker(local)
                return@launch
            }
            com.vladimir.messenger.data.sticker.StickerLibrary.rememberWant(swarm.sha256)
            pendingSwarmStickerSha = swarm.sha256
            val holder = runCatching {
                com.vladimir.messenger.data.sticker.StickerLibrary.requestSticker(
                    appContext, chatRepository, swarm.sha256, swarm.holders,
                )
            }.getOrNull()
            _uiState.update {
                it.copy(
                    swarmStatus = when (holder) {
                        null -> "Сеть пока не отвечает - попробуйте позже"
                        "" -> "Уже качаем этот стикер"
                        else -> "Качается с $holder - сейчас отправим"
                    },
                )
            }
        }
    }

    /** Раунд 139: стикер, которого ждали из роя, приехал - сразу отправить. */
    private fun observeStickerArrivals() {
        viewModelScope.launch {
            com.vladimir.messenger.data.sticker.StickerLibrary.arrivalsFlow().collect { sha ->
                _uiState.update { it.copy(swarmStatus = null) }
                refreshStickers()
                val wanted = pendingSwarmStickerSha
                if (wanted != null && wanted == sha) {
                    pendingSwarmStickerSha = null
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        stickerLibrary.entryOf(sha)
                    }?.let { sendSticker(it) }
                }
            }
        }
    }

    fun onGifCatalogOpened() {
        viewModelScope.launch {
            runCatching {
                com.vladimir.messenger.data.gif.GifLibrary.syncWithSwarm(
                    appContext, chatRepository, force = false,
                )
            }
            val my = runCatching {
                com.vladimir.messenger.data.gif.GifLibrary.entries(appContext)
            }.getOrDefault(emptyList())
            val swarm = runCatching {
                com.vladimir.messenger.data.gif.GifLibrary.swarmCatalog(appContext)
            }.getOrDefault(emptyList())
            _uiState.update {
                it.copy(
                    gifTab = if (my.isNotEmpty() || swarm.isNotEmpty()) "swarm" else "external",
                    myGifs = my,
                    swarmGifs = swarm,
                )
            }
        }
    }

    fun setGifTab(tab: String) {
        _uiState.update { it.copy(gifTab = tab) }
    }
    /**
     * Раунд 124: СВОЯ гифка из хранилища телефона. Ложится в библиотеку
     * (превью + индекс), объявляется в каталоге нашей сети - теперь она
     * есть у всех телефонов, без внешнего ресурса.
     */
    fun addOwnGif(uri: android.net.Uri) {
        viewModelScope.launch {
            val added = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val bytes = appContext.contentResolver.openInputStream(uri)
                        ?.use { input -> input.readBytes() }
                        ?: return@withContext null
                    if (bytes.isEmpty() || bytes.size > 30 * 1024 * 1024) return@withContext null
                    val name = runCatching {
                        appContext.contentResolver.query(
                            uri, null, null, null, null,
                        )?.use { cursor ->
                            val idx = cursor.getColumnIndex(
                                android.provider.OpenableColumns.DISPLAY_NAME,
                            )
                            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
                        }
                    }.getOrNull()
                    com.vladimir.messenger.data.gif.GifLibrary.add(
                        appContext,
                        bytes,
                        null,
                        "своя",
                        name?.takeIf { it.isNotBlank() }
                            ?: "своя_${System.currentTimeMillis() / 1000}.gif",
                    )
                }.getOrNull()
            }
            if (added == null) {
                _uiState.update { it.copy(swarmStatus = "Не вышло: нужен файл GIF до 30 МБ") }
            } else {
                runCatching {
                    com.vladimir.messenger.data.gif.GifLibrary.syncWithSwarm(
                        appContext, chatRepository, force = false,
                    )
                }
                onGifCatalogOpened()
                _uiState.update { it.copy(swarmStatus = "Своя гифка добавлена - уже в нашей сети") }
            }
        }
    }


    /**
     * Раунд 128: гифка из моей библиотеки уходит ССЫЛКОЙ. В чате появляется
     * ОДНА карточка от моего лица; байты собеседник тихо подтянет с
     * хранителей (у меня они уже есть - я и хранитель).
     */
    fun attachLocalGif(sha256: String) {
        if (_uiState.value.isPreparingFile) return
        viewModelScope.launch {
            try {
                sendGifRefInternal(sha256)
            } catch (e: Exception) {
                val message = e.message.orEmpty()
                _uiState.update {
                    it.copy(error = if (message.contains("недоступен")) "Собеседник недоступен - попробуйте позже" else "Гифка не отправлена: $message")
                }
            }
        }
    }

    /**
     * Раунд 128: отправить ССЫЛКУ на гифку (от моего лица). Никаких файлов
     * по переписке: карточка одна, байты каждый телефон подтягивает тихо с
     * хранителей из каталога сети.
     */
    private suspend fun sendGifRefInternal(sha256: String) {
        val chat = chatRepository.getChatById(chatId) ?: error("Чат недоступен")
        val recipientId = chat.contactId
        val messageId = UUID.randomUUID().toString()
        val sent = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                RustBridge.sendMessage(
                    messageId, chatId, recipientId,
                    com.vladimir.messenger.data.gif.GifLibrary.refContent(sha256),
                )
            }.getOrDefault(false)
        }
        check(sent) { "телефон собеседника недоступен" }
        chatRepository.insertGifRefMessage(
            chatId = chatId,
            recipientId = recipientId,
            messageId = messageId,
            sha256 = sha256,
            timestamp = System.currentTimeMillis(),
        )
        _uiState.update { it.copy(scrollToBottom = true) }
        // Раунд 131: я мог только что скачать эту гифку - объявить каталог,
        // чтобы все узнали хранителя и смогли тихо забрать байты.
        runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.vladimir.messenger.data.gif.GifLibrary.syncWithSwarm(
                    appContext, chatRepository, force = false,
                )
            }
        }
        // Своя карточка тоже должна ожить: если гифки у меня нет - тихо
        // попросим у сети (тротлимб внутри GifLibrary).
        ensureGifRefInternal(sha256)
    }

    private val thumbRequestedAt = HashMap<String, Long>()

    /**
     * Раунд 129: подтянуть миниатюры чужих гифок для сетки каталога
     * (по одной просьбе - лучшему хранителю; тротлимб 5 минут/sha).
     */
    fun requestPeerThumbs(entries: List<com.vladimir.messenger.data.gif.SwarmGif>) {
        if (entries.isEmpty()) return
        viewModelScope.launch {
            for (sg in entries) {
                val sha = sg.entry.sha256
                if (com.vladimir.messenger.data.gif.GifLibrary.tinyThumbFile(appContext, sha) != null) continue
                val now = System.currentTimeMillis()
                if (now - (thumbRequestedAt[sha] ?: 0L) < 5 * 60_000L) continue
                thumbRequestedAt[sha] = now
                runCatching {
                    com.vladimir.messenger.data.gif.GifLibrary.requestThumb(
                        appContext, chatRepository, sha, sg.holders,
                    )
                }
            }
        }
    }

    /**
     * Раунд 128: убедиться, что гифка есть в моей библиотеке (для карточки-
     * ссылки). Нет - тихо попросить у хранителей сети.
     */
    fun ensureGifRef(sha256: String) {
        viewModelScope.launch { ensureGifRefInternal(sha256) }
    }

    private suspend fun ensureGifRefInternal(sha256: String) {
        val have = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.vladimir.messenger.data.gif.GifLibrary.gifFile(appContext, sha256)?.isFile == true
        }
        if (have) return
        val holders = runCatching {
            com.vladimir.messenger.data.gif.GifLibrary.swarmCatalog(appContext)
        }.getOrDefault(emptyList())
            .firstOrNull { it.entry.sha256 == sha256 }?.holders.orEmpty()
        if (holders.isEmpty()) return
        com.vladimir.messenger.data.gif.GifLibrary.rememberWant(sha256)
        runCatching {
            com.vladimir.messenger.data.gif.GifLibrary.requestGif(
                appContext, chatRepository, sha256, holders,
            )
        }
    }

    /**
     * Раунд 128: выбрал гифку из сети (синяя точка) - в чат уходит моя
     * ССЫЛКА. У меня гифка подтянется тихо, у собеседника - тоже.
     */
    fun requestSwarmGif(swarm: com.vladimir.messenger.data.gif.SwarmGif) {
        if (_uiState.value.isPreparingFile) return
        viewModelScope.launch {
            try {
                sendGifRefInternal(swarm.entry.sha256)
                _uiState.update { it.copy(swarmStatus = "Ссылка отправлена - гифка подтянется из сети") }
            } catch (e: Exception) {
                _uiState.update { it.copy(swarmStatus = "Собеседник недоступен - попробуйте позже") }
            }
        }
    }

    /** Выбрал гифку: скачать и отправить как файл (тот же путь, что скрепка). */
    /**
     * Раунд 128: выбрал гифку во внешнем каталоге - скачиваем ОДИН раз,
     * селим в библиотеку (телефон становится хранителем) и отправляем в чат
     * ССЫЛКОЙ. Байты собеседник подтянет тихо - с меня как с хранителя.
     */
    fun attachGif(item: com.vladimir.messenger.data.gif.GifItem) {
        if (_uiState.value.isPreparingFile) return
        viewModelScope.launch {
            _uiState.update { it.copy(isPreparingFile = true, error = null) }
            try {
                // Раунд 121: если гифка уже живёт в моей библиотеке (качали
                // раньше или получили из сети) - наружный ресурс не трогаем.
                var sha = com.vladimir.messenger.data.gif.GifLibrary
                    .shaForGiphyId(appContext, item.id)
                if (sha == null) {
                    val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        botApi.downloadGif(item.gif)
                    } ?: error("Гифка не скачалась")
                    val added = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.vladimir.messenger.data.gif.GifLibrary.add(
                            appContext, bytes, item.id, lastGifQuery, "gif_" + item.id + ".gif",
                        )
                    } ?: error("Гифка не сохранилась")
                    sha = added.sha256
                }
                sendGifRefInternal(sha)
            } catch (e: Exception) {
                android.util.Log.w("ChatDetailVM", "gif attach failed", e)
                _uiState.update { it.copy(error = "Гифка не отправлена: " + e.message.orEmpty()) }
            } finally {
                _uiState.update { it.copy(isPreparingFile = false) }
            }
        }
    }

    /**
     * Раунд 135: удалить своё сообщение только у себя.
     */
    fun deleteMessageForMe(messageId: String) {
        viewModelScope.launch {
            messageDeletion.deleteForMe(chatId, messageId)
                .onFailure { e -> _uiState.update { it.copy(error = e.message.orEmpty()) } }
        }
    }

    /**
     * Раунд 135: удалить своё сообщение у всех: конверт собеседнику и
     * стирание у себя. Ошибка (собеседник недоступен) - тостом, ничего
     * не стираем: половинное удаление хуже честного отказа.
     */
    fun deleteMessageForAll(messageId: String) {
        viewModelScope.launch {
            messageDeletion.deleteForAllDirect(chatId, messageId)
                .onFailure { e -> _uiState.update { it.copy(error = e.message.orEmpty()) } }
        }
    }

    fun onScrolledToBottom() {
        _uiState.update { it.copy(scrollToBottom = false) }
    }

    /**
     * Received-file export: the SAF picker (launched by the screen) returns a user-chosen Uri;
     * the plaintext is streamed out of the app-private verified storage into it.
     */
    /**
     * Переслать себе в «Избранное».
     *
     * Копия файла не делается: избранное ссылается на эту же принятую передачу.
     */
    fun saveToFavorites(transfer: FileTransferEntity, sourceTitle: String) {
        viewModelScope.launch {
            val result = savedItems.saveFile(transfer, sourceTitle)
            _uiState.update {
                it.copy(
                    error = when (result) {
                        com.vladimir.messenger.data.repository.SaveResult.Saved ->
                            "Добавлено в избранное"
                        com.vladimir.messenger.data.repository.SaveResult.AlreadySaved ->
                            "Уже в избранном"
                        com.vladimir.messenger.data.repository.SaveResult.FileNotReady ->
                            "Файл ещё не получен полностью"
                    },
                )
            }
        }
    }

    /** Переслать текст сообщения себе в «Избранное». */
    fun saveTextToFavorites(text: String, sourceTitle: String) {
        viewModelScope.launch {
            // Запоминаем, откуда взято, чтобы из «Избранного» вернуться сюда.
            val chat = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { chatRepository.getChatById(chatId) }.getOrNull()
            }
            savedItems.saveText(
                text,
                sourceTitle,
                com.vladimir.messenger.data.repository.SavedOrigin(
                    kind = com.vladimir.messenger.data.repository.SavedOrigin.CHAT,
                    id = chatId,
                    name = chat?.contactName.orEmpty().ifBlank { sourceTitle },
                    contactId = chat?.contactId.orEmpty(),
                ),
            )
            _uiState.update { it.copy(error = "Добавлено в избранное") }
        }
    }

    fun requestSaveReceivedFile(transfer: FileTransferEntity) {
        if (transfer.direction != "INCOMING" || transfer.state != "COMPLETE") return
        _uiState.update { it.copy(pendingSave = transfer) }
    }

    fun onSaveTargetPicked(target: android.net.Uri?) {
        val transfer = _uiState.value.pendingSave
        _uiState.update { it.copy(pendingSave = null) }
        if (target == null || transfer == null) return
        viewModelScope.launch {
            val ok = runCatching { fileTransferRouter.exportReceivedFile(transfer, target) }
                .getOrDefault(false)
            _uiState.update {
                it.copy(
                    error = if (ok) "Сохранено: ${transfer.displayName}" else "Не удалось сохранить файл",
                )
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
