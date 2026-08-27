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
    ) {
        _uiState.update { it.copy(creating = true, createError = null) }
        viewModelScope.launch {
            val result = groupRepository.createGroup(title, about, isPublic, topicsEnabled)
            result.onSuccess { group ->
                _uiState.update { it.copy(creating = false, createError = null) }
                onCreated(group.id)
            }.onFailure { e ->
                _uiState.update { it.copy(creating = false, createError = e.message ?: "Не удалось создать группу") }
            }
        }
    }

    fun dismissCreateError() {
        _uiState.update { it.copy(createError = null) }
    }

    /**
     * Вход в группу по короткому коду из ссылки-приглашения или QR.
     * Публичная группа — сразу участник; частная — уйдёт заявка.
     */
    fun joinBySlug(slug: String) {
        if (slug.isBlank() || _uiState.value.joining) return
        viewModelScope.launch {
            _uiState.update { it.copy(joining = true, joinMessage = null, joinedGroupId = null) }
            val outcome = groupRepository.joinBySlug(slug)
            when (outcome) {
                is JoinOutcome.Joined -> _uiState.update {
                    it.copy(
                        joining = false,
                        joinedGroupId = outcome.groupId,
                        joinMessage = "Вы вошли в группу «" + outcome.title + "»",
                    )
                }
                is JoinOutcome.RequestSent -> _uiState.update {
                    it.copy(
                        joining = false,
                        joinMessage = "Заявка в «" + outcome.title + "» отправлена, ждёт решения администратора",
                    )
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
