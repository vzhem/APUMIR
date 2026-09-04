package com.vladimir.messenger.ui.screens.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.data.group.GroupRepository
import com.vladimir.messenger.data.group.JoinOutcome
import com.vladimir.messenger.data.group.GroupSummary
import com.vladimir.messenger.data.local.entity.DirectoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
    /** Найдено в сети: чужие публичные группы и каналы из роевого каталога. */
    val directoryMatches: List<DirectoryEntity> = emptyList(),
)

@HiltViewModel
class GroupsViewModel @Inject constructor(
    private val groupRepository: GroupRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GroupsUiState())
    val uiState: StateFlow<GroupsUiState> = _uiState.asStateFlow()

    /** Последнее состояние сетевого каталога - для матчинга по запросу. */
    private var latestDirectory: List<DirectoryEntity> = emptyList()

    init {
        // Всё, что читает диск, - только в фоне: `viewModelScope.launch` без
        // диспетчера исполняется на ГЛАВНОМ потоке и подвешивает экран.
        viewModelScope.launch {
            val can = withContext(Dispatchers.IO) { groupRepository.canCreateGroupsNow() }
            _uiState.update { it.copy(canCreate = can) }
        }
        // Возвращаем владельцу его строку участника, если её стёрла авария с
        // перезаписью группы: иначе группа есть в списке, но ничего не даёт.
        viewModelScope.launch(Dispatchers.IO) { groupRepository.repairOwnerMemberships() }
        // Каталог рассылается сам по расписанию (не чаще раза в 6 часов) и
        // сразу при создании публичной группы. Открытие раздела больше НЕ
        // тянет сеть: раньше каждый заход слал рассылку всем контактам.
        observe()
    }

    private fun observe() {
        viewModelScope.launch {
            groupRepository.observeGroups().collect { groups ->
                _uiState.update { state ->
                    state.copy(
                        groups = groups,
                        filtered = filter(groups, state.searchQuery),
                        directoryMatches = matchDirectory(state.searchQuery, groups),
                        isLoading = false,
                    )
                }
            }
        }
        viewModelScope.launch {
            groupRepository.observeDirectory().collect { dir ->
                latestDirectory = dir
                _uiState.update { state ->
                    state.copy(directoryMatches = matchDirectory(state.searchQuery, state.groups))
                }
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { state ->
            state.copy(
                searchQuery = query,
                filtered = filter(state.groups, query),
                directoryMatches = matchDirectory(query, state.groups),
            )
        }
    }

    /**
     * Ищем в каталоге то, чего ещё нет в своих группах.
     *
     * При ПУСТОМ запросе показываем подсказки: самое свежее из того, что сеть
     * успела рассказать. Раньше пустой поиск не показывал ничего, и человек не
     * догадывался, что в APU вообще есть чужие публичные группы и каналы.
     */
    private fun matchDirectory(query: String, groups: List<GroupSummary>): List<DirectoryEntity> {
        val q = query.trim().lowercase()
        val mine = groups.map { it.id }.toSet()
        val available = latestDirectory.filter { it.groupId !in mine }
        if (q.isEmpty()) {
            return available
                .sortedByDescending { it.updatedAtMs }
                .take(SUGGESTION_LIMIT)
        }
        return available.filter { it.title.lowercase().contains(q) }
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
            val result = withContext(Dispatchers.IO) { groupRepository.createGroup(
                title = title,
                about = about,
                isPublic = isPublic,
                topicsEnabled = topicsEnabled,
                isChannel = isChannel,
            ) }
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
            val outcome = withContext(Dispatchers.IO) { groupRepository.joinByLink(raw) }
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

    companion object {
        /** Сколько подсказок показываем при пустом поиске. */
        private const val SUGGESTION_LIMIT = 12
    }
}
