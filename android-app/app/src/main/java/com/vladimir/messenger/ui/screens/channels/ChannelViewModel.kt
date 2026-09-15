package com.vladimir.messenger.ui.screens.channels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.data.group.GroupRepository
import com.vladimir.messenger.data.group.GroupRole
import com.vladimir.messenger.data.group.GroupSummary
import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.util.InlineImage
import dagger.hilt.android.lifecycle.HiltViewModel
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
    /** Сколько разных людей открыли пост. */
    val views: Int = 0,
    /** Файл, приложенный к посту (визитка `APUFILE1:`, рой этап 9-10); null - файла нет. */
    val file: com.vladimir.messenger.util.GroupFileMarker.Info? = null,
)

data class ChannelUiState(
    val channelId: String = "",
    val channel: GroupSummary? = null,
    val posts: List<ChannelPost> = emptyList(),
    /** Писать посты может владелец и администраторы; комментарии - все. */
    val canPost: Boolean = false,
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
    /** Принятый файл, который человек просит сохранить в папку (системное окно). */
    val pendingSave: com.vladimir.messenger.data.local.entity.FileTransferEntity? = null,
    /** Файл к новому посту: подготовлен (хэш, копия) и ждёт «Опубликовать». */
    val stagedFile: com.vladimir.messenger.util.GroupFileMarker.Info? = null,
    /** Идёт подготовка выбранного файла. */
    val isPreparingFile: Boolean = false,
)

@HiltViewModel
class ChannelViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val groupRepository: GroupRepository,
    private val messageDao: MessageDao,
    private val savedItems: com.vladimir.messenger.data.repository.SavedItemsRepository,
    private val reactionRepository: com.vladimir.messenger.data.reaction.ReactionRepository,
    private val postViews: com.vladimir.messenger.data.channel.PostViewRepository,
    private val postCounters: com.vladimir.messenger.data.channel.PostCounterRepository,
    private val groupFiles: com.vladimir.messenger.data.group.GroupFileSwarm,
    private val fileTransferDao: com.vladimir.messenger.data.local.dao.FileTransferDao,
    private val fileTransferRouter: com.vladimir.messenger.data.file.FileTransferRouter,
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

    /** Реакции канала: поток уже уведён на IO внутри репозитория. */
    private fun observeReactions() {
        viewModelScope.launch {
            reactionRepository.observeChat(channelId).collect { map ->
                _uiState.update { it.copy(reactions = map) }
            }
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
                        views = viewCounts[topic.id] ?: 0,
                        // Файл поста (этап 10): визитка в тексте, сам файл
                        // тянется у автора или у соседей по нажатию.
                        file = com.vladimir.messenger.util.GroupFileMarker.parse(first.content),
                    )
                }.sortedBy { it.timeMs }

                ChannelSnapshot(
                    channel = channel,
                    posts = posts,
                    canPost = GroupRole.isAdminOrOwner(me?.role ?: GroupRole.MEMBER),
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
                        canPost = snapshot.canPost,
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
        val canPost: Boolean,
        val myId: String,
    )

    /**
     * Новый пост: создаём тему и сразу пишем в неё текст, а следом - куски
     * фотографий (до [InlineImage.MAX_PHOTOS]).
     *
     * Тема нужна, чтобы у поста было своё место для комментариев - ровно как
     * обсуждение под постом в Телеграме.
     */
    fun createPost(text: String, photos: List<String> = emptyList()) {
        val stripped = InlineImage.stripImage(text)
        val attached = photos.filter { it.isNotBlank() }.take(InlineImage.MAX_PHOTOS)
        val staged = _uiState.value.stagedFile
        if (stripped.isEmpty() && attached.isEmpty() && staged == null) return
        _uiState.update { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            // Заголовок берём из ТЕКСТА, а не из служебных строк картинок.
            val title = stripped.lineSequence().firstOrNull().orEmpty().trim()
                .take(GroupRepository.POST_TITLE_CHARS)
                .ifBlank { staged?.displayName?.take(GroupRepository.POST_TITLE_CHARS) ?: "Пост" }
            // Файл поста (рой, этап 10): визитка последней строкой, как в группе;
            // сам файл подписчики попросят у автора и друг у друга.
            val body = if (staged == null) stripped else com.vladimir.messenger.util.GroupFileMarker.compose(stripped, staged)
            groupRepository.createTopic(channelId, title)
                .onSuccess { topic ->
                    groupRepository.sendMessage(channelId, topic.id, body, attached)
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

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }
}
