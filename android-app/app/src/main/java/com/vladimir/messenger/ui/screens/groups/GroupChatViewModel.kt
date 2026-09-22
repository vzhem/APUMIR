package com.vladimir.messenger.ui.screens.groups

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.data.group.GroupPermissions
import com.vladimir.messenger.data.group.GroupRepository
import com.vladimir.messenger.data.group.GroupRole
import com.vladimir.messenger.data.group.GroupSummary
import com.vladimir.messenger.data.group.MemberSummary
import com.vladimir.messenger.data.group.TopicSummary
import com.vladimir.messenger.data.local.entity.MessageEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GroupChatUiState(
    val groupId: String = "",
    val group: GroupSummary? = null,
    /** Все свои группы и каналы — для левой колонки значков на экране чата. */
    val allGroups: List<GroupSummary> = emptyList(),
    val topics: List<TopicSummary> = emptyList(),
    val selectedTopicId: String? = null,
    val messages: List<MessageEntity> = emptyList(),
    val pinned: List<MessageEntity> = emptyList(),
    val members: List<MemberSummary> = emptyList(),
    val me: MemberSummary? = null,
    val canPin: Boolean = false,
    val canManageTopics: Boolean = false,
    val error: String? = null,
    val sending: Boolean = false,
    /** Открыты с темой из канала (комментарии): сразу лента, не список тем. */
    val startInTopic: Boolean = false,
    /** Реакции по сообщениям: ключ - id сообщения. */
    val reactions: Map<String, List<com.vladimir.messenger.data.reaction.ReactionSummary>> = emptyMap(),
    /**
     * Сколько комментариев в этой ветке у сборщика (владельца канала) сверх
     * тех, что уже на телефоне: на большом канале комментарии приходят по
     * запросу, и лента показывает кнопку «Показать ещё N». 0 - нечего.
     */
    val moreComments: Int = 0,
    // ── Файлы группы (рой, этап 9) ──
    /** Передачи файлов этой группы (по хэшу файла карточка находит свою). */
    val transfers: List<com.vladimir.messenger.data.local.entity.FileTransferEntity> = emptyList(),
    /** Файлы, которые сейчас просим у сидов ([GroupFileMarker.key]). */
    val pendingFiles: Set<String> = emptySet(),
    /** Скольким участникам отдана моя общая копия файла (K2), по ключу файла. */
    val servedFiles: Map<String, Int> = emptyMap(),
    /** Идёт подготовка выбранного файла (хэш, копия). */
    val isPreparingFile: Boolean = false,
    /** Файл готов и ждёт отправки вместе с подписью: показывается над полем ввода. */
    val stagedFile: com.vladimir.messenger.util.GroupFileMarker.Info? = null,
    /** Можно ли прикреплять файлы: ранг «Круг друзей» и право «Отправка медиа». */
    val canAttach: Boolean = false,
    val attachLockedHint: String = "",
    /** Каталог GIF (наш сервер): гифки, курсор «ещё», состояние. */
    val gifItems: List<com.vladimir.messenger.data.gif.GifItem> = emptyList(),
    val gifNext: String = "",
    val gifLoading: Boolean = false,
    /** Почему каталог недоступен (текст для диалога); null - доступен. */
    val gifError: String? = null,
    /** Раунд 121: свой каталог роя. tab: "swarm" | "external". */
    val gifTab: String = "swarm",
    val myGifs: List<com.vladimir.messenger.data.gif.GifLibEntry> = emptyList(),
    val swarmGifs: List<com.vladimir.messenger.data.gif.SwarmGif> = emptyList(),
    val swarmStatus: String? = null,
    /** Принятый файл, который человек просит сохранить в папку (системное окно). */
    val pendingSave: com.vladimir.messenger.data.local.entity.FileTransferEntity? = null,
)

@HiltViewModel
class GroupChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val groupRepository: GroupRepository,
    private val savedItems: com.vladimir.messenger.data.repository.SavedItemsRepository,
    private val reactionRepository: com.vladimir.messenger.data.reaction.ReactionRepository,
    private val groupFiles: com.vladimir.messenger.data.group.GroupFileSwarm,
    private val fileTransferDao: com.vladimir.messenger.data.local.dao.FileTransferDao,
    private val fileTransferRouter: com.vladimir.messenger.data.file.FileTransferRouter,
    private val botApi: com.vladimir.messenger.service.BotApi,
    private val chatRepository: com.vladimir.messenger.data.repository.ChatRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {

    private val groupId: String = savedStateHandle.get<String>("groupId").orEmpty()

    /**
     * Тема, которую надо открыть сразу. Так канал открывает комментарии
     * конкретного поста, а не первую тему подряд.
     */
    private val requestedTopicId: String? =
        savedStateHandle.get<String>("topicId")?.takeIf { it.isNotBlank() }

    private val _uiState = MutableStateFlow(
        GroupChatUiState(groupId = groupId, startInTopic = requestedTopicId != null)
    )
    val uiState: StateFlow<GroupChatUiState> = _uiState.asStateFlow()

    init {
        observeGroup()
        observeAllGroups()
        observeMembers()
        observeTopics()
        // Вступивший позже не застал создание тем - просим список у владельца.
        viewModelScope.launch { groupRepository.requestTopics(groupId) }
        // В канале ещё и сами посты: по ссылке на пост человек попадает сюда,
        // минуя ленту, и без этого видел бы пустые комментарии к пустому посту.
        // Репозиторий сам молчит, если это группа, владелец или уже просили.
        viewModelScope.launch { runCatching { groupRepository.requestPosts(groupId) } }
        observeReactions()
        observeCommentCounts()
        observeTransfers()
        // Закрепы подписываем на выбранную тему, а не на всю группу:
        // observePinned(topicId) стартует вместе с лентой сообщений.
        observeGifArrivals()
    }

    /** Раунд 121: гифка из роя пришла файлом - сразу приложить к сообщению. */
    private fun observeGifArrivals() {
        viewModelScope.launch {
            com.vladimir.messenger.data.gif.GifLibrary.arrivalsFlow().collect { sha ->
                if (pendingSwarmSha == sha) {
                    pendingSwarmSha = null
                    _uiState.update { it.copy(swarmStatus = "Гифка из роя получена - нажмите «Отправить»") }
                    attachLocalGif(sha)
                }
            }
        }
    }

    // ── Файлы группы (рой, этап 9) ────────────────────────────────────────────

    /** Передачи файлов группы и мои просьбы - карточке файла в ленте. */
    private fun observeTransfers() {
        // Просьбы с диска (этап 10): карточки сразу показывают «Запрошено…».
        groupFiles.warmUp()
        viewModelScope.launch {
            fileTransferDao.observeForChat(groupId)
                .flowOn(Dispatchers.IO)
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
     * Право прикреплять: ранг «Круг друзей» (как в личном чате) и право
     * «Отправка медиа» в этой группе (администраторам - всегда).
     */
    private fun refreshAttachRights(me: MemberSummary?, group: GroupSummary?) {
        val qualified = com.vladimir.messenger.data.referral.ReferralRankStore.qualifiedDirectCount(appContext)
        val byRank = com.vladimir.messenger.data.file.FileTransferRankPolicy.canSendAttachments(qualified)
        val role = me?.role ?: GroupRole.MEMBER
        val mask = group?.memberPermissions?.takeIf { it != 0L } ?: GroupPermissions.Member.DEFAULT
        val byGroup = GroupRole.isAdminOrOwner(role) ||
            (me?.isBanned != true && GroupPermissions.has(mask, GroupPermissions.Member.SEND_MEDIA))
        val hint = when {
            !byRank ->
                "Отправка файлов, фото и видео открывается с ранга «Круг друзей» — " +
                    "это 3 подтверждённых приглашения. Сейчас подтверждено: $qualified."
            !byGroup -> "В этой группе участникам запрещено отправлять файлы"
            else -> ""
        }
        _uiState.update { it.copy(canAttach = byRank && byGroup, attachLockedHint = hint) }
    }

    fun onAttachLocked() {
        _uiState.update { it.copy(error = it.attachLockedHint.ifBlank { "Вложения недоступны" }) }
    }

    /**
     * Файл выбран в системном окне: посчитать хэш, положить копию для
     * раздачи и показать карточку над полем ввода. Отправится вместе с
     * подписью по «Отправить».
     */
    fun onFileSelected(uri: android.net.Uri) {
        if (_uiState.value.isPreparingFile) return
        if (!_uiState.value.canAttach) {
            onAttachLocked()
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isPreparingFile = true, error = null) }
            try {
                val previous = _uiState.value.stagedFile
                val info = groupFiles.stage(groupId, uri)
                if (previous != null && previous.sha256 != info.sha256) groupFiles.unstage(groupId, previous.sha256)
                _uiState.update { it.copy(stagedFile = info) }
            } catch (e: Exception) {
                android.util.Log.w("GroupChatVM", "group file stage failed", e)
                _uiState.update { it.copy(error = "Файл не приложен: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isPreparingFile = false) }
            }
        }
    }

    // ── Каталог GIF (наш сервер -> Tenor; отправка через файловый рой) ──

    /** Открыть/обновить каталог: популярные или по запросу. */
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

    /** Закрыли каталог -.state гифок можно отпустить. */
    fun closeGifCatalog() {
        _uiState.update { it.copy(gifItems = emptyList(), gifNext = "", gifError = null) }
    }

    // ── Свой каталог роя (раунд 121) ────────────────────────────────────

    private var lastGifQuery: String = ""
    private var pendingSwarmSha: String? = null

    /** Открыли окно гифок: подтянуть мою библиотеку и каталог роя. */
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

    /** Своя гифка: в сцену - наружный ресурс не нужен. */
    fun attachLocalGif(sha256: String) {
        if (_uiState.value.isPreparingFile) return
        viewModelScope.launch {
            _uiState.update { it.copy(isPreparingFile = true, error = null) }
            try {
                val file = com.vladimir.messenger.data.gif.GifLibrary.gifFile(appContext, sha256)
                    ?: error("Гифки нет в библиотеке")
                val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    file.readBytes()
                }
                val previous = _uiState.value.stagedFile
                val info = groupFiles.stageGifBytes(groupId, bytes)
                if (previous != null && previous.sha256 != info.sha256) {
                    groupFiles.unstage(groupId, previous.sha256)
                }
                _uiState.update { it.copy(stagedFile = info) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Гифка не приложена: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isPreparingFile = false) }
            }
        }
    }

    /** Гифки нет нигде - попросить у хранителя роя. */
    fun requestSwarmGif(swarm: com.vladimir.messenger.data.gif.SwarmGif, onDone: () -> Unit) {
        if (pendingSwarmSha == swarm.entry.sha256) return
        viewModelScope.launch {
            val holder = runCatching {
                com.vladimir.messenger.data.gif.GifLibrary.requestGif(
                    appContext, chatRepository, swarm.entry.sha256, swarm.holders,
                )
            }.getOrNull()
            if (holder == null) {
                _uiState.update { it.copy(swarmStatus = "Держателей сейчас нет на связи") }
            } else if (holder.isEmpty()) {
                _uiState.update { it.copy(swarmStatus = "Уже попросили - гифка в пути") }
            } else {
                pendingSwarmSha = swarm.entry.sha256
                com.vladimir.messenger.data.gif.GifLibrary.rememberWant(swarm.entry.sha256)
                _uiState.update {
                    it.copy(swarmStatus = "Попросил у $holder - придёт в личный чат с ним")
                }
                onDone()
            }
        }
    }

    /** Выбрал гифку: скачать байты и приложить как файл (нажатие кнопки — как файл). */
    fun attachGif(item: com.vladimir.messenger.data.gif.GifItem, onDone: () -> Unit) {
        if (_uiState.value.isPreparingFile) return
        viewModelScope.launch {
            _uiState.update { it.copy(isPreparingFile = true, error = null) }
            try {
                // Раунд 121: своя библиотека прежде внешнего каталога.
                val localSha = com.vladimir.messenger.data.gif.GifLibrary
                    .shaForGiphyId(appContext, item.id)
                val bytes = localSha?.let { sha ->
                    com.vladimir.messenger.data.gif.GifLibrary.gifFile(appContext, sha)
                        ?.takeIf { it.isFile }
                        ?.let { f -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { f.readBytes() } }
                } ?: botApi.downloadGif(item.gif)
                    ?.also { downloaded ->
                        runCatching {
                            com.vladimir.messenger.data.gif.GifLibrary.add(
                                appContext, downloaded, item.id, lastGifQuery, "gif_" + item.id + ".gif",
                            )
                        }
                    }
                    ?: throw IllegalStateException("Гифка не скачалась")
                val previous = _uiState.value.stagedFile
                val info = groupFiles.stageGifBytes(groupId, bytes)
                if (previous != null && previous.sha256 != info.sha256) {
                    groupFiles.unstage(groupId, previous.sha256)
                }
                _uiState.update { it.copy(stagedFile = info) }
                onDone()
            } catch (e: Exception) {
                android.util.Log.w("GroupChatVM", "gif attach failed", e)
                _uiState.update { it.copy(error = "Гифка не приложена: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isPreparingFile = false) }
            }
        }
    }

    /** Убрать приложенный, но ещё не отправленный файл. */
    fun clearStagedFile() {
        val staged = _uiState.value.stagedFile ?: return
        _uiState.update { it.copy(stagedFile = null) }
        viewModelScope.launch { groupFiles.unstage(groupId, staged.sha256) }
    }

    /** Нажатие «Скачать» на карточке файла: попросить у автора или соседей. */
    fun requestFile(message: MessageEntity, info: com.vladimir.messenger.util.GroupFileMarker.Info) {
        viewModelScope.launch {
            runCatching { groupFiles.request(groupId, message.id, info, message.senderId, manual = true) }
                .onFailure { e -> _uiState.update { it.copy(error = "Не удалось запросить файл: ${e.message}") } }
        }
    }

    /** Моя авторская копия файла (превью картинки, «Поделиться»). */
    fun authorCopyFor(sha256: String): java.io.File? = groupFiles.authorCopy(groupId, sha256)

    /** Принятый файл: копия у меня (для «Открыть»/«Поделиться»). */
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

    private fun observeGroup() {
        viewModelScope.launch {
            groupRepository.observeGroup(groupId).collect { summary ->
                _uiState.update { it.copy(group = summary) }
                refreshAttachRights(_uiState.value.me, summary)
            }
        }
    }

    /**
     * Левая колонка значков: все свои группы и каналы с непрочитанными.
     * Тот же поток, которым пользуется список групп, — счётчики те же.
     */
    private fun observeAllGroups() {
        viewModelScope.launch {
            groupRepository.observeGroups().collect { list ->
                _uiState.update { it.copy(allGroups = list) }
            }
        }
    }

    /**
     * Участники нужны и для прав (закреп, управление темами), и для подписей
     * сообщений в ленте.
     */
    private fun observeMembers() {
        viewModelScope.launch {
            groupRepository.observeMembers(groupId).collect { members ->
                val me = members.firstOrNull { it.isMe }
                _uiState.update { state ->
                    state.copy(
                        members = members,
                        me = me,
                        canPin = GroupPermissions
                            .canPinMessages(me?.role ?: GroupRole.MEMBER, me?.permissions ?: 0L),
                        canManageTopics = GroupPermissions
                            .canManageTopics(me?.role ?: GroupRole.MEMBER, me?.permissions ?: 0L),
                    )
                }
                refreshAttachRights(me, _uiState.value.group)
            }
        }
    }

    private fun observeTopics() {
        viewModelScope.launch {
            groupRepository.observeTopics(groupId).collect { topics ->
                val previous = _uiState.value.selectedTopicId
                _uiState.update { state ->
                    val selected = state.selectedTopicId?.takeIf { id -> topics.any { it.id == id } }
                        ?: requestedTopicId?.takeIf { id -> topics.any { it.id == id } }
                        ?: topics.firstOrNull { it.isGeneral }?.id
                        ?: topics.firstOrNull()?.id
                    state.copy(topics = topics, selectedTopicId = selected)
                }
                val selected = _uiState.value.selectedTopicId
                // Ленту и закрепы перезапускаем ТОЛЬКО при смене темы.
                //
                // Раньше они перезапускались на каждом обновлении списка тем, и
                // вместе со сбросом непрочитанных это давало замкнутый круг:
                // запись в group_topics -> новый список тем -> перезапуск ленты
                // -> снова запись. Экран при этом намертво зависал.
                if (selected != null && selected != previous) {
                    observeMessages(selected)
                    observePinned(selected)
                }
            }
        }
    }

    private var messagesJob: kotlinx.coroutines.Job? = null

    /** Просьбы о комментариях к сборщику, пока ветка канала открыта (рой, этап 4). */
    private var commentsJob: kotlinx.coroutines.Job? = null

    /**
     * Ветка открыта: на большом канале комментарии ходят через владельца и
     * администраторов, и здесь могут быть не все. Просим последние и опись у
     * сборщика сразу и повторяем раз в минуту, пока экран открыт: повтор
     * добирает пропущенное и продлевает у сборщика «этот человек читает
     * ветку» - живые комментарии идут ему сразу. На маленьком канале, в
     * группе и у сборщика репозиторий ничего не шлёт.
     */
    private fun followComments(topicId: String) {
        commentsJob?.cancel()
        commentsJob = viewModelScope.launch {
            // Большая группа (рой, этап 5): сообщения, пропущенные в офлайне,
            // добираем у соседей; в канале и маленькой группе репозиторий
            // молчит. Один раз на открытие темы - в фоне повторять незачем:
            // живые сообщения приходят волной и эстафетой манифестов.
            runCatching { groupRepository.requestGroupMessages(groupId, topicId) }
            while (true) {
                runCatching { groupRepository.requestComments(groupId, topicId) }
                kotlinx.coroutines.delay(COMMENTS_REFRESH_MS)
            }
        }
    }

    /** «Показать ещё»: попросить у сборщика комментарии старше самых ранних, что есть. */
    fun loadOlderComments() {
        val topicId = _uiState.value.selectedTopicId ?: return
        viewModelScope.launch {
            runCatching { groupRepository.requestComments(groupId, topicId, older = true) }
        }
    }

    private fun observeMessages(topicId: String) {
        messagesJob?.cancel()
        followComments(topicId)
        messagesJob = viewModelScope.launch {
            groupRepository.observeTopicMessages(groupId, topicId).collect { all ->
                // Куски фотографий и длинного текста - служебные строки, а не
                // сообщения: в ленте их не показываем (галерея собирает фото
                // в ленте канала, а куски текста подклеиваются к своему
                // сообщению здесь же).
                val inline = com.vladimir.messenger.util.InlineImage
                val parts = all.filter { inline.isPart(it.content) }
                val list = all.filter { !inline.isPart(it.content) }.map { m ->
                    if (inline.textTail(m.content) == null) {
                        m
                    } else {
                        val own = parts.filter { it.senderId == m.senderId }.map { it.content }
                        m.copy(content = inline.expandContent(m.id, m.content, own))
                    }
                }
                _uiState.update { it.copy(messages = list, moreComments = moreComments(topicId, list.size)) }
                // Экран открыт - значит тема прочитана. Вызываем на каждом
                // обновлении, чтобы счётчик гас и на новых сообщениях.
                groupRepository.markRead(groupId, topicId)
            }
        }
    }

    /** Сколько комментариев у сборщика сверх [shown] текстовых сообщений темы (включая пост). */
    private fun moreComments(topicId: String, shown: Int): Int {
        val atHub = groupRepository.commentCounts.value[topicId] ?: return 0
        return (atHub - (shown - 1).coerceAtLeast(0)).coerceAtLeast(0)
    }

    /** Число комментариев у сборщика пришло или изменилось - пересчитать «ещё N». */
    private fun observeCommentCounts() {
        viewModelScope.launch {
            groupRepository.commentCounts.collect {
                val topicId = _uiState.value.selectedTopicId
                if (topicId != null) {
                    _uiState.update { state -> state.copy(moreComments = moreComments(topicId, state.messages.size)) }
                }
            }
        }
    }

    private var pinnedJob: kotlinx.coroutines.Job? = null

    /** Закрепы выбранной темы. У каждой темы — свой список закреплённых. */
    private fun observePinned(topicId: String) {
        pinnedJob?.cancel()
        pinnedJob = viewModelScope.launch {
            groupRepository.observePinned(groupId, topicId).collect { list ->
                _uiState.update { it.copy(pinned = list) }
            }
        }
    }

    fun selectTopic(topicId: String) {
        _uiState.update { it.copy(selectedTopicId = topicId, pinned = emptyList()) }
        observeMessages(topicId)
        observePinned(topicId)
    }

    fun createTopic(name: String, iconEmoji: String) {
        viewModelScope.launch {
            groupRepository.createTopic(groupId, name, iconEmoji)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun send(text: String) {
        val topicId = _uiState.value.selectedTopicId ?: return
        val staged = _uiState.value.stagedFile
        if (text.isBlank() && staged == null) return
        _uiState.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            // Файл (этап 9): в сообщении едет визитка и подпись «📎 имя
            // (размер)» - старые версии видят подпись, новые рисуют карточку;
            // сам файл участники просят у меня и друг у друга.
            val body = if (staged == null) text else com.vladimir.messenger.util.GroupFileMarker.compose(text, staged)
            groupRepository.sendMessage(groupId, topicId, body)
                .onFailure { e -> _uiState.update { it.copy(sending = false, error = e.message) } }
                .onSuccess { _uiState.update { it.copy(sending = false, stagedFile = null) } }
        }
    }

    fun togglePin(messageId: String, pinned: Boolean) {
        viewModelScope.launch {
            groupRepository.setPinned(groupId, messageId, pinned)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    /** Переслать сообщение темы себе в «Избранное». */
    fun saveToFavorites(text: String) {
        val source = _uiState.value.group?.title.orEmpty()
        val topicId = _uiState.value.selectedTopicId.orEmpty()
        viewModelScope.launch {
            // Вложенная картинка (служебная строка) уходит в фотографии
            // записи, а не в её текст: иначе в избранном тянулись бы «буквы».
            savedItems.saveText(
                com.vladimir.messenger.util.InlineImage.stripImage(text),
                if (source.isBlank()) "" else "Группа " + source,
                com.vladimir.messenger.data.repository.SavedOrigin(
                    kind = com.vladimir.messenger.data.repository.SavedOrigin.GROUP,
                    id = groupId,
                    topicId = topicId,
                ),
                photos = listOfNotNull(com.vladimir.messenger.util.InlineImage.extractB64(text)),
            )
            _uiState.update { it.copy(error = "Добавлено в избранное") }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    /** Реакции всей группы: поток уже уведён на IO внутри репозитория. */
    private fun observeReactions() {
        viewModelScope.launch {
            reactionRepository.observeChat(groupId).collect { map ->
                _uiState.update { it.copy(reactions = map) }
            }
        }
    }

    /** Поставить или снять реакцию на сообщение группы или канала. */
    fun toggleReaction(messageId: String, emoji: String) {
        viewModelScope.launch { reactionRepository.toggle(groupId, messageId, emoji) }
    }

    /** Убрать свою реакцию, какой бы она ни была. */
    fun removeReaction(messageId: String) {
        viewModelScope.launch { reactionRepository.removeMine(groupId, messageId) }
    }

    private companion object {
        /** Пока ветка открыта, просьба о комментариях повторяется с таким шагом. */
        const val COMMENTS_REFRESH_MS = 60_000L
    }
}
