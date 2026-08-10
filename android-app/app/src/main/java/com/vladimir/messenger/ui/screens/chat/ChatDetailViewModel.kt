package com.vladimir.messenger.ui.screens.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.domain.model.Message
import com.vladimir.messenger.domain.usecase.GetMessagesUseCase
import com.vladimir.messenger.domain.usecase.SendMessageUseCase
import com.vladimir.messenger.domain.usecase.MarkAsReadUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatDetailUiState(
    val messages: List<Message> = emptyList(),
    val inputText: String       = "",
    val isLoading: Boolean      = true,
    val isSending: Boolean      = false,
    val error: String?          = null,
    val isContactOnline: Boolean = false,
    val scrollToBottom: Boolean = false,
)

@HiltViewModel
class ChatDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getMessagesUseCase: GetMessagesUseCase,
    private val sendMessageUseCase: SendMessageUseCase,
    private val markAsReadUseCase: MarkAsReadUseCase,
) : ViewModel() {

    // chatId передаётся через навигацию (SavedStateHandle)
    private val chatId: String = checkNotNull(savedStateHandle["chatId"])

    private val _uiState = MutableStateFlow(ChatDetailUiState())
    val uiState: StateFlow<ChatDetailUiState> = _uiState.asStateFlow()

    init {
        loadMessages()
        markAsRead()
    }

    private fun loadMessages() {
        android.util.Log.i("ChatDetailVM", "🔍 loadMessages for chatId=$chatId")
        viewModelScope.launch {
            getMessagesUseCase(chatId)
                .collect { messages ->
                    android.util.Log.i("ChatDetailVM", "📥 Received ${messages.size} messages for chatId=$chatId")
                    // Показать последние 5 сообщений
                    messages.takeLast(5).forEach { msg ->
                        android.util.Log.i("ChatDetailVM", "  🔹 msg: id=${msg.id.take(8)} isFromMe=${msg.isFromMe} status=${msg.status} content=${msg.content.take(20)}")
                    }
                    val wasEmpty = _uiState.value.messages.isEmpty()
                    _uiState.update { state ->
                        state.copy(
                            messages      = messages,
                            isLoading     = false,
                            // Автопрокрутка при первой загрузке или новом сообщении
                            scrollToBottom = wasEmpty || messages.lastOrNull()?.isFromMe == true
                        )
                    }
                }
        }
    }

    private fun markAsRead() {
        viewModelScope.launch {
            markAsReadUseCase(chatId)
        }
    }

    fun onInputTextChanged(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun onSendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true, inputText = "") }

            sendMessageUseCase(chatId, text)
                .onSuccess {
                    _uiState.update { it.copy(
                        isSending      = false,
                        scrollToBottom = true
                    )}
                }
                .onFailure { e ->
                    _uiState.update { it.copy(
                        isSending = false,
                        inputText = text,  // Восстанавливаем текст при ошибке
                        error     = "Ошибка отправки: ${e.message}"
                    )}
                }
        }
    }

    fun onScrolledToBottom() {
        _uiState.update { it.copy(scrollToBottom = false) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}