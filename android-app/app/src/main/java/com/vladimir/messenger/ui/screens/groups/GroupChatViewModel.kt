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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GroupChatUiState(
    val groupId: String = "",
    val group: GroupSummary? = null,
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
)

@HiltViewModel
class GroupChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val groupRepository: GroupRepository,
) : ViewModel() {

    private val groupId: String = savedStateHandle.get<String>("groupId").orEmpty()

    private val _uiState = MutableStateFlow(GroupChatUiState(groupId = groupId))
    val uiState: StateFlow<GroupChatUiState> = _uiState.asStateFlow()

    init {
        observeGroup()
        observeMembers()
        observeTopics()
        // Закрепы подписываем на выбранную тему, а не на всю группу:
        // observePinned(topicId) стартует вместе с лентой сообщений.
    }

    private fun observeGroup() {
        viewModelScope.launch {
            groupRepository.observeGroup(groupId).collect { summary ->
                _uiState.update { it.copy(group = summary) }
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
            }
        }
    }

    private fun observeTopics() {
        viewModelScope.launch {
            groupRepository.observeTopics(groupId).collect { topics ->
                _uiState.update { state ->
                    val selected = state.selectedTopicId?.takeIf { id -> topics.any { it.id == id } }
                        ?: topics.firstOrNull { it.isGeneral }?.id
                        ?: topics.firstOrNull()?.id
                    state.copy(topics = topics, selectedTopicId = selected)
                }
                _uiState.value.selectedTopicId?.let {
                    observeMessages(it)
                    observePinned(it)
                }
            }
        }
    }

    private var messagesJob: kotlinx.coroutines.Job? = null

    private fun observeMessages(topicId: String) {
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            groupRepository.observeTopicMessages(groupId, topicId).collect { list ->
                _uiState.update { it.copy(messages = list) }
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

    fun createTopic(name: String) {
        viewModelScope.launch {
            groupRepository.createTopic(groupId, name)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun send(text: String) {
        val topicId = _uiState.value.selectedTopicId ?: return
        if (text.isBlank()) return
        _uiState.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            groupRepository.sendMessage(groupId, topicId, text)
                .onFailure { e -> _uiState.update { it.copy(sending = false, error = e.message) } }
                .onSuccess { _uiState.update { it.copy(sending = false) } }
        }
    }

    fun togglePin(messageId: String, pinned: Boolean) {
        viewModelScope.launch {
            groupRepository.setPinned(groupId, messageId, pinned)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }
}
