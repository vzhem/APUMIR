package com.vladimir.messenger.ui.screens.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.data.group.GroupRepository
import com.vladimir.messenger.data.group.JoinOutcome
import com.vladimir.messenger.data.group.GroupSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GroupsUiState(
    val groups: List<GroupSummary> = emptyList(),
    val filtered: List<GroupSummary> = emptyList(),
    val isLoading: Boolean = true,
    val searchQuery: String = "",
    val canCreate: Boolean = false,
    /** Текст ошибки создания группы (например, низкий ранг). */
    val createError: String? = null,
    val creating: Boolean = false,
    /** Присоединение по ссылке/QR: идём прямо сейчас. */
    val joining: Boolean = false,
    /** Что сказать владельцу про попытку войти по ссылке. */
    val joinMessage: String? = null,
    /** Группа, в которую удалось войти: экран откроет её чат. */
    val joinedGroupId: String? = null,
)

@HiltViewModel
class GroupsViewModel @Inject constructor(
    private val groupRepository: GroupRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GroupsUiState())
    val uiState: StateFlow<GroupsUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(canCreate = groupRepository.canCreateGroupsNow()) }
        // Возвращаем владельцу его строку участника, если её стёрла авария с
        // перезаписью группы: иначе группа есть в списке, но ничего не даёт.
        viewModelScope.launch { groupRepository.repairOwnerMemberships() }
        observe()
    }

    private fun observe() {
        viewModelScope.launch {
            groupRepository.observeGroups().collect { groups ->
                _uiState.update { state ->
                    state.copy(
                        groups = groups,
                        filtered = filter(groups, state.searchQuery),
                        isLoading = false,
                    )
                }
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { state ->
            state.copy(searchQuery = query, filtered = filter(state.groups, query))
        }
    }

    fun createGroup(
        title: String,
        about: String,
        isPublic: Boolean,
        topicsEnabled: Boolean,
        onCreated: (String) -> Unit,
        isChannel: Boolean = false,
    ) {
        _uiState.update { it.copy(creating = true, createError = null) }
        viewModelScope.launch {
            val result = groupRepository.createGroup(
                title = title,
                about = about,
                isPublic = isPublic,
                topicsEnabled = topicsEnabled,
                isChannel = isChannel,
            )
            result.onSuccess { group ->
                _uiState.update { it.copy(creating = false, createError = null) }
                onCreated(group.id)
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        creating = false,
                        createError = e.message
                            ?: if (isChannel) "Не удалось создать канал" else "Не удалось создать группу",
                    )
                }
            }
        }
    }

    fun dismissCreateError() {
        _uiState.update { it.copy(createError = null) }
    }

    /**
     * Вход в группу по ссылке или QR — с любого телефона.
     * Ссылка без одобрения — сразу участник; иначе владельцу уходит заявка.
     */
    fun joinByLink(raw: String) {
        if (raw.isBlank() || _uiState.value.joining) return
        viewModelScope.launch {
            _uiState.update { it.copy(joining = true, joinMessage = null, joinedGroupId = null) }
            val outcome = groupRepository.joinByLink(raw)
            when (outcome) {
                is JoinOutcome.Joined -> _uiState.update {
                    val what = if (outcome.isChannel) "канал" else "группу"
                    it.copy(
                        joining = false,
                        joinedGroupId = outcome.groupId,
                        joinMessage = "Вы вошли в $what «" + outcome.title + "»",
                    )
                }
                is JoinOutcome.RequestSent -> _uiState.update {
                    val what = if (outcome.isChannel) "канал" else "группа"
                    val where = if (outcome.title.isBlank()) what else "«" + outcome.title + "»"
                    // Ссылка без одобрения: владелец принимает сразу, и заявки
                    // не будет. Обещать заявку в таком случае нельзя - ровно
                    // это и выглядело как «заявки в канале не появляются».
                    val message = if (outcome.needsApproval) {
                        "Заявка в $where отправлена владельцу. " +
                            "Как только он её одобрит, $what появится в списке."
                    } else {
                        "Входим в $where: одобрение не требуется. " +
                            "Как только владелец будет на связи, $what появится в списке."
                    }
                    it.copy(joining = false, joinMessage = message)
                }
                is JoinOutcome.Failed -> _uiState.update {
                    it.copy(joining = false, joinMessage = "Не удалось войти: " + outcome.reason)
                }
            }
        }
    }

    /** Экран забрал результат входа — убираем, чтобы не показать его дважды. */
    fun consumeJoinResult() {
        _uiState.update { it.copy(joinMessage = null, joinedGroupId = null) }
    }

    private fun filter(groups: List<GroupSummary>, query: String): List<GroupSummary> {
        if (query.isBlank()) return groups
        return groups.filter { it.title.contains(query, ignoreCase = true) }
    }
}
