package com.vladimir.messenger.ui.screens.channels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.data.group.GroupRepository
import com.vladimir.messenger.data.group.GroupRole
import com.vladimir.messenger.data.group.GroupSummary
import com.vladimir.messenger.data.local.MessagePinPolicy
import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.data.local.dao.MessagePinMutation
import com.vladimir.messenger.util.InlineImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Пост канала.
 *
 * Канал устроен на тех же таблицах, что и группа: пост - это тема, а первое
 * сообщение темы и есть текст поста. Остальные сообщения темы - комментарии,
 * поэтому их число считается как «всего сообщений минус один».
 *
 * Фотографии поста едут отдельными сообщениями-кусками той же темы (см.
 * [InlineImage.PART_MARKER]); лента склеивает их в [images], а в комментариях
 * и счётчике они не участвуют.
 *
 * Порядок ленты - от старых к новым, как в переписке: свежий пост всегда
 * внизу, и экран при открытии прокручивается туда.
 */
data class ChannelPost(
    val topicId: String,
    /** id самого сообщения-поста: к нему привязываются реакции и правка. */
    val messageId: String,
    /** Раунд 173: пост закреплён в канале. */
    val isPinned: Boolean = false,
    val title: String,
    val text: String,
    /** Фотографии поста (jpeg в base64) по порядку; пусто, если фото нет. */
    val images: List<String> = emptyList(),
    /** Сколько фотографий обещано, но ещё не доехало целиком. */
    val pendingPhotos: Int = 0,
    val authorId: String = "",
    val authorName: String,
    val timeMs: Long,
    val comments: Int,
    /** Сколько комментариев поста ещё не прочитано (значок у «Комментарии»). */
    val unreadComments: Int = 0,
    /** Сколько разных людей открыли пост. */
    val views: Int = 0,
    /** Файл, приложенный к посту (визитка `APUFILE1:`, рой этап 9-10); null - файла нет. */
    val file: com.vladimir.messenger.util.GroupFileMarker.Info? = null,
)

data class ChannelUiState(
    val channelId: String = "",
    val channel: GroupSummary? = null,
    val posts: List<ChannelPost> = emptyList(),
    /** Раунд 173: закреплённые посты канала (messageId, свежие вверху). */
    val pinnedPostIds: List<String> = emptyList(),
    /** Писать посты может владелец и администраторы; комментарии - все. */
    val canPost: Boolean = false,
    /** Может ли этот пользователь модерировать публикации и участников канала. */
    val canModerate: Boolean = false,
    val members: List<com.vladimir.messenger.data.group.MemberSummary> = emptyList(),
    val antiRatings: Map<String, Int> = emptyMap(),
    /** Узлы с активным временным предупреждением о всплеске жалоб (`nodeId -> до когда`). */
    val antiWarnings: Map<String, Long> = emptyMap(),
    val myAntiRatings: Set<String> = emptySet(),
    val selectedPostIds: Set<String> = emptySet(),
    val inspectedPeerId: String? = null,
    val inspectedPeerName: String = "",
    val inspectedPeerHearts: Int = 0,
    val inspectedPeerHeartMine: Boolean = false,
    val inspectedPeerAntiCount: Int = 0,
    val inspectedPeerAntiMine: Boolean = false,
    val inspectedPeerAntiWarning: Boolean = false,
    val inspectedPeerAntiUntilMs: Long = 0,
    /** Мой идентификатор узла: по нему решается, можно ли править пост. */
    val myId: String = "",
    val isLoading: Boolean = true,
    val creating: Boolean = false,
    val error: String? = null,
    /** Реакции по сообщениям канала: ключ - id сообщения-поста. */
    val reactions: Map<String, List<com.vladimir.messenger.data.reaction.ReactionSummary>> = emptyMap(),
    /** Передачи файлов этого канала (по хэшу файла карточка под постом находит свою). */
    val transfers: List<com.vladimir.messenger.data.local.entity.FileTransferEntity> = emptyList(),
    /** Файлы, которые сейчас просим у сидов ([com.vladimir.messenger.util.GroupFileMarker.key]). */
    val pendingFiles: Set<String> = emptySet(),
    /** Скольким подписчикам отдана моя общая копия файла (K2), по ключу файла. */
    val servedFiles: Map<String, Int> = emptyMap(),
    /** Принятый файл, который человек просит сохранить в папку (системное окно). */
    val pendingSave: com.vladimir.messenger.data.local.entity.FileTransferEntity? = null,
    /** Файл к новому посту: подготовлен (хэш, копия) и ждёт «Опубликовать». */
    val stagedFile: com.vladimir.messenger.util.GroupFileMarker.Info? = null,
    /** Идёт подготовка выбранного файла. */
    val isPreparingFile: Boolean = false,
    /** р250: опросы канала: ключ - id сообщения-поста, под которым висит карточка. */
    val polls: Map<String, com.vladimir.messenger.data.group.PollSummary> = emptyMap(),
)

@HiltViewModel
class ChannelViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val groupRepository: GroupRepository,
    private val chatRepository: com.vladimir.messenger.data.repository.ChatRepository,
    private val messageDao: MessageDao,
    private val savedItems: com.vladimir.messenger.data.repository.SavedItemsRepository,
    private val reactionRepository: com.vladimir.messenger.data.reaction.ReactionRepository,
    private val postViews: com.vladimir.messenger.data.channel.PostViewRepository,
    private val postCounters: com.vladimir.messenger.data.channel.PostCounterRepository,
    private val groupFiles: com.vladimir.messenger.data.group.GroupFileSwarm,
    private val fileTransferDao: com.vladimir.messenger.data.local.dao.FileTransferDao,
    private val fileTransferRouter: com.vladimir.messenger.data.file.FileTransferRouter,
    private val hearts: com.vladimir.messenger.data.heart.HeartRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {

    private val channelId: String = savedStateHandle.get<String>("channelId").orEmpty()

    private val _uiState = MutableStateFlow(ChannelUiState(channelId = channelId))
    val uiState: StateFlow<ChannelUiState> = _uiState.asStateFlow()

    /** Повторная просьба о постах после появления членства уже ушла. */
    private var postsAskedAfterJoin = false

    init {
        observe()
        observeReactions()
        observeTransfers()
        observeAntiRatings()
        // р250: опросы под постами канала.
        observePolls()
        // Вступивший позже не застал посты - просим у владельца последние
        // (раз за запуск на канал; владельцу и уже полным лентам это не нужно).
        viewModelScope.launch {
            runCatching { groupRepository.requestPosts(channelId) }
        }
        // На большом канале просмотры и реакции стекаются к владельцу и
        // администраторам (рой, этап 3): сводные числа спрашиваем у них.
        viewModelScope.launch {
            runCatching { postCounters.requestCounters(channelId) }
        }
    }

    /** Закрепить/открепить пост канала (личное закреп, до 10; зеркалится своим устройствам). */
    fun togglePostPin(post: ChannelPost) {
        viewModelScope.launch(Dispatchers.IO) {
            val pinned = !post.isPinned
            val result = runCatching { chatRepository.setMessagePinned(post.messageId, pinned) }
                .getOrElse { error ->
                    _uiState.update { it.copy(error = error.message ?: "Не удалось изменить закреп") }
                    return@launch
                }
            when (result) {
                MessagePinMutation.LIMIT_REACHED ->
                    _uiState.update { it.copy(error = MessagePinPolicy.LIMIT_REACHED_MESSAGE) }
                MessagePinMutation.SCOPE_CONFLICT ->
                    _uiState.update { it.copy(error = MessagePinPolicy.SCOPE_CONFLICT_MESSAGE) }
                MessagePinMutation.NOT_FOUND ->
                    _uiState.update { it.copy(error = "Пост уже недоступен") }
                MessagePinMutation.UPDATED,
                MessagePinMutation.UNCHANGED -> {
                    // Закреп личный, поэтому не рассылаем его подписчикам
                    // канала; только переносим на другое своё устройство.
                    runCatching {
                        com.vladimir.messenger.data.mirror.MirrorHub.publishPin(post.messageId, pinned)
                    }
                }
            }
        }
    }

    /** Реакции канала: поток уже уведён на IO внутри репозитория. */
    private fun observeReactions() {
        viewModelScope.launch {
            reactionRepository.observeChat(channelId).collect { map ->
                _uiState.update { it.copy(reactions = map) }
            }
        }
    }

    // ── р250: опросы под постами канала ────────────────────────────────────

    /** Карточки опросов: ключ - id сообщения-поста. */
    private fun observePolls() {
        viewModelScope.launch {
            groupRepository.observePolls(channelId).collect { polls ->
                _uiState.update { it.copy(polls = polls) }
            }
        }
    }

    /** Отметить вариант (повторный тап в опросе с одним выбором голос отзывает). */
    fun togglePollChoice(poll: com.vladimir.messenger.data.group.PollSummary, index: Int) {
        val current = poll.myChoices
        val next = if (poll.multiChoice) {
            if (index in current) current - index else (current + index).sorted()
        } else {
            if (current == listOf(index)) emptyList() else listOf(index)
        }
        viewModelScope.launch {
            groupRepository.votePoll(channelId, poll.pollId, next)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    /** Закрыть опрос: автор поста, владелец или администратор канала. */
    fun closePoll(pollId: String) {
        viewModelScope.launch {
            groupRepository.closePoll(channelId, pollId)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    // ── Файлы канала (рой, этапы 9-10) ────────────────────────────────────────

    /** Передачи файлов канала и мои просьбы - карточке файла под постом. */
    private fun observeTransfers() {
        groupFiles.warmUp()
        viewModelScope.launch {
            fileTransferDao.observeForChat(channelId)
                .flowOn(kotlinx.coroutines.Dispatchers.IO)
                .collect { list -> _uiState.update { it.copy(transfers = list) } }
        }
        viewModelScope.launch {
            groupFiles.pendingKeys.collect { keys -> _uiState.update { it.copy(pendingFiles = keys) } }
        }
        viewModelScope.launch {
            groupFiles.servedCounts.collect { counts -> _uiState.update { it.copy(servedFiles = counts) } }
        }
    }

    /**
     * Файл к новому посту выбран в системном окне: посчитать хэш, положить
     * копию для раздачи. Уйдёт визиткой вместе с постом по «Опубликовать».
     */
    fun onFileSelected(uri: android.net.Uri) {
        if (_uiState.value.isPreparingFile) return
        viewModelScope.launch {
            _uiState.update { it.copy(isPreparingFile = true, error = null) }
            try {
                val previous = _uiState.value.stagedFile
                val info = groupFiles.stage(channelId, uri)
                if (previous != null && previous.sha256 != info.sha256) groupFiles.unstage(channelId, previous.sha256)
                _uiState.update { it.copy(stagedFile = info) }
            } catch (e: Exception) {
                android.util.Log.w("ChannelVM", "post file stage failed", e)
                _uiState.update { it.copy(error = "Файл не приложен: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isPreparingFile = false) }
            }
        }
    }

    /** Убрать приложенный, но ещё не опубликованный файл. */
    fun clearStagedFile() {
        val staged = _uiState.value.stagedFile ?: return
        _uiState.update { it.copy(stagedFile = null) }
        viewModelScope.launch { groupFiles.unstage(channelId, staged.sha256) }
    }

    /** Нажатие «Скачать» на карточке файла поста: попросить у автора или соседей. */
    fun requestFile(post: ChannelPost) {
        val info = post.file ?: return
        viewModelScope.launch {
            runCatching { groupFiles.request(channelId, post.messageId, info, post.authorId, manual = true) }
                .onFailure { e -> _uiState.update { it.copy(error = "Не удалось запросить файл: ${e.message}") } }
        }
    }

    /** Моя авторская копия файла (превью картинки, «Поделиться»). */
    fun authorCopyFor(sha256: String): java.io.File? = groupFiles.authorCopy(channelId, sha256)

    /** Принятый файл: копия у меня (для «Поделиться»). */
    fun receivedFileFor(transfer: com.vladimir.messenger.data.local.entity.FileTransferEntity): java.io.File? =
        fileTransferRouter.receivedFileFor(transfer)

    fun requestSaveReceivedFile(transfer: com.vladimir.messenger.data.local.entity.FileTransferEntity) {
        if (transfer.direction != "INCOMING" || transfer.state != "COMPLETE") return
        _uiState.update { it.copy(pendingSave = transfer) }
    }

    fun onSaveTargetPicked(target: android.net.Uri?) {
        val transfer = _uiState.value.pendingSave
        _uiState.update { it.copy(pendingSave = null) }
        if (target == null || transfer == null) return
        viewModelScope.launch {
            val ok = runCatching { fileTransferRouter.exportReceivedFile(transfer, target) }.getOrDefault(false)
            _uiState.update {
                it.copy(error = if (ok) "Сохранено: ${transfer.displayName}" else "Не удалось сохранить файл")
            }
        }
    }

    /** «Поделиться» принятым файлом (или своей копией) через системное окно. */
    fun shareFile(file: java.io.File, displayName: String, mediaType: String) {
        val ctx = appContext
        viewModelScope.launch {
            runCatching {
                val dir = java.io.File(ctx.cacheDir, "shared").apply { mkdirs() }
                val dst = java.io.File(dir, displayName.ifBlank { file.name })
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { file.copyTo(dst, overwrite = true) }
                val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", dst)
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType(mediaType.ifBlank { "application/octet-stream" })
                    .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val chooser = android.content.Intent.createChooser(intent, "Поделиться")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(chooser)
            }.onFailure { e -> _uiState.update { it.copy(error = "Не удалось поделиться: ${e.message}") } }
        }
    }

    /** Поставить или снять реакцию на пост. */
    fun toggleReaction(messageId: String, emoji: String) {
        viewModelScope.launch { reactionRepository.toggle(channelId, messageId, emoji) }
    }

    /**
     * Сжать выбранные картинки в фоне и вернуть строки для поста (по порядку
     * выбора). Нечитаемые и не влезшие картинки пропускаются, о них сообщаем.
     */
    fun prepareImages(
        context: android.content.Context,
        uris: List<android.net.Uri>,
        onReady: (List<String>) -> Unit,
    ) {
        viewModelScope.launch {
            val encoded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                uris.mapNotNull { uri -> InlineImage.compressUri(context, uri) }
            }
            if (encoded.size < uris.size) {
                val lost = uris.size - encoded.size
                _uiState.update {
                    it.copy(error = if (lost == 1) "Одну картинку не удалось прикрепить" else "Не удалось прикрепить картинок: $lost")
                }
            }
            onReady(encoded)
        }
    }

    /** Убрать свою реакцию с поста. */
    fun removeReaction(messageId: String) {
        viewModelScope.launch { reactionRepository.removeMine(channelId, messageId) }
    }

    /** Пост открыли: отмечаем просмотр и сообщаем остальным. */
    fun onPostSeen(topicId: String) {
        viewModelScope.launch {
            runCatching { postViews.markViewed(channelId, topicId) }
        }
    }

    /**
     * Ссылка на конкретный пост.
     *
     * Ведёт в канал и сразу к нужной записи. Открывший её из чужого
     * мессенджера попадает в APU: схема p2pmessenger:// перехватывается
     * приложением.
     */
    suspend fun postLink(topicId: String): String? =
        runCatching { groupRepository.postLinkFor(channelId, topicId) }.getOrNull()

    private fun observe() {
        viewModelScope.launch {
            val snapshots = combine(
                groupRepository.observeGroup(channelId),
                groupRepository.observeMembers(channelId),
                groupRepository.observeTopics(channelId),
                messageDao.observeChatMessages(channelId),
                postViews.observeCounts(),
            ) { channel, members, topics, messages, viewCounts ->
                val names = members.associate { it.nodeId to it.displayName }
                val me = members.firstOrNull { it.isMe }
                val byTopic = messages.groupBy { it.topicId.orEmpty() }
                val posts = topics.mapNotNull { topic ->
                    val thread = byTopic[topic.id].orEmpty()
                    // Куски фотографий - не сообщения: пост это первое
                    // ТЕКСТОВОЕ сообщение темы, комментарии - остальные текстовые.
                    val texts = thread.filter { !InlineImage.isPart(it.content) }
                    val first = texts.firstOrNull() ?: return@mapNotNull null
                    val parts = thread.filter {
                        InlineImage.isPart(it.content) && it.senderId == first.senderId
                    }
                    val partTexts = parts.map { it.content }
                    val images = ArrayList<String>()
                    // Старый способ: одна картинка прямо в тексте поста.
                    InlineImage.extractB64(first.content)?.let { images.add(it) }
                    images.addAll(InlineImage.assemble(partTexts))
                    val promised = InlineImage.photoCount(first.content)
                    ChannelPost(
                        topicId = topic.id,
                        messageId = first.id,
                        // В ленте показываем только личный закреп, не GroupWire-пин темы.
                        isPinned = first.isPinned && first.pinnedBy == null,
                        title = topic.name,
                        // Длинный текст едет кусками (рой, этап 3): склеиваем;
                        // пока куски в пути - текст с многоточием.
                        text = InlineImage.fullText(first.id, first.content, partTexts).text,
                        images = images,
                        pendingPhotos = (promised - images.size).coerceAtLeast(0),
                        authorId = first.senderId,
                        authorName = names[first.senderId]?.takeIf { it.isNotBlank() }
                            ?: "Участник " + first.senderId.takeLast(4),
                        timeMs = first.timestamp,
                        comments = (texts.size - 1).coerceAtLeast(0),
                        // Непрочитанные комментарии: счётчик темы ведёт
                        // GroupRepository (прибавляет на каждом чужом сообщении),
                        // сбрасывает GroupChatViewModel.markRead при чтении.
                        unreadComments = topic.unreadCount,
                        views = viewCounts[topic.id] ?: 0,
                        // Файл поста (этап 10): визитка в тексте, сам файл
                        // тянется у автора или у соседей по нажатию.
                        file = com.vladimir.messenger.util.GroupFileMarker.parse(first.content),
                    )
                }.sortedBy { it.timeMs }

                val role = me?.role ?: GroupRole.MEMBER
                val perms = me?.permissions ?: 0L
                val isOwner = channel?.ownerId != null && channel.ownerId == me?.nodeId
                ChannelSnapshot(
                    channel = channel,
                    posts = posts,
                    members = members,
                    canPost = GroupRole.isAdminOrOwner(role),
                    canModerate = isOwner || com.vladimir.messenger.data.group.GroupPermissions.canModerateMessages(role, perms),
                    myId = me?.nodeId.orEmpty(),
                )
            }
            // Большой канал (рой, этап 4): комментарии ходят через владельца и
            // администраторов, на телефоне читателя их лишь часть. Число под
            // постом - от сборщика, если он его сообщил; ниже своего не падает.
            combine(snapshots, groupRepository.commentCounts) { snapshot, atHub ->
                if (atHub.isEmpty()) {
                    snapshot
                } else {
                    snapshot.copy(
                        posts = snapshot.posts.map { post ->
                            val known = atHub[post.topicId] ?: return@map post
                            if (known > post.comments) post.copy(comments = known) else post
                        },
                    )
                }
            }.collect { snapshot ->
                _uiState.update {
                    it.copy(
                        channel = snapshot.channel,
                        posts = snapshot.posts,
                        members = snapshot.members,
                        // В ленте закрепляются именно публикации, а не комментарии.
                        pinnedPostIds = snapshot.posts.filter { it.isPinned }.map { post -> post.messageId },
                        error = MessagePinPolicy.visibleError(it.error, snapshot.posts.count { post -> post.isPinned }),
                        canPost = snapshot.canPost,
                        canModerate = snapshot.canModerate,
                        myId = snapshot.myId,
                        isLoading = false,
                    )
                }
                // Только что вступили: карточка канала и членство появились
                // уже после открытия экрана, и просьба из init ушла впустую.
                // Просим ещё раз - один раз, когда канал стал «нашим».
                if (snapshot.channel != null && snapshot.myId.isNotBlank() && !postsAskedAfterJoin) {
                    postsAskedAfterJoin = true
                    viewModelScope.launch { runCatching { groupRepository.requestPosts(channelId) } }
                    viewModelScope.launch { runCatching { postCounters.requestCounters(channelId) } }
                }
            }
        }
    }

    /** То, что собирается из пяти потоков одним пакетом. */
    private data class ChannelSnapshot(
        val channel: GroupSummary?,
        val posts: List<ChannelPost>,
        val members: List<com.vladimir.messenger.data.group.MemberSummary>,
        val canPost: Boolean,
        val canModerate: Boolean,
        val myId: String,
    )

    /**
     * Новый пост: создаём тему и сразу пишем в неё текст, а следом - куски
     * фотографий (до [InlineImage.MAX_PHOTOS]).
     *
     * Тема нужна, чтобы у поста было своё место для комментариев - ровно как
     * обсуждение под постом в Телеграме.
     */
    fun createPost(
        text: String,
        photos: List<String> = emptyList(),
        /** р250: опрос к посту; null - публикация без опроса. */
        poll: com.vladimir.messenger.data.group.PollDraft? = null,
    ) {
        val stripped = InlineImage.stripImage(text)
        val attached = photos.filter { it.isNotBlank() }.take(InlineImage.MAX_PHOTOS)
        val staged = _uiState.value.stagedFile
        if (stripped.isEmpty() && attached.isEmpty() && staged == null && poll == null) return
        _uiState.update { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            // р250: опрос без подписи - пост всё равно нужен: текстом поста
            // становится вопрос опроса, иначе старые телефоны (и лента без
            // карточки) увидели бы пустую публикацию.
            val question = poll?.question.orEmpty().trim()
            val words = stripped.ifBlank { question }
            // Заголовок берём из ТЕКСТА, а не из служебных строк картинок.
            val title = words.lineSequence().firstOrNull().orEmpty().trim()
                .take(GroupRepository.POST_TITLE_CHARS)
                .ifBlank { staged?.displayName?.take(GroupRepository.POST_TITLE_CHARS) ?: "Пост" }
            // Файл поста (рой, этап 10): визитка последней строкой, как в группе;
            // сам файл подписчики попросят у автора и друг у друга.
            val body = if (staged == null) words else com.vladimir.messenger.util.GroupFileMarker.compose(words, staged)
            groupRepository.createTopic(channelId, title)
                .onSuccess { topic ->
                    groupRepository.sendMessage(channelId, topic.id, body, attached, poll = poll)
                        .onFailure { e ->
                            _uiState.update {
                                it.copy(creating = false, error = e.message ?: "Не удалось опубликовать пост")
                            }
                        }
                        .onSuccess { _uiState.update { it.copy(stagedFile = null) } }
                    _uiState.update { it.copy(creating = false) }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(creating = false, error = e.message ?: "Не удалось создать пост")
                    }
                }
        }
    }

    /**
     * Изменить текст поста. Фотографии остаются прежними. Заголовок темы
     * (первая строка) обновляется в репозитории вместе с текстом.
     */
    fun editPost(post: ChannelPost, newText: String) {
        val words = InlineImage.stripImage(newText)
        if (words.isEmpty() && post.images.isEmpty() && post.pendingPhotos == 0) {
            _uiState.update { it.copy(error = "Пост не может быть пустым") }
            return
        }
        _uiState.update { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            groupRepository.editMessage(channelId, post.messageId, words)
                .onFailure { e ->
                    _uiState.update { it.copy(creating = false, error = e.message ?: "Не удалось изменить пост") }
                }
                .onSuccess { _uiState.update { it.copy(creating = false) } }
        }
    }

    /** Переслать пост канала себе в «Избранное» - с текстом и фотографиями. */
    fun savePostToFavorites(post: ChannelPost) {
        val source = _uiState.value.channel?.title.orEmpty()
        val body = if (post.title.isBlank()) post.text else post.title + "\n\n" + post.text
        viewModelScope.launch {
            savedItems.saveText(
                body,
                if (source.isBlank()) "" else "Канал " + source,
                com.vladimir.messenger.data.repository.SavedOrigin(
                    kind = com.vladimir.messenger.data.repository.SavedOrigin.CHANNEL,
                    id = channelId,
                    topicId = post.topicId,
                ),
                photos = post.images,
            )
            _uiState.update { it.copy(error = "Добавлено в избранное") }
        }
    }

    /** Раунд 124: файл из карточки канала/комментариев - в избранное. */
    fun saveFileToFavorites(transfer: com.vladimir.messenger.data.local.entity.FileTransferEntity) {
        viewModelScope.launch {
            val source = _uiState.value.channel?.title.orEmpty()
            val result = savedItems.saveFile(transfer, if (source.isBlank()) "Канал" else "Канал " + source)
            _uiState.update {
                it.copy(
                    error = when (result) {
                        com.vladimir.messenger.data.repository.SaveResult.Saved -> "Добавлено в избранное"
                        com.vladimir.messenger.data.repository.SaveResult.AlreadySaved -> "Уже в избранном"
                        com.vladimir.messenger.data.repository.SaveResult.FileNotReady -> "Файл ещё не получен полностью"
                    },
                )
            }
        }
    }

    /**
     * Репост поста в другое приложение одним нажатием: одно меню «Поделиться»,
     * одно сообщение у получателя - картинка (одно фото или сетка из всех
     * фото поста) с подписью из текста и ссылки «Открыть в APU». Почему так, а
     * не файлами по отдельности - в [com.vladimir.messenger.util.PhotoShare].
     * Подготовка (ссылка из базы, сборка картинки) идёт в фоне.
     */
    fun sharePost(context: android.content.Context, post: ChannelPost) {
        val app = context.applicationContext
        viewModelScope.launch {
            val link = postLink(post.topicId)
            val text = com.vladimir.messenger.util.PhotoShare.buildText(
                title = post.title.ifBlank { "Пост" },
                body = post.text,
                link = link,
            )
            val uri = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    com.vladimir.messenger.util.PhotoShare.writeShareImage(app, post.images, post.topicId)
                }.getOrNull()
            }
            // Полный текст - в буфер обмена: если мессенджер урежет подпись,
            // человек вставит его одним нажатием.
            com.vladimir.messenger.util.PhotoShare.copyToClipboard(app, "Пост APU", text)
            val intent = if (uri != null) {
                com.vladimir.messenger.util.PhotoShare.buildImageIntent(
                    uri,
                    com.vladimir.messenger.util.PhotoShare.captionFor(text, link),
                )
            } else {
                com.vladimir.messenger.util.PhotoShare.buildTextIntent(text)
            }
            if (!com.vladimir.messenger.util.PhotoShare.open(app, intent, "Поделиться постом")) {
                _uiState.update { it.copy(error = "Не удалось поделиться") }
            }
        }
    }

    // ===== Раунд 212: «Поделиться в APU» - пересылка поста внутрь APU =====

    /** Цели пересылки: друзья и сообщества (тот же список, что в личных чатах). */
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
     * Раунд 213: короткая ссылка-приглашение для «Пригласить по QR коду»
     * в шапке канала. Null - бессрочной ссылки ещё нет.
     */
    suspend fun inviteQrLink(): String? =
        runCatching { groupRepository.inviteQrLink(channelId) }.getOrNull()

    /**
     * Раунд 212 (владелец: в меню канала не хватает «Поделиться в APU»):
     * переслать пост другу или в группу/канал. Уходит текст поста
     * (заголовок + текст) с шапкой-источником «↩ Переслано из «канал»» -
     * по ней открывается сам канал. Фотографии не едут: они живут кусками
     * своей темы (как при пересылке постов групп, раунд 203).
     */
    fun forwardPost(
        post: ChannelPost,
        sourceLabel: String,
        target: com.vladimir.messenger.ui.components.ForwardTarget,
        onResult: (Boolean) -> Unit,
    ) {
        viewModelScope.launch {
            val words = listOf(post.title, com.vladimir.messenger.util.InlineImage.stripImage(post.text))
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
            // Слаг и владелец - из бессрочной ссылки канала: попавший в шапку
            // сможет вступиться, даже если он не участник.
            val invite = runCatching { groupRepository.postLinkFor(channelId, post.topicId) }
                .getOrNull()
                ?.let { com.vladimir.messenger.data.group.GroupInviteLinks.parseTarget(it) }
            val body = com.vladimir.messenger.util.ForwardMarker.buildBody(
                true,
                channelId,
                post.topicId,
                sourceLabel,
                words,
                slug = invite?.slug.orEmpty(),
                ownerId = invite?.ownerId.orEmpty(),
                isChannel = true,
            )
            val res = if (target.kind == com.vladimir.messenger.ui.components.ForwardKind.FRIEND) {
                chatRepository.sendMessage(target.id, "", body)
            } else {
                groupRepository.sendMessage(target.id, target.topicId ?: "", body)
            }
            onResult(res.isSuccess)
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    fun toggleSelectPost(messageId: String) {
        _uiState.update { state ->
            val next = state.selectedPostIds.toMutableSet()
            if (!next.add(messageId)) next.remove(messageId)
            state.copy(selectedPostIds = next)
        }
    }

    fun clearPostSelection() {
        _uiState.update { it.copy(selectedPostIds = emptySet()) }
    }

    suspend fun countAuthorMessages(authorId: String): Int =
        groupRepository.countMessagesByAuthor(channelId, authorId)

    fun applyPostModeration(
        messageIds: List<String>,
        authorId: String,
        result: com.vladimir.messenger.ui.components.ApuModerationResult,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(selectedPostIds = emptySet()) }
            when {
                result.deleteAllInGroup -> {
                    groupRepository.deleteAllMessagesInGroup(channelId)
                        .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
                }
                result.deleteAllFromAuthor && authorId.isNotBlank() -> {
                    groupRepository.deleteAllMessagesFromAuthor(channelId, authorId)
                        .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
                }
                else -> {
                    for (msgId in messageIds) {
                        if (result.deleteForAll) {
                            groupRepository.deleteMessageForAll(channelId, msgId)
                                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
                        } else {
                            runCatching { groupRepository.deleteMessageForMe(channelId, msgId) }
                                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
                        }
                    }
                }
            }
            if (result.giveAntiRating && authorId.isNotBlank()) {
                val peers = _uiState.value.members.map { it.nodeId }
                hearts.addAntiRating(authorId, peers)
            }
            if (result.restrictAuthorPermissions && authorId.isNotBlank()) {
                groupRepository.restrictMemberPermissions(channelId, authorId, result.allowedMemberMask)
                    .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
            }
            if (result.blockAuthor && authorId.isNotBlank()) {
                groupRepository.setMemberBlocked(channelId, authorId, true)
                    .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
            }
        }
    }

    private fun observeAntiRatings() {
        viewModelScope.launch {
            hearts.observeAntiCountsMap().collect { map ->
                _uiState.update { state ->
                    val peer = state.inspectedPeerId
                    state.copy(
                        antiRatings = map,
                        inspectedPeerAntiCount = if (peer != null) (map[peer] ?: 0) else state.inspectedPeerAntiCount,
                    )
                }
            }
        }
        viewModelScope.launch {
            hearts.observeAntiWarnings().collect { warnings ->
                _uiState.update { state ->
                    val peer = state.inspectedPeerId
                    val until = if (peer != null) (warnings[peer] ?: 0L) else state.inspectedPeerAntiUntilMs
                    state.copy(
                        antiWarnings = warnings,
                        inspectedPeerAntiWarning = peer != null && until > 0L,
                        inspectedPeerAntiUntilMs = until,
                    )
                }
            }
        }
        viewModelScope.launch {
            val me = kotlinx.coroutines.withContext(Dispatchers.IO) {
                appContext.getSharedPreferences("p2p_prefs", android.content.Context.MODE_PRIVATE)
                    .getString("node_id", null)
                    ?.takeIf { it.isNotBlank() }
                    ?: com.vladimir.messenger.data.RustBridge.nodeId().orEmpty()
            }
            if (me.isBlank()) return@launch
            hearts.observeMyAntiTargets(me).collect { targets ->
                _uiState.update { state ->
                    val peer = state.inspectedPeerId
                    state.copy(
                        myAntiRatings = targets,
                        inspectedPeerAntiMine = if (peer != null) (peer in targets) else state.inspectedPeerAntiMine,
                    )
                }
            }
        }
    }

    fun openPeerProfile(peerId: String, peerName: String) {
        if (peerId.isBlank()) return
        _uiState.update {
            it.copy(
                inspectedPeerId = peerId,
                inspectedPeerName = peerName,
                inspectedPeerAntiCount = it.antiRatings[peerId] ?: 0,
                inspectedPeerAntiMine = peerId in it.myAntiRatings,
            )
        }
        viewModelScope.launch {
            val hCount = hearts.countOf(peerId)
            val hMine = hearts.isMine(peerId)
            val anti = hearts.antiStateOf(peerId)
            val aMine = hearts.isMyAntiRating(peerId)
            _uiState.update { state ->
                if (state.inspectedPeerId != peerId) state else state.copy(
                    inspectedPeerHearts = hCount,
                    inspectedPeerHeartMine = hMine,
                    inspectedPeerAntiCount = anti.total,
                    inspectedPeerAntiMine = aMine,
                    inspectedPeerAntiWarning = anti.warning,
                    inspectedPeerAntiUntilMs = anti.warningUntilMs,
                )
            }
        }
    }

    fun closePeerProfile() {
        _uiState.update { it.copy(inspectedPeerId = null) }
    }

    fun togglePeerHeart(peerId: String) {
        if (peerId.isBlank()) return
        viewModelScope.launch {
            val mine = hearts.toggle(peerId)
            val count = hearts.countOf(peerId)
            _uiState.update { state ->
                if (state.inspectedPeerId != peerId) state else state.copy(
                    inspectedPeerHeartMine = mine,
                    inspectedPeerHearts = count,
                )
            }
        }
    }

    fun togglePeerAntiRating(peerId: String) {
        if (peerId.isBlank()) return
        viewModelScope.launch {
            val peers = _uiState.value.members.map { it.nodeId }
            val mine = hearts.toggleAntiRating(peerId, peers)
            val anti = hearts.antiStateOf(peerId)
            _uiState.update { state ->
                if (state.inspectedPeerId != peerId) state else state.copy(
                    inspectedPeerAntiMine = mine,
                    inspectedPeerAntiCount = anti.total,
                    inspectedPeerAntiWarning = anti.warning,
                    inspectedPeerAntiUntilMs = anti.warningUntilMs,
                )
            }
        }
    }
}
