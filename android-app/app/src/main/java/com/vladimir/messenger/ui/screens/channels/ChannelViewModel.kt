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
)

@HiltViewModel
class ChannelViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val groupRepository: GroupRepository,
    private val messageDao: MessageDao,
    private val savedItems: com.vladimir.messenger.data.repository.SavedItemsRepository,
    private val reactionRepository: com.vladimir.messenger.data.reaction.ReactionRepository,
    private val postViews: com.vladimir.messenger.data.channel.PostViewRepository,
) : ViewModel() {

    private val channelId: String = savedStateHandle.get<String>("channelId").orEmpty()

    private val _uiState = MutableStateFlow(ChannelUiState(channelId = channelId))
    val uiState: StateFlow<ChannelUiState> = _uiState.asStateFlow()

    /** Повторная просьба о постах после появления членства уже ушла. */
    private var postsAskedAfterJoin = false

    init {
        observe()
        observeReactions()
        // Вступивший позже не застал посты - просим у владельца последние
        // (раз за запуск на канал; владельцу и уже полным лентам это не нужно).
        viewModelScope.launch {
            runCatching { groupRepository.requestPosts(channelId) }
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
            combine(
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
                    val images = ArrayList<String>()
                    // Старый способ: одна картинка прямо в тексте поста.
                    InlineImage.extractB64(first.content)?.let { images.add(it) }
                    images.addAll(InlineImage.assemble(parts.map { it.content }))
                    val promised = InlineImage.photoCount(first.content)
                    ChannelPost(
                        topicId = topic.id,
                        messageId = first.id,
                        title = topic.name,
                        text = InlineImage.stripImage(first.content),
                        images = images,
                        pendingPhotos = (promised - images.size).coerceAtLeast(0),
                        authorId = first.senderId,
                        authorName = names[first.senderId]?.takeIf { it.isNotBlank() }
                            ?: "Участник " + first.senderId.takeLast(4),
                        timeMs = first.timestamp,
                        comments = (texts.size - 1).coerceAtLeast(0),
                        views = viewCounts[topic.id] ?: 0,
                    )
                }.sortedBy { it.timeMs }

                ChannelSnapshot(
                    channel = channel,
                    posts = posts,
                    canPost = GroupRole.isAdminOrOwner(me?.role ?: GroupRole.MEMBER),
                    myId = me?.nodeId.orEmpty(),
                )
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
        if (stripped.isEmpty() && attached.isEmpty()) return
        _uiState.update { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            // Заголовок берём из ТЕКСТА, а не из служебных строк картинок.
            val title = stripped.lineSequence().firstOrNull().orEmpty().trim()
                .take(GroupRepository.POST_TITLE_CHARS)
                .ifBlank { "Пост" }
            groupRepository.createTopic(channelId, title)
                .onSuccess { topic ->
                    groupRepository.sendMessage(channelId, topic.id, stripped, attached)
                        .onFailure { e ->
                            _uiState.update {
                                it.copy(creating = false, error = e.message ?: "Не удалось опубликовать пост")
                            }
                        }
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

    /** Переслать пост канала себе в «Избранное». */
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
            )
            _uiState.update { it.copy(error = "Добавлено в избранное") }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }
}
