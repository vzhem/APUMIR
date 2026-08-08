package com.vladimir.messenger.ui.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.domain.model.Chat
import com.vladimir.messenger.domain.usecase.GetChatsUseCase
import com.vladimir.messenger.domain.usecase.ObserveNetworkStatusUseCase
import com.vladimir.messenger.data.repository.NetworkStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatListUiState(
    val chats: List<Chat>        = emptyList(),
    val isLoading: Boolean       = true,
    val error: String?           = null,
    val networkStatus: NetworkStatus = NetworkStatus.Disconnected,
    val searchQuery: String      = "",
    val filteredChats: List<Chat> = emptyList(),
)

@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val getChatsUseCase: GetChatsUseCase,
    private val observeNetworkStatusUseCase: ObserveNetworkStatusUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatListUiState())
    val uiState: StateFlow<ChatListUiState> = _uiState.asStateFlow()

    init {
        loadChats()
        observeNetworkStatus()
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
                        )
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
            )
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