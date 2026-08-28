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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
            groupDao.observeGroups().collect { entities ->
                val me = com.vladimir.messenger.data.RustBridge.nodeId().orEmpty()
                val roles = if (me.isBlank()) {
                    emptyMap()
                } else {
                    groupDao.getMyMemberships(me).associate { it.groupId to it.role }
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
        val managesGroup = groups.any {
            it.myRole == GroupRole.OWNER || it.myRole == GroupRole.ADMIN
        }
        if (managesGroup) sections += InboxSection.AdminGroups
        // Каналов в приложении пока нет: раздел «Админ каналы» появится вместе
        // с первым созданным каналом, пустым он показываться не должен.
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
    private fun ChatListUiState.withItems(): ChatListUiState {
        val query = searchQuery.trim()
        val personal = filterChats(chats, query).map {
            InboxItem.Personal(it, it.lastMessageTime ?: 0L)
        }
        val groupRows = groups.filter { row ->
            when (section) {
                InboxSection.All, InboxSection.Groups -> true
                InboxSection.AdminGroups ->
                    row.myRole == GroupRole.OWNER || row.myRole == GroupRole.ADMIN
                InboxSection.Chats, InboxSection.Channels, InboxSection.AdminChannels -> false
            }
        }.filter { row ->
            query.isBlank() ||
                row.title.contains(query, ignoreCase = true) ||
                row.preview?.contains(query, ignoreCase = true) == true
        }.map { InboxItem.Group(it, it.timeMs ?: 0L) }

        val items = when (section) {
            InboxSection.Chats -> personal
            InboxSection.Groups, InboxSection.AdminGroups -> groupRows
            // Заглушки: каналов в приложении ещё нет.
            InboxSection.Channels, InboxSection.AdminChannels -> emptyList()
            InboxSection.All -> personal + groupRows
        }.sortedByDescending { it.sortKey }

        return copy(items = items)
    }

    /** Видный бейдж ранга на главном экране: имя ранга + число квалифицированных друзей. */
    private fun refreshRankBadge() {
        val qualified = com.vladimir.messenger.data.referral.ReferralRankStore
            .qualifiedDirectCount(appContext)
        val rank = com.vladimir.messenger.data.file.FileTransferRankPolicy
            .entitlement(qualified)
        _uiState.update { state ->
            state.copy(rankBadge = "🎖 ${rank.rankName}")
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

    private fun filterChats(chats: List<Chat>, query: String): List<Chat> {
        if (query.isBlank()) return chats
        return chats.filter {
            it.contactName.contains(query, ignoreCase = true) ||
            it.lastMessage?.contains(query, ignoreCase = true) == true
        }
    }
}