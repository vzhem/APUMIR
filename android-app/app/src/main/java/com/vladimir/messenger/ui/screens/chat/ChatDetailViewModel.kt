package com.vladimir.messenger.ui.screens.chat

import com.vladimir.messenger.R
import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.file.FileTransferRankPolicy
import com.vladimir.messenger.data.file.FileTransferRouter
import com.vladimir.messenger.data.file.OutgoingFilePreparationService
import com.vladimir.messenger.data.local.MessagePinPolicy
import com.vladimir.messenger.data.local.dao.FileTransferDao
import com.vladimir.messenger.data.local.dao.MessagePinMutation
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import com.vladimir.messenger.data.referral.ReferralRankStore
import com.vladimir.messenger.data.repository.ChatRepository
import com.vladimir.messenger.domain.model.Message
import com.vladimir.messenger.domain.usecase.GetMessagesUseCase
import com.vladimir.messenger.domain.usecase.SendMessageUseCase
import com.vladimir.messenger.domain.usecase.MarkAsReadUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    /** р235: собеседник сейчас печатает («печатает…» в шапке чата). */
    val isPeerTyping: Boolean = false,
    /**
     * р241: переписка с СОБСТВЕННЫМ узлом (в контакты попал свой адрес -
     * например, отсканировали собственный QR из профиля). Такая переписка
     * никуда не ведёт: собеседника в ней нет. Показываем честную плашку,
     * вместо того чтобы молча копить строки «в ожидании».
     */
    val isSelfChat: Boolean = false,
    /**
     * Собеседник сообщил ранг VIP (APURANK1) — у имени в шапке чата появляется
     * знак, а вокруг аватарки — объёмное золотое кольцо с блеском.
     */
    val peerVip: Boolean = false,
    /**
     * От собеседника приходят конверты, которые не вскрываются: у него осталась
     * прежняя копия нашего ключа (переустановка/восстановление профиля). Пока
     * это так, в переписке висит золотая плашка с объяснением и кнопкой
     * «Отправить мой ключ» — раньше об этом знал только отчёт «Логи».
     */
    val keyDesync: Boolean = false,
    /**
     * Переписка с САМИМ СОБОЙ и мой ранг — VIP: тогда кольцо и знак показываем
     * у своего узла (в такой переписке собеседника нет).
     */
    val selfVip: Boolean = false,
    val transfers: List<FileTransferEntity> = emptyList(),
    val inputText: String       = "",
    val isLoading: Boolean      = true,
    /** Local database error, distinct from sending/network errors. */
    val historyError: String?   = null,
    val isSending: Boolean      = false,
    val isPreparingFile: Boolean = false,
    val error: String?          = null,
    val isContactOnline: Boolean = false,
    /** Срок паузы уведомлений этого личного чата. */
    val mutedUntilMs: Long = 0L,
    /** @никнейм собеседника: показывает карточка профиля. */
    val contactUsername: String = "",
    /** Сердечки и анти-рейтинг профиля собеседника. */
    val heartCount: Int = 0,
    val heartMine: Boolean = false,
    val antiRatingCount: Int = 0,
    val antiRatingMine: Boolean = false,
    /** Временное предупреждение о всплеске жалоб (не одиночные отметки). */
    val antiRatingWarning: Boolean = false,
    val antiRatingUntilMs: Long = 0,
    val scrollToBottom: Boolean = false,
    val pendingSave: FileTransferEntity? = null,
    /** Non-null asks the screen to open Android's system picker for an explicit file retry. */
    val pendingRetryFilePickerId: String? = null,
    /** Ранг ещё не открыл вложения: кнопка объяснит это сразу, а не после выбора файла. */
    val canSendAttachments: Boolean = true,
    val attachmentsLockedHint: String = "",
    /** Каталог GIF (наш сервер): гифки, курсор «ещё», состояние. */
    val gifItems: List<com.vladimir.messenger.data.gif.GifItem> = emptyList(),
    val gifNext: String = "",
    val gifLoading: Boolean = false,
    val gifError: String? = null,
    /** Раунд 192: мягкая плашка, когда показываем сохранённые результаты. */
    val gifNotice: String? = null,
    /** Раунд 121: свой каталог роя. tab: "swarm" | "external". */
    val gifTab: String = "swarm",
    val myGifs: List<com.vladimir.messenger.data.gif.GifLibEntry> = emptyList(),
    val swarmGifs: List<com.vladimir.messenger.data.gif.SwarmGif> = emptyList(),
    val swarmStatus: String? = null,
    /** Реакции по сообщениям: ключ - id сообщения. */
    val reactions: Map<String, List<com.vladimir.messenger.data.reaction.ReactionSummary>> = emptyMap(),
    val replyTo: Message? = null,
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
    private val groupRepository: com.vladimir.messenger.data.group.GroupRepository,
    private val reactionRepository: com.vladimir.messenger.data.reaction.ReactionRepository,
    private val contactDao: com.vladimir.messenger.data.local.dao.ContactDao,
    private val readReceipts: com.vladimir.messenger.data.receipt.ReadReceiptRepository,
    private val hearts: com.vladimir.messenger.data.heart.HeartRepository,
    private val messageDeletion: com.vladimir.messenger.data.repository.MessageDeletionRepository,
    private val stickerLibrary: com.vladimir.messenger.data.sticker.StickerLibrary,
    @ApplicationContext private val appContext: Context,
    /** Ранги собеседников: поток узлов-элиты для знака и кольца у аватарки. */
    private val peerRankStore: com.vladimir.messenger.data.rank.PeerRankStore,
) : ViewModel() {

    // chatId передаётся через навигацию (SavedStateHandle)
    private val chatId: String = checkNotNull(savedStateHandle["chatId"])
    /** Picker result is bound to the original outgoing file message ID. */
    @Volatile private var awaitingFileRetryMessageId: String? = null

    /** р235: собеседник личного чата - ему уходит «печатает…». */
    @Volatile private var peerId: String = ""

    /** р235: когда последний раз отправляли «печатает» (не чаще раза в 2.5 с). */
    @Volatile private var lastTypingSentAt = 0L

    /** р235: сказали ли собеседнику, что мы печатаем (чтобы послать «перестал»). */
    @Volatile private var typingAnnounced = false

    /** р236: черновик этого чата (ключ - адрес собеседника, он общий у устройств). */
    @Volatile private var draftKey: String = ""

    /** р236: когда последний раз отправляли черновик партнёрскому устройству. */
    @Volatile private var lastDraftSentAt = 0L

    /** р244-подобное: собеседник личного чата, за пометкой ключа которого следим. */
    @Volatile private var keyDesyncPeer: String = ""

    /** Наблюдение за пометкой ключа заведено (один раз на экран). */
    @Volatile private var keyDesyncWatched = false

    private var keyDesyncJob: kotlinx.coroutines.Job? = null

    private val _uiState = MutableStateFlow(ChatDetailUiState())
    val uiState: StateFlow<ChatDetailUiState> = _uiState.asStateFlow()

    private val historyObserver = ChatHistoryObserver(
        scope = viewModelScope,
        messages = { getMessagesUseCase(chatId) },
        onMessages = ::showLocalMessages,
        reportRead = {
            withContext(Dispatchers.IO) {
                markAsReadUseCase(chatId)
                readReceipts.reportRead(chatId)
            }
        },
        onLoadError = { error ->
            android.util.Log.w("ChatDetailVM", "Local history read failed", error)
            _uiState.update { it.copy(isLoading = false, historyError = appContext.getString(R.string.cd_history_failed)) }
        },
        onReadError = { error -> android.util.Log.w("ChatDetailVM", "Read receipt failed", error) },
    )

    init {
        refreshAttachmentRights()
        observePeerTyping()
        observeDrafts()
        loadMessages()
        observePinned()
        observeContactPresence()
        observeTransfers()
        observeReactions()
        markAsRead()
        observeGifArrivals()
        observeStickerArrivals()
    }

    /**
     * р235: «печатает…». Состояние живёт в памяти (TypingPeer) и само гаснет
     * через несколько секунд после последнего пакета; здесь только показываем
     * его в шапке чата.
     */
    private fun observePeerTyping() {
        viewModelScope.launch {
            com.vladimir.messenger.data.typing.TypingPeer.typing.collect { typing ->
                val peer = peerId
                _uiState.update { it.copy(isPeerTyping = peer.isNotBlank() && typing.contains(peer)) }
            }
        }
    }

    /**
     * р235: рассказать собеседнику, что мы печатаем. Пакет уходит не чаще раза
     * в [TYPING_REFRESH_MS] (на каждую букву - нельзя: это лишний трафик), а
     * когда поле очистили или сообщение ушло - «перестал».
     */
    private fun publishTyping(active: Boolean) {
        val peer = peerId
        if (peer.isBlank()) return
        // р243: в переписке с собственным узлом сигнал не нужен - он вернулся бы
        // уведомлением «от себя». Признак берём из состояния (р241): вызова в
        // ядро здесь нет, набор текста не должен ждать JNI.
        if (_uiState.value.isSelfChat) return
        if (!active) {
            if (!typingAnnounced) return
            typingAnnounced = false
            lastTypingSentAt = 0L
            // «Перестал» - тоже с запасным путём: иначе индикатор остался бы
            // висеть, если прямой канал не работает.
            com.vladimir.messenger.data.typing.TypingRouter.publishLocal(peer, chatId, false, queueFallback = true)
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastTypingSentAt < TYPING_REFRESH_MS) return
        lastTypingSentAt = now
        // В надёжную очередь попадает только ПЕРВЫЙ пакет сессии: так
        // индикатор появится даже без прямого канала, а поток обновлений
        // очередь сообщений не забивает.
        val first = !typingAnnounced
        typingAnnounced = true
        com.vladimir.messenger.data.typing.TypingRouter.publishLocal(peer, chatId, true, queueFallback = first)
    }

    // ── р236: черновики сообщений ───────────────────────────────────────────

    /**
     * Черновик чата: текст хранится под ключом собеседника, поэтому второй
     * телефон той же личности видит тот же недописанный текст. Здесь только
     * сохранение у себя (при каждом изменении) и редкая отправка партнёру
     * (не чаще раза в 1.5 с - набор текста не должен забивать канал).
     */
    private fun saveDraft(text: String) {
        val key = draftKey
        if (key.isBlank()) return
        com.vladimir.messenger.data.draft.DraftStore.save(key, text)
        // р243: партнёрскому устройству черновик в свой же чат не шлём (см. выше).
        if (_uiState.value.isSelfChat) return
        val now = System.currentTimeMillis()
        if (now - lastDraftSentAt < DRAFT_REFRESH_MS && text.isNotEmpty()) return
        lastDraftSentAt = now
        com.vladimir.messenger.data.mirror.MirrorHub.publishDraft(key, text)
    }

    /**
     * Черновик, приехавший с партнёрского устройства. Подставляем его в поле
     * только если поле пустое: то, что человек набирает прямо сейчас, чужой
     * текст затирать не должен.
     */
    private fun observeDrafts() {
        viewModelScope.launch {
            com.vladimir.messenger.data.draft.DraftStore.drafts.collect { drafts ->
                val key = draftKey
                if (key.isBlank()) return@collect
                val text = drafts[key].orEmpty()
                if (text.isEmpty()) return@collect
                if (_uiState.value.inputText.isNotBlank()) return@collect
                _uiState.update { it.copy(inputText = text) }
            }
        }
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
        // Знак VIP и золотое кольцо у имени собеседника обновляем на лету: ранг
        // может приехать в любой момент конвертом APURANK1, а экран уже открыт.
        viewModelScope.launch {
            peerRankStore.vipNodeIds.collect { vipIds ->
                val peer = peerId.lowercase()
                // В переписке с собственным узлом собеседника нет: кольцо решает
                // МОЙ ранг (он же виден в профиле и на главной). Свой ранг лежит
                // в настройках, поэтому читаем его в фоне, а не на главном потоке.
                val selfVip = withContext(Dispatchers.IO) {
                    isSelfChat(_uiState.value.messages, peerId) && ownVip()
                }
                _uiState.update {
                    it.copy(
                        peerVip = peer.isNotBlank() && peer in vipIds,
                        selfVip = selfVip,
                    )
                }
            }
        }

        viewModelScope.launch {
            // р243: пока чат не опознан, считаем его обычным: плашка «это ваш
            // узел» появляется после загрузки переписки (р241), а не после
            // вызова в ядро. Набор текста от этого не зависит.
            chatRepository.observeChat(chatId).collect { chat ->
                if (chat != null) {
                    // Заодно подтягиваем @никнейм: он живёт в таблице контактов
                    // и нужен карточке профиля. В списке чатов его намеренно
                    // нет - там только имя.
                    val nick = runCatching {
                        contactDao.getContactById(chat.contactId)?.username.orEmpty()
                    }.getOrDefault("")
                    // р235: адрес собеседника нужен для «печатает…».
                    if (chat.contactId.isNotBlank()) peerId = chat.contactId
                    // р241/р243: свой ли это узел, решает загрузка переписки:
                    // она сравнивает адрес собеседника со «своим» адресом из
                    // уже полученных сообщений (recipientId). Ядро здесь не
                    // опрашиваем - этот код идёт при каждом обновлении чата.
                    // р236: черновик этого чата (ключ - адрес собеседника)
                    // подставляем в пустое поле: недописанное с другого
                    // устройства должно ждать здесь.
                    if (chat.contactId.isNotBlank() && draftKey.isBlank()) {
                        draftKey = com.vladimir.messenger.data.draft.DraftStore.dmKey(chat.contactId)
                        val draft = com.vladimir.messenger.data.draft.DraftStore.load(draftKey)
                        if (draft.isNotEmpty() && _uiState.value.inputText.isBlank()) {
                            _uiState.update { it.copy(inputText = draft) }
                        }
                    }
                    _uiState.update {
                        it.copy(
                            isContactOnline = chat.isContactOnline,
                            mutedUntilMs = chat.mutedUntilMs,
                            isSelfChat = isSelfChat(it.messages, chat.contactId),
                            contactUsername = nick,
                            isPeerTyping = com.vladimir.messenger.data.typing.TypingPeer
                                .isTyping(chat.contactId),
                        )
                    }
                    // Сердечки заводим здесь: только тут точно известен адрес
                    // собеседника (в личном чате это contactId).
                    if (!heartsWatched && chat.contactId.isNotBlank()) {
                        heartsWatched = true
                        observeHearts(chat.contactId)
                    }
                    // «Сообщения от него не открываются»: пока экран открыт,
                    // спрашиваем пометку раз в 5 секунд. Это одно чтение
                    // настроек — дешевле любого нового потока событий.
                    if (!keyDesyncWatched && chat.contactId.startsWith("pk_")) {
                        keyDesyncWatched = true
                        observeKeyDesync(chat.contactId)
                    }
                }
            }
        }
    }

    /**
     * Пометка «от собеседника не открывается». Опрос: пометку ставит служба
     * ядра (при нерасшифрованном конверте), а экран лишь показывает её словами.
     * Гасим сразу, как только что-то от него открылось, — состояние видно и так.
     */
    private fun observeKeyDesync(peer: String) {
        keyDesyncPeer = peer
        keyDesyncJob = viewModelScope.launch {
            while (true) {
                val pending = com.vladimir.messenger.data.security.KeyDesyncNotice
                    .isPending(appContext, peer)
                _uiState.update { if (it.keyDesync == pending) it else it.copy(keyDesync = pending) }
                kotlinx.coroutines.delay(KEY_DESYNC_POLL_MS)
            }
        }
    }

    /**
     * Кнопка плашки: отдать собеседнику свой ключ ещё раз.
     *
     * Само по себе это его приложение не «починит» (пин сбрасывает только
     * человек, отсканировав QR заново), но оно покажет ЕМУ нашу настоящую
     * привязку и подсказку — так переписка восстанавливается быстрее, чем при
     * ожидании фоновой рассылки. Пометку у себя снимаем: сигнал отправлен.
     */
    fun onKeyDesyncAction() {
        val peer = keyDesyncPeer
        if (peer.isBlank()) return
        viewModelScope.launch {
            runCatching { fileTransferRouter.announceMyKeyTo(peer) }
                .onFailure { error -> android.util.Log.w("ChatDetailVM", "key announce failed", error) }
            com.vladimir.messenger.data.security.KeyDesyncNotice.clear(appContext, peer)
            _uiState.update { it.copy(keyDesync = false) }
        }
    }

    /** Изменить срок отключения уведомлений только для этой личной переписки. */
    fun setNotificationsMutedUntil(untilMs: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { chatRepository.setChatMutedUntil(chatId, untilMs) }
                .onFailure { error ->
                    _uiState.update { it.copy(error = error.message ?: appContext.getString(R.string.cd_notify_failed)) }
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
                _uiState.update {
                    it.copy(pinned = pinned, error = MessagePinPolicy.visibleError(it.error, pinned.size))
                }
            }
        }
    }

    /** Статус вступления по карточке приглашения (для всплывающей подсказки). */
    private val _inviteStatus = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val inviteStatus: kotlinx.coroutines.flow.StateFlow<String?> = _inviteStatus.asStateFlow()
    fun consumeInviteStatus() { _inviteStatus.value = null }

    /**
     * Раунд 189: тап «Вступить»/«Подписаться» на карточке приглашения -
     * та же механика, что у ссылок сообществ: короткая https-ссылка
     * раскрывается сервисом, дальше заявка или вход.
     */
    fun joinByInviteLink(link: String) {
        if (link.isBlank()) return
        viewModelScope.launch {
            val expanded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { groupRepository.expandLink(link) }.getOrNull()
            }
            if (expanded == null) {
                _inviteStatus.value = appContext.getString(R.string.cd_no_service)
                return@launch
            }
            val outcome = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { groupRepository.joinByLink(expanded) }.getOrNull()
            }
            _inviteStatus.value = when (outcome) {
                is com.vladimir.messenger.data.group.JoinOutcome.Joined ->
                    if (outcome.isChannel) "Вы подписались: " + outcome.title
                    else "Вы вступили: " + outcome.title
                is com.vladimir.messenger.data.group.JoinOutcome.RequestSent ->
                    "Заявка отправлена: " + outcome.title
                is com.vladimir.messenger.data.group.JoinOutcome.Failed ->
                    "Не удалось войти: " + outcome.reason
                null -> appContext.getString(R.string.cd_join_failed2)
            }
        }
    }

    fun togglePin(messageId: String, pinned: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { chatRepository.setMessagePinned(messageId, pinned) }
                .getOrElse { error ->
                    _uiState.update { it.copy(error = error.message ?: appContext.getString(R.string.cd_pin_failed)) }
                    return@launch
                }
            when (result) {
                MessagePinMutation.LIMIT_REACHED -> {
                    _uiState.update { it.copy(error = MessagePinPolicy.LIMIT_REACHED_MESSAGE) }
                }
                MessagePinMutation.SCOPE_CONFLICT -> {
                    _uiState.update { it.copy(error = MessagePinPolicy.SCOPE_CONFLICT_MESSAGE) }
                }
                MessagePinMutation.NOT_FOUND -> {
                    _uiState.update { it.copy(error = appContext.getString(R.string.cd_msg_not_found)) }
                }
                MessagePinMutation.UPDATED,
                MessagePinMutation.UNCHANGED -> {
                    // Закреп личный: собеседнику не уходит, но совпадает на
                    // втором устройстве этого же человека.
                    runCatching {
                        com.vladimir.messenger.data.mirror.MirrorHub.publishPin(messageId, pinned)
                    }
                }
            }
        }
    }

    private fun loadMessages() {
        _uiState.update { it.copy(isLoading = it.messages.isEmpty(), historyError = null) }
        historyObserver.start()
    }

    fun retryMessages() = loadMessages()

    /** No suspend, JNI, database lookup or network send before publishing local rows. */
    private fun showLocalMessages(messages: List<Message>) {
        if (com.vladimir.messenger.BuildConfig.DEBUG) {
            android.util.Log.d("ChatDetailVM", "Local history: ${messages.size} messages")
        }
        val isSelf = isSelfChat(messages, peerId)
        _uiState.update { state ->
            state.copy(
                messages = messages,
                isLoading = false,
                historyError = null,
                isSelfChat = isSelf,
                scrollToBottom = state.messages.isEmpty() || messages.lastOrNull()?.isFromMe == true,
            )
        }
    }

    /** Мой ранг дотягивает до VIP: кольцо и знак в переписке с собственным узлом. */
    private fun ownVip(): Boolean = com.vladimir.messenger.data.rank.PeerRankStore.isVipRank(
        com.vladimir.messenger.data.referral.ReferralRankStore.qualifiedDirectCount(appContext),
    )

    /** Peer metadata arrives independently; history must not wait for a second DB query. */
    private fun isSelfChat(messages: List<Message>, peer: String): Boolean {
        val selfNode = com.vladimir.messenger.data.mirror.MirrorHub.nodeIdCached().ifBlank {
            messages.firstNotNullOfOrNull { message ->
                if (message.isFromMe) null else message.recipientId.takeIf { it.startsWith("pk_") }
            }.orEmpty()
        }
        return selfNode.isNotBlank() && peer == selfNode
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
            _uiState.update {
                it.copy(
                    heartMine = hearts.isMine(peerId),
                    antiRatingMine = hearts.isMyAntiRating(peerId),
                )
            }
        }
        viewModelScope.launch {
            hearts.observeCount(peerId).collect { count ->
                _uiState.update { it.copy(heartCount = count) }
            }
        }
        viewModelScope.launch {
            hearts.observeAntiState(peerId).collect { state ->
                _uiState.update {
                    it.copy(
                        antiRatingCount = state.total,
                        antiRatingWarning = state.warning,
                        antiRatingUntilMs = state.warningUntilMs,
                    )
                }
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

    /** Поставить или снять анти-рейтинг профилю собеседника. */
    fun onAntiRatingClick(peerId: String) {
        if (peerId.isBlank()) return
        viewModelScope.launch {
            val mine = hearts.toggleAntiRating(peerId)
            _uiState.update { it.copy(antiRatingMine = mine) }
        }
    }

    private fun markAsRead() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Local badge only. The history observer owns the ONE receipt worker.
                markAsReadUseCase(chatId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("ChatDetailVM", "Local unread badge update failed", error)
            }
        }
    }

    fun startReply(message: Message) {
        _uiState.update { it.copy(replyTo = message) }
    }

    fun clearReply() {
        _uiState.update { it.copy(replyTo = null) }
    }

    private fun contactNameForReply(message: Message): String =
        if (message.senderId == peerId) "Собеседник" else message.senderId.takeLast(6)

    fun onInputTextChanged(text: String) {
        _uiState.update { it.copy(inputText = text) }
        // р235: «печатает…» у собеседника, пока в поле есть текст.
        publishTyping(text.isNotBlank())
        // р236: черновик - и у себя, и на партнёрском устройстве.
        saveDraft(text)
    }

    fun onSendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true, inputText = "") }

            val reply = _uiState.value.replyTo?.let { target ->
                com.vladimir.messenger.data.reply.DirectReplyWire.Target(
                    messageId = target.id,
                    author = if (target.isFromMe) "Вы" else contactNameForReply(target),
                    text = target.content,
                )
            }
            sendMessageUseCase(chatId, text, reply)
                .onSuccess {
                    // р235: сообщение ушло - «печатает…» у собеседника гаснет.
                    publishTyping(false)
                    // р236: текст ушёл - черновик больше не нужен ни здесь, ни там.
                    saveDraft("")
                    _uiState.update { it.copy(
                        isSending      = false,
                        scrollToBottom = true,
                        replyTo = null,
                    )}
                }
                .onFailure { e ->
                    _uiState.update { it.copy(
                        isSending = false,
                        inputText = text,  // Восстанавливаем текст при ошибке
                        error     = appContext.getString(R.string.cd_send_error, e.message)
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
                    android.content.Intent.createChooser(intent, appContext.getString(R.string.cd_share_chooser))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { _uiState.update { st -> st.copy(error = appContext.getString(R.string.cd_share_failed)) } }
        }
    }

    /**
     * р245: медиа с телефона-зеркала.
     *
     * Отправка файла требует СВОЕЙ сетевой сессии: у тени её нет, и подготовка
     * передачи падала на незакреплённом ключе получателя («файл не отправлен»).
     * Поэтому сначала просим движок у активного партнёра (передача роли, р231)
     * и ждём, пока он поднимется здесь. Если партнёра нет - просто продолжаем:
     * дальше всё как обычно, с понятной ошибкой, если ключа действительно нет.
     */
    private suspend fun claimEngineForMediaIfNeeded() {
        if (RustBridge.isRunning()) return
        if (!com.vladimir.messenger.data.mirror.MirrorHub.canClaimEngine()) return
        com.vladimir.messenger.data.mirror.MirrorHub.claimEngine()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + 15_000L
            while (!RustBridge.isRunning() && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(400L)
            }
        }
        if (!RustBridge.isRunning()) {
            android.util.Log.i("ChatDetailVM", "р245: движок не поднялся, пробуем как есть")
        }
    }

    /** Manual resend for an undelivered text message or file placeholder. */
    fun retryMessage(message: Message) {
        if (!message.isFromMe || _uiState.value.isPreparingFile) return
        if (message.status == com.vladimir.messenger.domain.model.MessageStatus.LOCAL_FILE ||
            message.status == com.vladimir.messenger.domain.model.MessageStatus.FILE_EXPIRED
        ) {
            requestFileRetryPicker(message.id)
            return
        }
        viewModelScope.launch {
            chatRepository.retryOutgoingMessage(message.id)
                .onFailure { error ->
                    _uiState.update { it.copy(error = "Повторить отправку не удалось: ${error.message.orEmpty()}") }
                }
        }
    }

    /** Always use the system picker for a file retry: source URIs/paths are not retained. */
    fun retryFileTransfer(messageId: String) {
        requestFileRetryPicker(messageId)
    }

    private fun requestFileRetryPicker(messageId: String) {
        if (_uiState.value.isPreparingFile) return
        if (!_uiState.value.canSendAttachments) {
            _uiState.update { it.copy(error = it.attachmentsLockedHint) }
            return
        }
        awaitingFileRetryMessageId = messageId
        _uiState.update { it.copy(error = null, pendingRetryFilePickerId = messageId) }
    }

    fun onRetryFilePickerLaunched(messageId: String) {
        if (_uiState.value.pendingRetryFilePickerId == messageId) {
            _uiState.update { it.copy(pendingRetryFilePickerId = null) }
        }
    }

    fun onRetryFilePickerResult(uri: Uri?) {
        val messageId = awaitingFileRetryMessageId ?: return
        awaitingFileRetryMessageId = null
        if (uri == null) return
        viewModelScope.launch {
            _uiState.update { it.copy(isPreparingFile = true, error = null) }
            try {
                claimEngineForMediaIfNeeded()
                val message = chatRepository.getMessageById(messageId)
                    ?: error("Исходное сообщение не найдено")
                check(message.isFromMe) { "Можно повторить только свой файл" }
                check(
                    message.status == com.vladimir.messenger.domain.model.MessageStatus.LOCAL_FILE ||
                        message.status == com.vladimir.messenger.domain.model.MessageStatus.FILE_EXPIRED
                ) { "Файл уже доставлен или больше не ожидает отправки" }
                val chat = chatRepository.getChatById(message.chatId)
                    ?: error("Чат недоступен")
                val recipientId = chat.contactId
                check(recipientId.startsWith("pk_")) { "У контакта нет ключа для передачи файлов" }
                check(chatRepository.markOutgoingFileRequeued(messageId)) {
                    "Статус файла изменился: он уже мог быть подтверждён"
                }
                fileTransferRouter.retryOutgoingFile(
                    source = uri,
                    messageId = messageId,
                    chatId = message.chatId,
                    recipientNodeId = recipientId,
                    qualifiedDirectReferrals = ReferralRankStore.qualifiedDirectCount(appContext),
                )
                _uiState.update { it.copy(scrollToBottom = true) }
                fileTransferRouter.pumpOutgoing()
            } catch (e: Exception) {
                android.util.Log.w("ChatDetailVM", "file retry failed: ${e.javaClass.simpleName}")
                _uiState.update { it.copy(error = appContext.getString(R.string.cd_file_not_sent2, e.message.orEmpty())) }
            } finally {
                _uiState.update { it.copy(isPreparingFile = false) }
            }
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
                // р245: с телефона-зеркала сначала забираем движок у активного.
                claimEngineForMediaIfNeeded()
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
                    _uiState.update { it.copy(error = appContext.getString(R.string.cd_file_not_sent, e.message)) }
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
            // Раунд 192: успех первой страницы - в кэш (файл), чтобы при
            // недоступном сервере каталог продолжал работать сохранённым.
            if (result != null && !more) {
                com.vladimir.messenger.data.gif.GifSearchCache.save(
                    appContext, query, result.first, result.second,
                )
            }
            _uiState.update { state ->
                if (result == null) {
                    // Сервер недоступен (лимит/сеть): показываем сохранённое.
                    val cached = if (more) null
                    else runCatching {
                        com.vladimir.messenger.data.gif.GifSearchCache.load(appContext, query)
                    }.getOrNull()
                    when {
                        cached != null -> state.copy(
                            gifLoading = false,
                            gifError = null,
                            gifNotice = "Сервер перегружен — показываю сохранённые гифки. " +
                                "Скачать и отправить можно как обычно",
                            gifItems = cached.first,
                            gifNext = "",
                        )
                        more -> state.copy(
                            gifLoading = false,
                            gifError = null,
                            gifNotice = appContext.getString(R.string.cd_gif_more_unavailable),
                            gifNext = "",
                        )
                        else -> state.copy(
                            gifLoading = false,
                            gifError = "Каталог гиф недоступен: сервер перегружен. Это временно — попробуйте позже",
                        )
                    }
                } else {
                    val (items, next) = result
                    if (items.isEmpty() && state.gifItems.isEmpty()) {
                        state.copy(gifLoading = false, gifError = appContext.getString(R.string.cd_gif_nothing))
                    } else {
                        state.copy(
                            gifLoading = false,
                            gifError = null,
                            gifNotice = null,
                            gifItems = (state.gifItems + items).distinctBy { it.id },
                            gifNext = next,
                        )
                    }
                }
            }
        }
    }

    fun closeGifCatalog() {
        _uiState.update { it.copy(gifItems = emptyList(), gifNext = "", gifError = null, gifNotice = null) }
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
        // Раунд 206: непрорисованный стикер не отправляем «пустышкой» -
        // сначала тихо докачиваем у роя, приедет - уйдёт сам.
        if (!entry.file.isFile) {
            val swarm = _swarmStickers.value.firstOrNull { it.sha256 == entry.sha256 }
            if (swarm != null) {
                autoFetchSticker(swarm)
                pendingSwarmStickerSha = entry.sha256
                _uiState.update { it.copy(swarmStatus = appContext.getString(R.string.cd_sticker_loading)) }
            } else {
                _uiState.update { it.copy(error = appContext.getString(R.string.cd_sticker_lost)) }
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isPreparingFile = true, error = null) }
            var targetRecipientId: String? = null
            try {
                // р245: с телефона-зеркала сначала забираем движок (как и файл).
                claimEngineForMediaIfNeeded()
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
                    it.copy(error = appContext.getString(R.string.cd_sticker_not_sent, e.message.orEmpty()))
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
                        null -> appContext.getString(R.string.cd_net_silent)
                        "" -> appContext.getString(R.string.cd_already_downloading)
                        else -> "Качается с $holder - сейчас отправим"
                    },
                )
            }
        }
    }

    /**
     * Раунд 206: тихая докачка плитки без файла (панель сама, при открытии).
     * Без плашек и без «отправить по приезде» - просто вернуть файл домой.
     */
    fun autoFetchSticker(swarm: com.vladimir.messenger.data.sticker.SwarmSticker) {
        viewModelScope.launch {
            val local = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                stickerLibrary.entryOf(swarm.sha256)
            }
            if (local != null) return@launch
            com.vladimir.messenger.data.sticker.StickerLibrary.rememberWant(swarm.sha256)
            runCatching {
                com.vladimir.messenger.data.sticker.StickerLibrary.requestSticker(
                    appContext, chatRepository, swarm.sha256, swarm.holders,
                )
            }
        }
    }

    /** Раунд 206: «обновить» - сброс паузы, просьба ВСЕМ держателям (до 12). */
    fun retryFetchSticker(swarm: com.vladimir.messenger.data.sticker.SwarmSticker) {
        viewModelScope.launch {
            com.vladimir.messenger.data.sticker.StickerLibrary.rememberWant(swarm.sha256)
            _uiState.update { it.copy(swarmStatus = appContext.getString(R.string.cd_asking_holders)) }
            val holder = runCatching {
                com.vladimir.messenger.data.sticker.StickerLibrary.requestSticker(
                    appContext, chatRepository, swarm.sha256, swarm.holders,
                    force = true,
                    maxTargets = 12,
                )
            }.getOrNull()
            _uiState.update {
                it.copy(
                    swarmStatus = when (holder) {
                        null -> appContext.getString(R.string.cd_net_silent)
                        "" -> appContext.getString(R.string.cd_already_downloading)
                        else -> "Просили: $holder"
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
                _uiState.update { it.copy(swarmStatus = appContext.getString(R.string.cd_gif_too_big)) }
            } else {
                runCatching {
                    com.vladimir.messenger.data.gif.GifLibrary.syncWithSwarm(
                        appContext, chatRepository, force = false,
                    )
                }
                onGifCatalogOpened()
                _uiState.update { it.copy(swarmStatus = appContext.getString(R.string.cd_gif_added)) }
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
                    it.copy(error = if (message.contains("недоступен")) appContext.getString(R.string.cd_peer_unavailable) else "Гифка не отправлена: $message")
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
        // р245: это устройство без сети (зеркало)? Тогда ссылку отправляет
        // партнёр-активный. Раньше вызов шёл в ядро напрямую: движка здесь нет,
        // отправка возвращала false, и строка висела «в ожидании» вечно -
        // гифка с телефона-зеркала до собеседника не доходила вовсе.
        val viaMirror = chatRepository.sendGifRefViaMirror(
            chatId = chatId,
            recipientId = recipientId,
            sha256 = sha256,
            messageId = messageId,
        )
        if (viaMirror) {
            _uiState.update { it.copy(scrollToBottom = true) }
            return
        }
        val sent = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                RustBridge.sendMessage(
                    messageId, chatId, recipientId,
                    com.vladimir.messenger.data.gif.GifLibrary.refContent(sha256),
                )
            }.getOrDefault(false)
        }
        // Раунд 178: гифка больше НЕ ждёт собеседника. Отправилось - хорошо;
        // нет - карточка всё равно появляется в чате и встаёт в телефонную
        // очередь QUEUED_OFFLINE: доставим сами при его появлении в сети
        // (retryPendingMessagesForPeer/FULL SYNC по presence), а байты он
        // доберёт из роя у хранителей - я среди них.
        chatRepository.insertGifRefMessage(
            chatId = chatId,
            recipientId = recipientId,
            messageId = messageId,
            sha256 = sha256,
            timestamp = System.currentTimeMillis(),
            status = if (sent) "LOCAL_FILE"
            else com.vladimir.messenger.domain.model.MessageStatus.QUEUED_OFFLINE.name,
        )
        _uiState.update {
            it.copy(
                scrollToBottom = true,
                swarmStatus = if (sent) null
                else appContext.getString(R.string.cd_gif_later),
            )
        }
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
                _uiState.update { it.copy(swarmStatus = appContext.getString(R.string.cd_gif_link_sent)) }
            } catch (e: Exception) {
                _uiState.update { it.copy(swarmStatus = appContext.getString(R.string.cd_peer_unavailable)) }
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
                            appContext.getString(R.string.cd_saved_fav)
                        com.vladimir.messenger.data.repository.SaveResult.AlreadySaved ->
                            appContext.getString(R.string.cd_already_saved)
                        com.vladimir.messenger.data.repository.SaveResult.FileNotReady ->
                            appContext.getString(R.string.cd_file_incomplete)
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
            _uiState.update { it.copy(error = appContext.getString(R.string.cd_saved_fav)) }
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
                    error = if (ok) appContext.getString(R.string.cd_saved_as, transfer.displayName) else appContext.getString(R.string.cd_save_failed),
                )
            }
        }
    }

    // Раунд 203: «Поделиться в APU» - друзья, группы+темы, каналы+посты.
    suspend fun forwardTargets(): List<com.vladimir.messenger.ui.components.ForwardTarget> {
        val friends = chatRepository.forwardFriends().map {
            com.vladimir.messenger.ui.components.ForwardTarget(
                com.vladimir.messenger.ui.components.ForwardKind.FRIEND,
                it.id,
                it.contactName,
            )
        }
        val groups = groupRepository.forwardGroups().map {
            com.vladimir.messenger.ui.components.ForwardTarget(
                com.vladimir.messenger.ui.components.ForwardKind.GROUP,
                it.id,
                it.title,
                it.isChannel,
            )
        }
        return friends + groups
    }

    /** Темы группы / посты канала для второго шага выбора цели. */
    suspend fun forwardTopics(groupId: String): List<com.vladimir.messenger.ui.components.ForwardTopic> =
        groupRepository.forwardTopics(groupId).map {
            com.vladimir.messenger.ui.components.ForwardTopic(it.id, it.name, it.iconEmoji)
        }

    /**
     * Раунд 203: переслать текст в выбранную цель. Над пересылаемым
     * пишется ссылка на источник: «↩ Переслано из «…»».
     */
    fun forwardMessage(
        text: String,
        sourceLabel: String,
        target: com.vladimir.messenger.ui.components.ForwardTarget,
        onResult: (Boolean) -> Unit,
    ) {
        viewModelScope.launch {
            val peer = runCatching { chatRepository.forwardChatById(chatId) }.getOrNull()
            val body = com.vladimir.messenger.util.ForwardMarker.buildBody(
                false,
                peer?.contactId.orEmpty(),
                "",
                sourceLabel,
                text,
            )
            val res = if (target.kind == com.vladimir.messenger.ui.components.ForwardKind.FRIEND) {
                chatRepository.sendMessage(target.id, "", body)
            } else {
                groupRepository.sendMessage(target.id, target.topicId ?: "", body)
            }
            onResult(res.isSuccess)
        }
    }

    /** Раунд 203: тап по источнику пересылки - куда открывать. */
    suspend fun resolveForwardTap(ref: com.vladimir.messenger.util.ForwardMarker.Ref): com.vladimir.messenger.util.ForwardMarker.Open {
        return if (!ref.isGroup) {
            val chat = chatRepository.forwardChatByContact(ref.id)
            if (chat == null) {
                com.vladimir.messenger.util.ForwardMarker.Open.Missing(appContext.getString(R.string.cd_no_contact))
            } else {
                com.vladimir.messenger.util.ForwardMarker.Open.Chat(chat.id, chat.contactName, chat.contactId)
            }
        } else {
            val group = groupRepository.forwardGroupById(ref.id)
            if (group == null || group.isLeft) {
                // Раунд 203 (владелец): вместо «недоступно» - вступить/подписаться.
                if (ref.slug.isNotBlank()) {
                    com.vladimir.messenger.util.ForwardMarker.Open.Join(
                        com.vladimir.messenger.data.group.GroupInviteLinks.build(
                            ref.slug,
                            ref.id,
                            ref.ownerId.takeIf { it.isNotBlank() },
                            ref.isChannel,
                            false,
                            ref.topicId.takeIf { it.isNotBlank() },
                        )
                    )
                } else {
                    com.vladimir.messenger.util.ForwardMarker.Open.Missing(appContext.getString(R.string.cd_no_invite_link))
                }
            } else {
                com.vladimir.messenger.util.ForwardMarker.Open.Group(group.id, ref.topicId.takeIf { it.isNotBlank() })
            }
        }
    }

    /** Раунд 203: локальный файл стикера/роя по sha - для пересланных визиток. */
    fun localSwarmFile(sha256: String): java.io.File? = stickerLibrary.fileOf(sha256)

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private companion object {
        /** р235: не чаще раза в 2.5 с - иначе «печатает…» стал бы потоком пакетов. */
        const val TYPING_REFRESH_MS = 2_500L

        /** р236: черновик уходит партнёру не чаще раза в 1.5 с. */
        const val DRAFT_REFRESH_MS = 1_500L

        /**
         * Как часто экран перечитывает пометку «от собеседника не открывается».
         * Пять секунд: человек всё равно вернётся к чату не мгновенно, а чтение
         * настроек стоит дешевле одного кадра отрисовки.
         */
        const val KEY_DESYNC_POLL_MS = 5_000L
    }
}
