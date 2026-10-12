package com.vladimir.messenger.ui.screens.groups

import com.vladimir.messenger.R
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.data.group.GroupPermissions
import com.vladimir.messenger.data.group.GroupRepository
import com.vladimir.messenger.data.group.GroupRole
import com.vladimir.messenger.data.group.GroupStats
import com.vladimir.messenger.data.group.GroupSummary
import com.vladimir.messenger.data.group.InviteSummary
import com.vladimir.messenger.data.group.JoinRequestSummary
import com.vladimir.messenger.data.group.MemberSummary
import com.vladimir.messenger.data.group.TopicSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import javax.inject.Inject

data class GroupAdminUiState(
    val groupId: String = "",
    val group: GroupSummary? = null,
    val members: List<MemberSummary> = emptyList(),
    /**
     * Узлы-элита: у участника в списке — золотое кольцо вокруг аватарки и знак
     * VIP. Ранг участник сообщил сам конвертом APURANK1 (решение владельца
     * 2026-10-07: «VIP должно быть видно везде, и в группах, и в каналах»).
     */
    val vipNodeIds: Set<String> = emptySet(),
    val searchQuery: String = "",
    val searchResults: List<MemberSummary> = emptyList(),
    val requests: List<JoinRequestSummary> = emptyList(),
    val invites: List<InviteSummary> = emptyList(),
    val stats: GroupStats? = null,
    val isStatsRefreshing: Boolean = false,
    /** Темы нужны, чтобы в статистике показывать имена тем, а не их идентификаторы. */
    val topics: List<TopicSummary> = emptyList(),
    val memberPermissions: Long = GroupPermissions.Member.DEFAULT,
    val isAdmin: Boolean = false,
    val isOwner: Boolean = false,
    /** Менять название и описание: владелец, админ с CHANGE_INFO или участник с таким правом группы. */
    val canChangeInfo: Boolean = false,
    /** Создавать, отзывать и удалять ссылки-приглашения: только владелец и админы с правом приглашать. */
    val canManageInvites: Boolean = false,
    /** Картина владения: молчит ли владелец и могу ли я забрать права (наследование). */
    val ownership: GroupRepository.OwnershipState? = null,
    val error: String? = null,
    val notice: String? = null,
)

@HiltViewModel
class GroupAdminViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val groupRepository: GroupRepository,
    /** Ранги собеседников: поток узлов-элиты для знака и кольца. */
    private val peerRankStore: com.vladimir.messenger.data.rank.PeerRankStore,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {

    private val groupId: String = savedStateHandle.get<String>("groupId").orEmpty()

    /**
     * Знак VIP и золотое кольцо у участников в списке: подписка на поток
     * узлов-элиты. Ранги приходят конвертами APURANK1 от самих участников.
     */
    private fun observePeerVip() {
        viewModelScope.launch {
            peerRankStore.vipNodeIds.collect { ids ->
                _uiState.update { it.copy(vipNodeIds = ids) }
            }
        }
    }

    private val _uiState = MutableStateFlow(GroupAdminUiState(groupId = groupId))
    val uiState: StateFlow<GroupAdminUiState> = _uiState.asStateFlow()

    private var statsJob: Job? = null

    init {
        observePeerVip()
        observeGroup()
        observeMembers()
        observeRequests()
        observeInvites()
        observeTopics()
    }

    private fun observeGroup() {
        viewModelScope.launch {
            groupRepository.observeGroup(groupId).collect { summary ->
                val me = groupRepository.searchMembers(groupId, "").firstOrNull { it.isMe }
                val role = me?.role ?: GroupRole.MEMBER
                val myMask = me?.permissions ?: 0L
                val memberMask = summary?.memberPermissions ?: GroupPermissions.Member.DEFAULT
                // Владение смотрим вместе с составом: молчание владельца и
                // право на наследование зависят от моей роли в группе.
                val ownership = runCatching { groupRepository.ownershipState(groupId) }.getOrNull()
                _uiState.update { state ->
                    state.copy(
                        group = summary,
                        isAdmin = GroupRole.isAdminOrOwner(role),
                        isOwner = role == GroupRole.OWNER,
                        canChangeInfo = GroupPermissions.canChangeInfo(role, myMask, memberMask),
                        canManageInvites = GroupPermissions.canManageInvites(role, myMask),
                        ownership = ownership,
                        // Берём сохранённую политику группы: раньше здесь всегда
                        // подставлялся DEFAULT, и вкладка «Разрешения» показывала
                        // не то, что реально включено.
                        memberPermissions = summary?.memberPermissions
                            ?: GroupPermissions.Member.DEFAULT,
                    )
                }
                refreshStats()
            }
        }
    }

    private fun observeMembers() {
        viewModelScope.launch {
            groupRepository.observeMembers(groupId).collect { members ->
                _uiState.update { state ->
                    state.copy(
                        members = members,
                        searchResults = applySearch(members, state.searchQuery),
                    )
                }
            }
        }
    }

    private fun observeRequests() {
        viewModelScope.launch {
            groupRepository.observeJoinRequests(groupId).collect { list ->
                _uiState.update { it.copy(requests = list) }
            }
        }
    }

    private fun observeInvites() {
        viewModelScope.launch {
            groupRepository.observeInvites(groupId).collect { list ->
                _uiState.update { it.copy(invites = list) }
            }
        }
    }

    private fun observeTopics() {
        viewModelScope.launch {
            groupRepository.observeTopics(groupId).collect { list ->
                _uiState.update { it.copy(topics = list) }
            }
        }
    }

    fun refreshStats() {
        // Не выдаём обычному участнику ошибку лишь из-за открытия настроек.
        if (!_uiState.value.isAdmin) {
            _uiState.update { it.copy(stats = null) }
            return
        }
        if (statsJob?.isActive == true) return
        statsJob = viewModelScope.launch {
            _uiState.update { it.copy(isStatsRefreshing = true) }
            try {
                val snapshot = groupRepository.stats(groupId).getOrThrow()
                _uiState.update { state -> state.copy(stats = snapshot.takeIf { state.isAdmin }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { state ->
                    if (state.isAdmin) state.copy(error = e.message ?: appContext.getString(R.string.ga_stats_failed))
                    else state.copy(stats = null)
                }
            } finally {
                _uiState.update { it.copy(isStatsRefreshing = false) }
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        viewModelScope.launch {
            val results = groupRepository.searchMembers(groupId, query)
            _uiState.update { it.copy(searchQuery = query, searchResults = results) }
        }
    }

    fun decideRequest(nodeId: String, approve: Boolean) {
        viewModelScope.launch {
            groupRepository.decideJoinRequest(groupId, nodeId, approve)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
                .onSuccess { _uiState.update { it.copy(notice = if (approve) appContext.getString(R.string.ga_member_added) else appContext.getString(R.string.ga_request_declined)) } }
        }
    }

    fun createInvite(requestApproval: Boolean) {
        viewModelScope.launch {
            groupRepository.createInvite(groupId, requestApproval = requestApproval)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
                .onSuccess { _uiState.update { it.copy(notice = appContext.getString(R.string.ga_link_created)) } }
        }
    }

    /**
     * Разослать темы и состав заново. Лечит участников, которые вступили раньше,
     * чем группа научилась присылать темы, и видят пустой чат.
     */
    fun resyncMembers() {
        viewModelScope.launch {
            groupRepository.resyncMembers(groupId)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
                .onSuccess { count ->
                    _uiState.update { it.copy(notice = appContext.getString(R.string.ga_sent_to_members, count)) }
                }
        }
    }

    fun revokeInvite(slug: String) {
        viewModelScope.launch {
            groupRepository.revokeInvite(groupId, slug)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
                .onSuccess { _uiState.update { it.copy(notice = appContext.getString(R.string.ga_link_revoked)) } }
        }
    }

    /** Убрать отозванную ссылку из списка совсем. */
    fun deleteInvite(slug: String) {
        viewModelScope.launch {
            groupRepository.deleteInvite(groupId, slug)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
                .onSuccess { _uiState.update { it.copy(notice = appContext.getString(R.string.ga_link_deleted)) } }
        }
    }

    fun toggleAdmin(nodeId: String, admin: Boolean) {
        viewModelScope.launch {
            groupRepository.setAdminRole(groupId, nodeId, admin)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun setAdminPermission(nodeId: String, flag: Long, enabled: Boolean) {
        val current = _uiState.value.members.firstOrNull { it.nodeId == nodeId }?.permissions ?: 0L
        viewModelScope.launch {
            groupRepository.setAdminPermissions(groupId, nodeId, GroupPermissions.withFlag(current, flag, enabled))
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun setMemberPermission(flag: Long, enabled: Boolean) {
        val next = GroupPermissions.withFlag(_uiState.value.memberPermissions, flag, enabled)
        viewModelScope.launch {
            groupRepository.setMemberPermissions(groupId, next)
                .onSuccess { _uiState.update { it.copy(memberPermissions = next) } }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun setPublic(isPublic: Boolean) {
        viewModelScope.launch {
            groupRepository.setPublic(groupId, isPublic)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
                .onSuccess { _uiState.update { it.copy(notice = if (isPublic) appContext.getString(R.string.ga_public) else appContext.getString(R.string.ga_private)) } }
        }
    }

    /** Раунд 153: перевести группу «без тем» в группу с темами. */
    fun enableTopics() {
        viewModelScope.launch {
            groupRepository.enableTopics(groupId)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
                .onSuccess { _uiState.update { it.copy(notice = appContext.getString(R.string.ga_topics_on)) } }
        }
    }

    /**
     * р250: медленный режим - [seconds] секунд между сообщениями участника
     * (0 - выключен). Право то же, что у названия и описания группы: режим
     * меняет владелец, администратор с правом «Изменять информацию» или
     * участник, которому это право дано (см. GroupPermissions.canChangeInfo).
     */
    fun setSlowMode(seconds: Int) {
        viewModelScope.launch {
            groupRepository.setSlowMode(groupId, seconds)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            notice = if (seconds <= 0) {
                                appContext.getString(R.string.ga_slow_off)
                            } else {
                                appContext.getString(R.string.ga_slow_mode_notice, secondsWords(seconds))
                            },
                        )
                    }
                }
        }
    }

    private fun secondsWords(seconds: Int): String = when (seconds) {
        10 -> appContext.getString(R.string.ga_slow_10s)
        30 -> appContext.getString(R.string.ga_slow_30s)
        60 -> appContext.getString(R.string.ga_slow_1m)
        300 -> appContext.getString(R.string.ga_slow_5m)
        900 -> appContext.getString(R.string.ga_slow_15m)
        else -> appContext.getString(R.string.ga_seconds_short, seconds)
    }

    fun updateProfile(title: String, about: String) {
        viewModelScope.launch {
            groupRepository.updateProfile(groupId, title, about)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
                .onSuccess { _uiState.update { it.copy(notice = appContext.getString(R.string.ga_saved)) } }
        }
    }

    /**
     * Аватар группы/канала (раунд 42): сжимаем выбранную картинку, сохраняем в
     * роевой реестр и рассылаем контактам - участники увидят её в списке и в
     * шапке группы.
     */
    fun setGroupAvatar(uri: android.net.Uri) {
        viewModelScope.launch {
            val b64 = com.vladimir.messenger.util.AvatarCompress
                .compressUri(appContext, uri.toString())
            if (b64 == null) {
                _uiState.update { it.copy(error = appContext.getString(R.string.ga_image_read)) }
                return@launch
            }
            groupRepository.saveGroupAvatar(groupId, b64)
            groupRepository.publishGroupAvatar(groupId, b64)
            _uiState.update { it.copy(notice = appContext.getString(R.string.ga_avatar_updated)) }
        }
    }

    fun blockMember(nodeId: String) {
        viewModelScope.launch {
            groupRepository.setMemberBlocked(groupId, nodeId, true)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
                .onSuccess { _uiState.update { it.copy(notice = appContext.getString(R.string.ga_member_removed)) } }
        }
    }

    /**
     * Добровольная передача владения администратору. UI обязан спросить
     * подтверждение: обратной передачи «в один тап» нет - бывший владелец
     * становится администратором.
     */
    fun transferOwnership(nodeId: String) {
        viewModelScope.launch {
            groupRepository.transferOwnership(groupId, nodeId)
                .onSuccess { _uiState.update { it.copy(notice = appContext.getString(R.string.ga_ownership_moved)) } }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    /** Наследование: забрать владение у владельца, который долго молчал. */
    fun claimOwnership() {
        viewModelScope.launch {
            groupRepository.claimOwnership(groupId)
                .onSuccess { _uiState.update { it.copy(notice = appContext.getString(R.string.ga_now_owner)) } }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun leaveGroup(onLeft: () -> Unit) {
        viewModelScope.launch {
            groupRepository.leaveGroup(groupId)
                .onSuccess { onLeft() }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    /**
     * Удалить группу целиком. UI обязан спросить подтверждение дважды
     * (второй раз — ввести название группы), поэтому вызов сюда доходит
     * только при осознанном решении.
     */
    fun deleteGroup(onDeleted: () -> Unit) {
        viewModelScope.launch {
            groupRepository.deleteGroup(groupId)
                .onSuccess { onDeleted() }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun dismissMessages() {
        _uiState.update { it.copy(error = null, notice = null) }
    }

    private fun applySearch(members: List<MemberSummary>, query: String): List<MemberSummary> {
        if (query.isBlank()) return members
        return members.filter {
            it.displayName.contains(query, ignoreCase = true) || it.nodeId.contains(query, ignoreCase = true)
        }
    }
}
