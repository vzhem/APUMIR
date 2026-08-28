package com.vladimir.messenger.ui.screens.groups

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
import javax.inject.Inject

data class GroupAdminUiState(
    val groupId: String = "",
    val group: GroupSummary? = null,
    val members: List<MemberSummary> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<MemberSummary> = emptyList(),
    val requests: List<JoinRequestSummary> = emptyList(),
    val invites: List<InviteSummary> = emptyList(),
    val stats: GroupStats? = null,
    /** Темы нужны, чтобы в статистике показывать имена тем, а не их идентификаторы. */
    val topics: List<TopicSummary> = emptyList(),
    val memberPermissions: Long = GroupPermissions.Member.DEFAULT,
    val isAdmin: Boolean = false,
    val isOwner: Boolean = false,
    /** Менять название и описание: владелец, админ с CHANGE_INFO или участник с таким правом группы. */
    val canChangeInfo: Boolean = false,
    /** Создавать, отзывать и удалять ссылки-приглашения: только владелец и админы с правом приглашать. */
    val canManageInvites: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

@HiltViewModel
class GroupAdminViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val groupRepository: GroupRepository,
) : ViewModel() {

    private val groupId: String = savedStateHandle.get<String>("groupId").orEmpty()

    private val _uiState = MutableStateFlow(GroupAdminUiState(groupId = groupId))
    val uiState: StateFlow<GroupAdminUiState> = _uiState.asStateFlow()

    init {
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
                _uiState.update { state ->
                    state.copy(
                        group = summary,
                        isAdmin = GroupRole.isAdminOrOwner(role),
                        isOwner = role == GroupRole.OWNER,
                        canChangeInfo = GroupPermissions.canChangeInfo(role, myMask, memberMask),
                        canManageInvites = GroupPermissions.canManageInvites(role, myMask),
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
        viewModelScope.launch {
            groupRepository.stats(groupId)
                .onSuccess { s -> _uiState.update { it.copy(stats = s) } }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
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
                .onSuccess { _uiState.update { it.copy(notice = if (approve) "Участник добавлен" else "Заявка отклонена") } }
        }
    }

    fun createInvite(requestApproval: Boolean) {
        viewModelScope.launch {
            groupRepository.createInvite(groupId, requestApproval = requestApproval)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
                .onSuccess { _uiState.update { it.copy(notice = "Ссылка создана") } }
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
                    _uiState.update { it.copy(notice = "Отправлено участникам: $count") }
                }
        }
    }

    fun revokeInvite(slug: String) {
        viewModelScope.launch {
            groupRepository.revokeInvite(groupId, slug)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
                .onSuccess { _uiState.update { it.copy(notice = "Ссылка отозвана") } }
        }
    }

    /** Убрать отозванную ссылку из списка совсем. */
    fun deleteInvite(slug: String) {
        viewModelScope.launch {
            groupRepository.deleteInvite(groupId, slug)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
                .onSuccess { _uiState.update { it.copy(notice = "Ссылка удалена") } }
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
                .onSuccess { _uiState.update { it.copy(notice = if (isPublic) "Группа публичная" else "Группа частная") } }
        }
    }

    fun updateProfile(title: String, about: String) {
        viewModelScope.launch {
            groupRepository.updateProfile(groupId, title, about)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
                .onSuccess { _uiState.update { it.copy(notice = "Сохранено") } }
        }
    }

    fun blockMember(nodeId: String) {
        viewModelScope.launch {
            groupRepository.setMemberBlocked(groupId, nodeId, true)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
                .onSuccess { _uiState.update { it.copy(notice = "Участник исключён") } }
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
