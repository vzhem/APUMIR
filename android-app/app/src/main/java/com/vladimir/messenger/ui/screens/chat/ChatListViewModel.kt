package com.vladimir.messenger.ui.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.domain.model.Chat
import com.vladimir.messenger.domain.usecase.GetChatsUseCase
import com.vladimir.messenger.domain.usecase.ObserveNetworkStatusUseCase
import com.vladimir.messenger.data.group.GroupRole
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.repository.NetworkStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

/**
 * Разделы главного экрана.
 *
 * Полоска разделов под рангом пролистывается вбок. Разделы «Админ ...»
 * появляются только у того, кто группу или канал создал (или назначен
 * администратором): остальным они не показываются вовсе.
 */
enum class InboxSection(val title: String) {
    All("Все"),
    Chats("Чаты"),
    Groups("Группы"),
    Channels("Каналы"),
    AdminGroups("Админ группы"),
    AdminChannels("Админ каналы"),
}

/** Группа в общем списке главного экрана. */
data class InboxGroup(
    val id: String,
    val title: String,
    val preview: String?,
    val timeMs: Long?,
    val unreadCount: Int,
    val memberCount: Int,
    val isPublic: Boolean,
    /** OWNER | ADMIN | MEMBER - роль этого телефона. */
    val myRole: String,
    /** Канал открывается лентой постов, группа - общим чатом. */
    val isChannel: Boolean = false,
)

/** Строка общего списка: личный чат или группа. */
sealed interface InboxItem {
    val sortKey: Long

    data class Personal(val chat: Chat, override val sortKey: Long) : InboxItem

    data class Group(val group: InboxGroup, override val sortKey: Long) : InboxItem
}

data class ChatListUiState(
    val chats: List<Chat>        = emptyList(),
    val isLoading: Boolean       = true,
    val error: String?           = null,
    val networkStatus: NetworkStatus = NetworkStatus.Disconnected,
    val searchQuery: String      = "",
    val filteredChats: List<Chat> = emptyList(),
    val rankBadge: String        = "",
    /** Группы, в которых телефон состоит (без тех, из которых вышел). */
    val groups: List<InboxGroup> = emptyList(),
    /** Выбранный раздел. */
    val section: InboxSection    = InboxSection.All,
    /** Разделы, которые этому телефону показывать: админские - по факту наличия групп. */
    val sections: List<InboxSection> = listOf(
        InboxSection.All,
        InboxSection.Chats,
        InboxSection.Groups,
        InboxSection.Channels,
    ),
    /** Готовый список выбранного раздела, уже отфильтрованный поиском. */
    val items: List<InboxItem>   = emptyList(),
)

@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val getChatsUseCase: GetChatsUseCase,
    private val observeNetworkStatusUseCase: ObserveNetworkStatusUseCase,
    private val groupDao: GroupDao,
    private val chatRepository: com.vladimir.messenger.data.repository.ChatRepository,
    private val groupRepository: com.vladimir.messenger.data.group.GroupRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatListUiState())
    val uiState: StateFlow<ChatListUiState> = _uiState.asStateFlow()

    init {
        loadChats()
        observeGroups()
        observeNetworkStatus()
        refreshRankBadge()
    }

    /**
     * Группы для разделов «Группы» и «Админ группы».
     *
     * Отдельный поток: список чатов (таблица chats) групп не содержит, у групп
     * своя таблица со своим предпросмотром и счётчиком непрочитанных.
     */
    private fun observeGroups() {
        viewModelScope.launch {
            // Группы и каналы лежат в одной таблице, но выборки разные: в
            // разделе «Группы» каналы не показываются и наоборот.
            combine(groupDao.observeGroups(), groupDao.observeChannels()) { groups, channels ->
                groups + channels
            }.collect { entities ->
                // Опрос ядра и чтение ролей - строго не на главном потоке.
                // nodeId() уходит в Rust через FFI и ждёт внутренний замок
                // движка: пока движок занят раздачей присутствия, вызов
                // подвисает. На главном потоке это и есть «не отвечает».
                val me = withContext(Dispatchers.IO) {
                    com.vladimir.messenger.data.RustBridge.nodeId().orEmpty()
                }
                val roles = if (me.isBlank()) {
                    emptyMap()
                } else {
                    withContext(Dispatchers.IO) {
                        groupDao.getMyMemberships(me).associate { it.groupId to it.role }
                    }
                }
                val groups = entities.filter { !it.isLeft }.map { g ->
                    InboxGroup(
                        id = g.id,
                        title = g.title,
                        preview = g.lastMessagePreview,
                        timeMs = g.lastMessageAtMs,
                        unreadCount = g.unreadCount,
                        memberCount = g.memberCount,
                        isPublic = g.isPublic,
                        myRole = if (g.ownerId == me) GroupRole.OWNER else roles[g.id] ?: GroupRole.MEMBER,
                        isChannel = g.isChannel,
                    )
                }
                _uiState.update { state ->
                    state.copy(
                        groups = groups,
                        sections = sectionsFor(groups),
                        isLoading = false,
                    ).withItems()
                }
            }
        }
    }

    /** Админские разделы - только тому, у кого есть своя группа или канал. */
    private fun sectionsFor(groups: List<InboxGroup>): List<InboxSection> {
        val sections = mutableListOf(
            InboxSection.All,
            InboxSection.Chats,
            InboxSection.Groups,
            InboxSection.Channels,
        )
        val manages = { row: InboxGroup ->
            row.myRole == GroupRole.OWNER || row.myRole == GroupRole.ADMIN
        }
        if (groups.any { !it.isChannel && manages(it) }) sections += InboxSection.AdminGroups
        // Раздел появляется только у того, кто канал создал или ведёт:
        // пустым он показываться не должен.
        if (groups.any { it.isChannel && manages(it) }) sections += InboxSection.AdminChannels
        return sections
    }

    /** Переключение раздела полоской под рангом. */
    fun onSectionSelected(section: InboxSection) {
        _uiState.update { state -> state.copy(section = section).withItems() }
    }

    /**
     * Собирает список выбранного раздела из чатов и групп.
     *
     * Личные чаты и группы сортируются вместе - по времени последнего
     * сообщения, как в Телеграме.
     */
    private fun ChatListUiState.withItems(): ChatListUiState = copy(items = itemsFor(section))

    /**
     * Список ЛЮБОГО раздела, не только выбранного.
     *
     * Нужен листалке главного экрана: во время движения пальцем на экране видны
     * сразу две страницы, поэтому соседний раздел обязан уметь построить свой
     * список независимо от того, какой раздел сейчас выбран.
     */
    private fun ChatListUiState.itemsFor(target: InboxSection): List<InboxItem> {
        val query = searchQuery.trim()
        val personal = filterChats(chats, query).map {
            InboxItem.Personal(it, it.lastMessageTime ?: 0L)
        }
        val adminOnly = target == InboxSection.AdminGroups ||
            target == InboxSection.AdminChannels
        val groupRows = groups.filter { row ->
            when (target) {
                InboxSection.All -> true
                InboxSection.Groups, InboxSection.AdminGroups -> !row.isChannel
                InboxSection.Channels, InboxSection.AdminChannels -> row.isChannel
                InboxSection.Chats -> false
            }
        }.filter { row ->
            !adminOnly || row.myRole == GroupRole.OWNER || row.myRole == GroupRole.ADMIN
        }.filter { row ->
            query.isBlank() ||
                row.title.contains(query, ignoreCase = true) ||
                row.preview?.contains(query, ignoreCase = true) == true
        }.map { InboxItem.Group(it, it.timeMs ?: 0L) }

        return when (target) {
            InboxSection.Chats -> personal
            InboxSection.All -> personal + groupRows
            else -> groupRows
        }.sortedByDescending { it.sortKey }
    }

    /**
     * Список конкретного раздела для страницы листалки.
     *
     * Считается от переданного состояния, а не от `_uiState.value`: страница
     * читает `uiState` из композиции, поэтому новое сообщение или удалённый чат
     * перерисовывают её сразу.
     */
    fun itemsOf(state: ChatListUiState, section: InboxSection): List<InboxItem> =
        state.itemsFor(section)

    /** Видный бейдж ранга на главном экране: имя ранга + число квалифицированных друзей. */
    private fun refreshRankBadge() {
        // Ранг лежит в SharedPreferences: первое чтение открывает файл с диска,
        // а это блокирующая работа. На главном потоке она задерживает первую
        // отрисовку списка, поэтому уводим её в фон.
        viewModelScope.launch {
            val rank = withContext(Dispatchers.IO) {
                val qualified = com.vladimir.messenger.data.referral.ReferralRankStore
                    .qualifiedDirectCount(appContext)
                com.vladimir.messenger.data.file.FileTransferRankPolicy
                    .entitlement(qualified)
            }
            _uiState.update { state ->
                state.copy(rankBadge = "🎖 ${rank.rankName}")
            }
        }
    }

    private fun loadChats() {
        viewModelScope.launch {
            // Подписываемся на Flow — экран автоматически обновляется
            // при появлении новых сообщений
            getChatsUseCase()
                .collect { chats ->
                    _uiState.update { state ->
                        state.copy(
                            chats         = chats,
                            filteredChats = filterChats(chats, state.searchQuery),
                            isLoading     = false,
                            error         = null
                        ).withItems()
                    }
                }
        }
    }

    private fun observeNetworkStatus() {
        viewModelScope.launch {
            observeNetworkStatusUseCase()
                .collect { status ->
                    _uiState.update { it.copy(networkStatus = status) }
                }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { state ->
            state.copy(
                searchQuery   = query,
                filteredChats = filterChats(state.chats, query)
            ).withItems()
        }
    }

    // ── Действия меню «⋮» в пузырях ───────────────────────────────────────

    /** Удалить личный чат вместе с историей (только на этом телефоне). */
    fun deleteChat(chatId: String) {
        viewModelScope.launch { chatRepository.deleteChat(chatId) }
    }

    /** Очистить переписку, сам чат остаётся. */
    fun clearChatHistory(chatId: String) {
        viewModelScope.launch { chatRepository.clearHistory(chatId) }
    }

    /** Сбросить счётчик непрочитанных личного чата. */
    fun markChatRead(chatId: String) {
        viewModelScope.launch { chatRepository.markAsRead(chatId) }
    }

    /** Сбросить счётчик непрочитанных группы или канала. */
    fun markGroupRead(groupId: String) {
        viewModelScope.launch { groupDao.markGroupRead(groupId) }
    }

    /** Выйти из группы или канала (не владельцу). */
    fun leaveGroup(groupId: String) {
        viewModelScope.launch { groupRepository.leaveGroup(groupId) }
    }

    /** Удалить свою группу или канал у всех участников (только владельцу). */
    fun deleteGroup(groupId: String) {
        viewModelScope.launch { groupRepository.deleteGroup(groupId) }
    }

    private fun filterChats(chats: List<Chat>, query: String): List<Chat> {
        if (query.isBlank()) return chats
        return chats.filter {
            it.contactName.contains(query, ignoreCase = true) ||
            it.lastMessage?.contains(query, ignoreCase = true) == true
        }
    }
}