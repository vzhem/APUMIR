package com.vladimir.messenger.ui.screens.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.data.file.FileTransferRouter
import com.vladimir.messenger.data.file.OutgoingFilePreparationService
import com.vladimir.messenger.data.local.dao.FileTransferDao
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import com.vladimir.messenger.data.referral.ReferralRankStore
import com.vladimir.messenger.data.repository.ChatRepository
import com.vladimir.messenger.domain.model.Message
import com.vladimir.messenger.domain.usecase.GetMessagesUseCase
import com.vladimir.messenger.domain.usecase.SendMessageUseCase
import com.vladimir.messenger.domain.usecase.MarkAsReadUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ChatDetailUiState(
    val messages: List<Message> = emptyList(),
    val transfers: List<FileTransferEntity> = emptyList(),
    val inputText: String       = "",
    val isLoading: Boolean      = true,
    val isSending: Boolean      = false,
    val isPreparingFile: Boolean = false,
    val error: String?          = null,
    val isContactOnline: Boolean = false,
    val scrollToBottom: Boolean = false,
    val pendingSave: FileTransferEntity? = null,
)

@HiltViewModel
class ChatDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getMessagesUseCase: GetMessagesUseCase,
    private val sendMessageUseCase: SendMessageUseCase,
    private val markAsReadUseCase: MarkAsReadUseCase,
    private val chatRepository: ChatRepository,
    private val filePreparation: OutgoingFilePreparationService,
    private val fileTransferDao: FileTransferDao,
    private val fileTransferRouter: FileTransferRouter,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    // chatId передаётся через навигацию (SavedStateHandle)
    private val chatId: String = checkNotNull(savedStateHandle["chatId"])

    private val _uiState = MutableStateFlow(ChatDetailUiState())
    val uiState: StateFlow<ChatDetailUiState> = _uiState.asStateFlow()

    init {
        loadMessages()
        observeTransfers()
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

    private fun observeTransfers() {
        viewModelScope.launch {
            fileTransferDao.observeForChat(chatId).collect { transfers ->
                _uiState.update { state ->
                    state.copy(
                        transfers = transfers,
                        scrollToBottom = state.scrollToBottom ||
                            transfers.any { it.state == "OFFERED" || it.state == "PREPARING" },
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

    /**
     * F3: pick → rank-checked encrypted preparation (manifest, key envelope, durable chunks) →
     * local chat placeholder → immediate pump. Offline multi-day delivery is owned by the
     * durable transport, not by this UI path.
     */
    fun onFileSelected(uri: Uri) {
        if (_uiState.value.isPreparingFile) return
        viewModelScope.launch {
            _uiState.update { it.copy(isPreparingFile = true) }
            var targetRecipientId: String? = null
            try {
                val chat = chatRepository.getChatById(chatId)
                    ?: error("Чат недоступен")
                val recipientId = chat.contactId
                targetRecipientId = recipientId
                check(recipientId.startsWith("pk_")) { "У контакта нет ключа для передачи файлов" }
                val messageId = UUID.randomUUID().toString()
                val prepared = filePreparation.prepare(
                    source = uri,
                    messageId = messageId,
                    chatId = chatId,
                    recipientNodeId = recipientId,
                    qualifiedDirectReferrals = ReferralRankStore.qualifiedDirectCount(appContext),
                )
                chatRepository.insertLocalFileMessage(
                    chatId = chatId,
                    recipientId = recipientId,
                    messageId = messageId,
                    content = FileTransferRouter.formatPlaceholder(
                        prepared.displayName,
                        prepared.mediaType,
                        prepared.totalBytes,
                    ),
                    timestamp = System.currentTimeMillis(),
                )
                _uiState.update { it.copy(scrollToBottom = true) }
                fileTransferRouter.pumpOutgoing()
            } catch (e: Exception) {
                android.util.Log.w("ChatDetailVM", "File prepare failed", e)
                val message = e.message.orEmpty()
                if (message.contains("binding is not pinned")) {
                    // First contact between these phones for files: push our signed HELLO so the
                    // recipient can pin us and reply; durable transport delivers it when online.
                    targetRecipientId?.let { fileTransferRouter.requestExchangeBinding(it) }
                    _uiState.update {
                        it.copy(error = "Ключ получателя ещё не закреплён. Отправил запрос — попробуйте снова через пару минут.")
                    }
                } else {
                    _uiState.update { it.copy(error = "Файл не отправлен: ${e.message}") }
                }
            } finally {
                _uiState.update { it.copy(isPreparingFile = false) }
            }
        }
    }

    fun onScrolledToBottom() {
        _uiState.update { it.copy(scrollToBottom = false) }
    }

    /**
     * Received-file export: the SAF picker (launched by the screen) returns a user-chosen Uri;
     * the plaintext is streamed out of the app-private verified storage into it.
     */
    fun requestSaveReceivedFile(transfer: FileTransferEntity) {
        if (transfer.direction != "INCOMING" || transfer.state != "COMPLETE") return
        _uiState.update { it.copy(pendingSave = transfer) }
    }

    fun onSaveTargetPicked(target: android.net.Uri?) {
        val transfer = _uiState.value.pendingSave
        _uiState.update { it.copy(pendingSave = null) }
        if (target == null || transfer == null) return
        viewModelScope.launch {
            val ok = runCatching { fileTransferRouter.exportReceivedFile(transfer, target) }
                .getOrDefault(false)
            _uiState.update {
                it.copy(
                    error = if (ok) "Сохранено: ${transfer.displayName}" else "Не удалось сохранить файл",
                )
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
