package com.vladimir.messenger.ui.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.domain.model.Chat
import com.vladimir.messenger.domain.usecase.ObserveNetworkStatusUseCase
import com.vladimir.messenger.data.group.GroupRole
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.repository.NetworkStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
    /**
     * Готовые списки ВСЕХ разделов, посчитанные один раз в фоне.
     *
     * Листалка во время жеста показывает две страницы сразу, и раньше соседняя
     * страница пересчитывала свой список прямо во время отрисовки. Теперь все
     * разделы считаются разом при изменении данных, а страница только берёт
     * готовое.
     */
    val itemsBySection: Map<InboxSection, List<InboxItem>> = emptyMap(),
    /** Сколько строк сейчас загружено с диска (окно). */
    val loadedWindow: Int = 50,
    /** Есть ли ещё строки за пределами окна - список досыпается на прокрутке. */
    val canLoadMore: Boolean = false,
)

@kotlinx.coroutines.FlowPreview
@kotlinx.coroutines.ExperimentalCoroutinesApi
@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val observeNetworkStatusUseCase: ObserveNetworkStatusUseCase,
    private val groupDao: GroupDao,
    private val chatRepository: com.vladimir.messenger.data.repository.ChatRepository,
    private val groupRepository: com.vladimir.messenger.data.group.GroupRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatListUiState())
    val uiState: StateFlow<ChatListUiState> = _uiState.asStateFlow()

    /** Выбранный раздел и строка поиска - отдельными потоками. */
    private val section = MutableStateFlow(InboxSection.All)
    private val searchQuery = MutableStateFlow("")

    /**
     * Размер загруженного окна.
     *
     * С диска читается не вся переписка, а верхушка списка. Когда прокрутка
     * подходит к концу загруженного, окно растёт на страницу - и запрос к базе
     * перезапускается сам, потому что окно участвует в потоке данных.
     */
    private val windowSize = MutableStateFlow(PAGE_SIZE)

    /**
     * Мой идентификатор узла. Спрашиваем ядро ОДИН раз за жизнь экрана.
     *
     * Раньше его дёргали на каждое обновление списка групп. Вызов уходит в
     * ядро и ждёт его внутренний замок, поэтому при частых обновлениях экран
     * замирал. Идентификатор за время работы не меняется - незачем спрашивать
     * повторно.
     */
    private val myNodeId = CompletableDeferred<String>()

    init {
        viewModelScope.launch {
            myNodeId.complete(
                withContext(Dispatchers.IO) {
                    // Сначала спрашиваем сохранённый идентификатор, и только
                    // если его нет - ядро. Раньше список чатов ЖДАЛ ответа
                    // ядра: пока движок поднимается (а он ждёт сеть), главный
                    // экран показывал пустоту с крутилкой. Идентификатор не
                    // меняется, он лежит в настройках с первого запуска -
                    // читать его с диска мгновенно и сети не требует.
                    val saved = appContext
                        .getSharedPreferences("p2p_prefs", android.content.Context.MODE_PRIVATE)
                        .getString("node_id", null)
                    saved?.takeIf { it.isNotBlank() }
                        ?: com.vladimir.messenger.data.RustBridge.nodeId().orEmpty()
                }
            )
        }
        observeInbox()
        observeNetworkStatus()
        refreshRankBadge()
    }

    /**
     * Единый конвейер главного экрана.
     *
     * Раньше список чатов и список групп жили двумя независимыми потоками, и
     * каждый сам себе перекладывал состояние. Любое изменение перестраивало всё
     * заново, а тяжёлая часть - разбор по разделам, поиск и сортировка -
     * выполнялась дважды и в неудачный момент.
     *
     * Теперь источники сведены в один поток. Тяжёлая часть считается на
     * рабочем потоке (`Dispatchers.Default`), главному потоку достаётся только
     * готовый результат. Поиск ждёт паузы в наборе, поэтому набор строки
     * больше не пересобирает список на каждую букву.
     */
    private fun observeInbox() {
        val queryFlow = searchQuery
            .debounce { if (it.isBlank()) 0L else SEARCH_DEBOUNCE_MS }
            .distinctUntilChanged()

        // Окно и строка поиска вместе решают, ЧТО спросить у базы. При пустом
        // поиске - верхушка списка длиной в окно; при поиске - совпадения по
        // всей таблице, тоже ограниченные окном. Меняется окно или запрос -
        // подписка на базу пересоздаётся, и приходит уже нужный кусок.
        val sourcesFlow = combine(windowSize, queryFlow) { window, query -> window to query }
            .distinctUntilChanged()
            .flatMapLatest { (window, query) ->
                val chats = if (query.isBlank()) {
                    chatRepository.observeChatsWindow(window)
                } else {
                    chatRepository.searchChats(query.trim(), window)
                }
                val groups = if (query.isBlank()) {
                    combine(
                        groupDao.observeGroupsWindow(window),
                        groupDao.observeChannelsWindow(window),
                    ) { g, c -> g + c }
                } else {
                    groupDao.searchGroups(query.trim(), window)
                }
                combine(chats, groups, chatRepository.observeChatCount()) { c, g, total ->
                    Sources(c, g, query, window, total)
                }
            }
            .flowOn(Dispatchers.IO)

        viewModelScope.launch {
            combine(
                sourcesFlow,
                section,
            ) { src, current ->
                Snapshot(src.chats, toInboxGroups(src.groups), src.query, current, src.window, src.total)
            }
                // Сборка разделов, поиск и сортировка - на рабочем потоке.
                // Главный поток получает готовые списки и только рисует их.
                .map { snap -> buildState(snap) }
                .flowOn(Dispatchers.Default)
                .collect { built ->
                    _uiState.update { state ->
                        state.copy(
                            chats = built.chats,
                            filteredChats = built.filtered,
                            groups = built.groups,
                            sections = built.sections,
                            section = built.section,
                            // searchQuery НЕ трогаем: поле ввода принадлежит
                            // набору текста. Пересчёт отстаёт от набора на паузу,
                            // и запись отставшего значения возвращала бы курсор
                            // к уже стёртым буквам.
                            items = built.items,
                            itemsBySection = built.itemsBySection,
                            loadedWindow = built.window,
                            canLoadMore = built.canLoadMore,
                            isLoading = false,
                            error = null,
                        )
                    }
                }
        }
    }

    /** Сырые источники одного пересчёта. */
    private data class Snapshot(
        val chats: List<Chat>,
        val groups: List<InboxGroup>,
        val query: String,
        val section: InboxSection,
        val window: Int,
        val totalChats: Int,
    )

    /** То, что пришло из базы за один заход. */
    private data class Sources(
        val chats: List<Chat>,
        val groups: List<com.vladimir.messenger.data.local.entity.GroupEntity>,
        val query: String,
        val window: Int,
        val total: Int,
    )

    /** Готовый результат пересчёта - всё, что нужно экрану. */
    private data class Built(
        val chats: List<Chat>,
        val filtered: List<Chat>,
        val groups: List<InboxGroup>,
        val sections: List<InboxSection>,
        val section: InboxSection,
        val query: String,
        val items: List<InboxItem>,
        val itemsBySection: Map<InboxSection, List<InboxItem>>,
        val window: Int,
        val canLoadMore: Boolean,
    )

    /** Роли считаем один раз на пересчёт, а не на каждую строку списка. */
    private suspend fun toInboxGroups(entities: List<com.vladimir.messenger.data.local.entity.GroupEntity>): List<InboxGroup> {
        val me = myNodeId.await()
        val roles = if (me.isBlank()) {
            emptyMap()
        } else {
            withContext(Dispatchers.IO) {
                groupDao.getMyMemberships(me).associate { it.groupId to it.role }
            }
        }
        return entities.filter { !it.isLeft }.map { g ->
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
    }

    /**
     * Один проход по данным вместо прохода на каждый раздел.
     *
     * Личные чаты и группы фильтруются поиском ровно один раз, затем
     * раскладываются по разделам. Сортировка тоже одна: общий список уже
     * упорядочен, разделы наследуют порядок.
     */
    private fun buildState(snap: Snapshot): Built {
        val query = snap.query.trim()
        val sections = sectionsFor(snap.groups)
        // Во время поиска показываем «Все»: человек ищет собеседника, а не
        // раздел, и находка в группах не должна прятаться только потому, что
        // открыта вкладка «Чаты».
        val section = when {
            query.isNotBlank() -> InboxSection.All
            snap.section in sections -> snap.section
            else -> InboxSection.All
        }

        val filteredChats = filterChats(snap.chats, query)
        val filteredGroups = snap.groups.filter { row ->
            query.isBlank() ||
                row.title.contains(query, ignoreCase = true) ||
                row.preview?.contains(query, ignoreCase = true) == true
        }

        val personal = filteredChats
            .map { InboxItem.Personal(it, it.lastMessageTime ?: 0L) }
            .sortedByDescending { it.sortKey }
        val groupItems = filteredGroups
            .map { InboxItem.Group(it, it.timeMs ?: 0L) }
            .sortedByDescending { it.sortKey }

        val manages = { row: InboxGroup ->
            row.myRole == GroupRole.OWNER || row.myRole == GroupRole.ADMIN
        }
        val plainGroups = groupItems.filter { !it.group.isChannel }
        val channels = groupItems.filter { it.group.isChannel }

        val bySection = mutableMapOf<InboxSection, List<InboxItem>>()
        for (target in sections) {
            bySection[target] = when (target) {
                InboxSection.All -> merge(personal, groupItems)
                InboxSection.Chats -> personal
                InboxSection.Groups -> plainGroups
                InboxSection.Channels -> channels
                InboxSection.AdminGroups -> plainGroups.filter { manages(it.group) }
                InboxSection.AdminChannels -> channels.filter { manages(it.group) }
            }
        }

        // Досыпать есть что, если база вернула полное окно (значит за ним
        // что-то осталось) или всего чатов больше, чем в окне.
        val canLoadMore = snap.chats.size >= snap.window ||
            snap.groups.size >= snap.window ||
            snap.totalChats > snap.chats.size

        return Built(
            chats = snap.chats,
            filtered = filteredChats,
            groups = snap.groups,
            sections = sections,
            section = section,
            query = snap.query,
            items = bySection[section].orEmpty(),
            itemsBySection = bySection,
            window = snap.window,
            canLoadMore = canLoadMore,
        )
    }

    /**
     * Слияние двух уже упорядоченных списков.
     *
     * Общая сортировка склеенного списка - лишняя работа: обе половины уже
     * стоят по времени. Идём по ним разом и берём тот, что свежее.
     */
    private fun merge(a: List<InboxItem>, b: List<InboxItem>): List<InboxItem> {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val out = ArrayList<InboxItem>(a.size + b.size)
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            if (a[i].sortKey >= b[j].sortKey) out.add(a[i++]) else out.add(b[j++])
        }
        while (i < a.size) out.add(a[i++])
        while (j < b.size) out.add(b[j++])
        return out
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
    fun onSectionSelected(target: InboxSection) {
        // Списки всех разделов уже готовы, поэтому переключение мгновенное:
        // берём посчитанное, ничего не пересобирая.
        section.value = target
        _uiState.update { state ->
            state.copy(
                section = target,
                items = state.itemsBySection[target].orEmpty(),
            )
        }
    }

    /**
     * Список конкретного раздела для страницы листалки.
     *
     * Ничего не считает - отдаёт готовое, посчитанное в фоне.
     */
    fun itemsOf(state: ChatListUiState, target: InboxSection): List<InboxItem> =
        state.itemsBySection[target].orEmpty()

    /** Видный бейдж ранга на главном экране: имя ранга + число квалифицированных друзей. */
    private fun refreshRankBadge() {
        // Ранг лежит в SharedPreferences: первое чтение открывает файл с диска,
        // а это блокирующая работа. На главном потоке она задерживает первую
        // отрисовку списка, поэтому уводим её в фон.
        //
        // Подписка вместо разового чтения: промокод поднимает ранг, пока экран
        // уже открыт, и раньше на главной так и висел прежний ранг до
        // перезапуска приложения.
        viewModelScope.launch {
            com.vladimir.messenger.data.referral.ReferralRankStore.changes.collect {
                val rank = withContext(Dispatchers.IO) {
                    val qualified = com.vladimir.messenger.data.referral.ReferralRankStore
                        .qualifiedDirectCount(appContext)
                    com.vladimir.messenger.data.file.FileTransferRankPolicy
                        .entitlement(qualified)
                }
                _uiState.update { state ->
                    // Только название: медаль рисует отдельный значок
                    // RankMedal - объёмный и анимированный, эмодзи такого не
                    // умеет и выглядит по-разному на разных прошивках.
                    state.copy(rankBadge = rank.rankName)
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
        // Поле ввода откликается сразу, а пересчёт списка ждёт паузы в наборе.
        _uiState.update { it.copy(searchQuery = query) }
        searchQuery.value = query
    }

    // ── Действия меню «⋮» в пузырях ───────────────────────────────────────

    /** Удалить личный чат вместе с историей (только на этом телефоне). */
    fun deleteChat(chatId: String) {
        viewModelScope.launch(Dispatchers.IO) { chatRepository.deleteChat(chatId) }
    }

    /** Очистить переписку, сам чат остаётся. */
    fun clearChatHistory(chatId: String) {
        viewModelScope.launch(Dispatchers.IO) { chatRepository.clearHistory(chatId) }
    }

    /** Сбросить счётчик непрочитанных личного чата. */
    fun markChatRead(chatId: String) {
        viewModelScope.launch(Dispatchers.IO) { chatRepository.markAsRead(chatId) }
    }

    /** Сбросить счётчик непрочитанных группы или канала. */
    fun markGroupRead(groupId: String) {
        viewModelScope.launch(Dispatchers.IO) { groupDao.markGroupRead(groupId) }
    }

    /** Выйти из группы или канала (не владельцу). */
    fun leaveGroup(groupId: String) {
        viewModelScope.launch(Dispatchers.IO) { groupRepository.leaveGroup(groupId) }
    }

    /**
     * Позвать в группу: готовим ссылку и отдаём её системному «Поделиться».
     *
     * Одна ссылка работает в обе стороны. У кого APU уже стоит - откроет её и
     * сразу попадёт в группу. У кого нет - увидит в том же сообщении, откуда
     * скачать приложение, поставит и войдёт по той же ссылке. Поэтому
     * отдельного пункта «для своих» и «для чужих» не нужно.
     */
    fun shareGroupInvite(groupId: String, onReady: (title: String, link: String) -> Unit) {
        viewModelScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                val group = runCatching { groupDao.getGroupById(groupId) }.getOrNull()
                    ?: return@withContext null
                val slug = group.inviteSlug
                if (slug.isBlank()) return@withContext null
                group.title to com.vladimir.messenger.data.group.GroupInviteLinks.build(
                    slug = slug,
                    groupId = group.id,
                    ownerId = group.ownerId,
                    isChannel = group.isChannel,
                    // Частная группа принимает по заявке - вступающий телефон
                    // должен честно написать «заявка отправлена».
                    requestApproval = !group.isPublic,
                )
            }
            if (prepared != null) onReady(prepared.first, prepared.second)
        }
    }

    /** Удалить свою группу или канал у всех участников (только владельцу). */
    fun deleteGroup(groupId: String) {
        viewModelScope.launch(Dispatchers.IO) { groupRepository.deleteGroup(groupId) }
    }

    private fun filterChats(chats: List<Chat>, query: String): List<Chat> {
        if (query.isBlank()) return chats
        return chats.filter {
            it.contactName.contains(query, ignoreCase = true) ||
            it.lastMessage?.contains(query, ignoreCase = true) == true
        }
    }

    /**
     * Прокрутка подошла к концу загруженного - досыпаем следующую страницу.
     *
     * Вызывается из списка. Повторные вызовы безопасны: окно растёт только
     * если есть что грузить, а сама дозагрузка идёт запросом к базе, не
     * трогая уже показанные строки.
     */
    fun loadMore() {
        if (!_uiState.value.canLoadMore) return
        val current = windowSize.value
        if (current >= MAX_WINDOW) return
        windowSize.value = (current + PAGE_SIZE).coerceAtMost(MAX_WINDOW)
    }

    companion object {
        /** Пауза в наборе, после которой пересчитывается поиск. */
        private const val SEARCH_DEBOUNCE_MS = 200L

        /**
         * Страница загрузки. Экран показывает около десятка строк, поэтому
         * полсотни хватает и на первый экран, и на запас под быструю прокрутку.
         */
        const val PAGE_SIZE = 50

        /**
         * Предел окна. Даже при десятках тысяч чатов в памяти не окажется
         * больше этого числа строк: дальше прокрутка идёт по уже загруженному,
         * а глубже человек ищет поиском, который спрашивает всю таблицу.
         */
        const val MAX_WINDOW = 3000
    }
}
