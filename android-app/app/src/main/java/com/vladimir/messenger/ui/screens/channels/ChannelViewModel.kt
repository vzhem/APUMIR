package com.vladimir.messenger.ui.screens.channels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.data.group.GroupRepository
import com.vladimir.messenger.data.group.GroupRole
import com.vladimir.messenger.data.group.GroupSummary
import com.vladimir.messenger.data.local.dao.MessageDao
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
 * Порядок ленты - от старых к новым, как в переписке: свежий пост всегда
 * внизу, и экран при открытии прокручивается туда.
 */
data class ChannelPost(
    val topicId: String,
    /** id самого сообщения-поста: к нему привязываются реакции. */
    val messageId: String,
    val title: String,
    val text: String,
    /** Прикреплённая к посту картинка (jpeg в base64) или null. */
    val imageB64: String? = null,
    val authorName: String,
    val timeMs: Long,
    val comments: Int,
)

data class ChannelUiState(
    val channelId: String = "",
    val channel: GroupSummary? = null,
    val posts: List<ChannelPost> = emptyList(),
    /** Писать посты может владелец и администраторы; комментарии - все. */
    val canPost: Boolean = false,
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
) : ViewModel() {

    private val channelId: String = savedStateHandle.get<String>("channelId").orEmpty()

    private val _uiState = MutableStateFlow(ChannelUiState(channelId = channelId))
    val uiState: StateFlow<ChannelUiState> = _uiState.asStateFlow()

    init {
        observe()
        observeReactions()
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
     * Сжать выбранную картинку в фоне и вернуть строку для поста.
     * Пустой результат означает, что картинка не читается или не влезла.
     */
    fun prepareImage(
        context: android.content.Context,
        uri: android.net.Uri,
        onReady: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            val encoded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.vladimir.messenger.util.InlineImage.compressUri(context, uri)
            }
            if (encoded == null) {
                _uiState.update { it.copy(error = "Картинку не удалось прикрепить") }
            }
            onReady(encoded)
        }
    }

    /** Убрать свою реакцию с поста. */
    fun removeReaction(messageId: String) {
        viewModelScope.launch { reactionRepository.removeMine(channelId, messageId) }
    }

    private fun observe() {
        viewModelScope.launch {
            combine(
                groupRepository.observeGroup(channelId),
                groupRepository.observeMembers(channelId),
                groupRepository.observeTopics(channelId),
                messageDao.observeChatMessages(channelId),
            ) { channel, members, topics, messages ->
                val names = members.associate { it.nodeId to it.displayName }
                val me = members.firstOrNull { it.isMe }
                val byTopic = messages.groupBy { it.topicId.orEmpty() }
                val posts = topics.mapNotNull { topic ->
                    val thread = byTopic[topic.id].orEmpty()
                    val first = thread.firstOrNull() ?: return@mapNotNull null
                    ChannelPost(
                        topicId = topic.id,
                        messageId = first.id,
                        title = topic.name,
                        text = com.vladimir.messenger.util.InlineImage.stripImage(first.content),
                        imageB64 = com.vladimir.messenger.util.InlineImage.extractB64(first.content),
                        authorName = names[first.senderId]?.takeIf { it.isNotBlank() }
                            ?: "Участник " + first.senderId.takeLast(4),
                        timeMs = first.timestamp,
                        comments = (thread.size - 1).coerceAtLeast(0),
                    )
                }.sortedBy { it.timeMs }

                ChannelSnapshot(
                    channel = channel,
                    posts = posts,
                    canPost = GroupRole.isAdminOrOwner(me?.role ?: GroupRole.MEMBER),
                )
            }.collect { snapshot ->
                _uiState.update {
                    it.copy(
                        channel = snapshot.channel,
                        posts = snapshot.posts,
                        canPost = snapshot.canPost,
                        isLoading = false,
                    )
                }
            }
        }
    }

    /** То, что собирается из четырёх потоков одним пакетом. */
    private data class ChannelSnapshot(
        val channel: GroupSummary?,
        val posts: List<ChannelPost>,
        val canPost: Boolean,
    )

    /**
     * Новый пост: создаём тему и сразу пишем в неё текст.
     *
     * Тема нужна, чтобы у поста было своё место для комментариев - ровно как
     * обсуждение под постом в Телеграме.
     */
    fun createPost(text: String, imageB64: String? = null) {
        val stripped = text.trim()
        val body = if (imageB64.isNullOrBlank()) {
            stripped
        } else {
            com.vladimir.messenger.util.InlineImage.attach(stripped, imageB64)
        }
        if (body.isEmpty()) return
        _uiState.update { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            // Заголовок берём из ТЕКСТА, а не из служебной строки картинки.
            val title = stripped.lineSequence().firstOrNull().orEmpty().trim().take(40)
                .ifBlank { "Пост" }
            groupRepository.createTopic(channelId, title)
                .onSuccess { topic ->
                    groupRepository.sendMessage(channelId, topic.id, body)
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
