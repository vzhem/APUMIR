package com.vladimir.messenger.ui.screens.contacts

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.data.repository.ChatRepository
import com.vladimir.messenger.data.repository.ContactRepository
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.util.InviteLinkParser
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AddContactUiState(
    val inviteLink: String = "",
    val displayName: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val contactAdded: Boolean = false,
)

@HiltViewModel
class AddContactViewModel @Inject constructor(
    private val contactRepository: ContactRepository,
    private val chatRepository: ChatRepository,
) : ViewModel() {

    companion object {
        private const val TAG = "AddContactVM"
    }

    private val _uiState = MutableStateFlow(AddContactUiState())
    val uiState = _uiState.asStateFlow()

    fun onInviteLinkChanged(value: String) {
        _uiState.update { it.copy(inviteLink = value, error = null, contactAdded = false) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun onDisplayNameChanged(value: String) {
        _uiState.update { it.copy(displayName = value) }
    }

    fun onAddContactClicked() {
        val raw = _uiState.value.inviteLink.trim()
        if (raw.isBlank()) {
            _uiState.update { it.copy(error = "Invite link is empty") }
            return
        }

        // Сначала общий разборщик ссылок: приложение само генерирует
        // p2pmessenger://add?node_id=..., а этот блок раньше понимал только
        // p2p://invite/, p2p://key/ и голый pk_, то есть QR контакта не срабатывал.
        val parsedInvite = InviteLinkParser.parse(raw)
        val fingerprint = parsedInvite?.nodeId ?: when {
            raw.contains("node=pk_") -> "pk_" + raw.substringAfter("node=pk_").substringBefore("&")
            raw.startsWith("p2p://invite/") -> raw.removePrefix("p2p://invite/").trim()
            raw.startsWith("p2p://key/") -> raw.removePrefix("p2p://key/").trim()
            raw.startsWith("pk_") -> raw.trim()
            else -> raw.trim()
        }

        if (fingerprint.isBlank()) {
            _uiState.update { it.copy(error = "Invalid invite link") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val displayName = _uiState.value.displayName.trim().takeIf { it.isNotBlank() }
                ?: parsedInvite?.displayName?.trim()?.takeIf { it.isNotBlank() }
                ?: "Contact ${fingerprint.takeLast(8)}"

            // 1. обавляем контакт
            val contactResult = contactRepository.addContact(displayName, fingerprint)

            contactResult
                .onSuccess { contact ->
                    Log.i(TAG, "Contact added: ${contact.displayName}")
                    // 2. Сразу создаём чат для этого контакта
                    val chat = chatRepository.createChat(fingerprint, contact.displayName)
                    Log.i(TAG, "Chat created: ${chat.id} for ${chat.contactName}")
                    _uiState.update { it.copy(isLoading = false, contactAdded = true, inviteLink = "", displayName = "") }
                }
                .onFailure { error ->
                    // сли контакт уже есть — ищем существующий чат
                    if (error.message == "Contact already exists") {
                        Log.i(TAG, "Contact already exists, finding existing chat")
                        // Всё равно регистрируем peer в Rust
                        try { RustBridge.connectViaInvite(raw) } catch (_: Exception) {}
                        val existing = contactRepository.getContactByFingerprint(fingerprint)
                        if (existing != null) {
                            // роверяем есть ли уже чат, если нет — создаём
                            chatRepository.getOrCreateChat(fingerprint, existing.displayName)
                        }
                        _uiState.update { it.copy(isLoading = false, contactAdded = true, inviteLink = "", displayName = "") }
                    } else {
                        Log.e(TAG, "Failed to add contact", error)
                        _uiState.update {
                            it.copy(isLoading = false, error = error.message ?: "Failed to add contact")
                        }
                    }
                }
        }
    }
}