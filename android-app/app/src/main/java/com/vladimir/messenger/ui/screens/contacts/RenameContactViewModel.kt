package com.vladimir.messenger.ui.screens.contacts

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.data.repository.ContactRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RenameContactUiState(
    val contactId: String = "",
    val currentName: String = "",
    val newName: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val renamed: Boolean = false,
)

@HiltViewModel
class RenameContactViewModel @Inject constructor(
    private val contactRepository: ContactRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    companion object {
        private const val TAG = "RenameContactVM"
    }

    private val _uiState = MutableStateFlow(RenameContactUiState())
    val uiState = _uiState.asStateFlow()

    init {
        val contactId = savedStateHandle.get<String>("contactId") ?: ""
        val currentName = savedStateHandle.get<String>("currentName") ?: ""
        _uiState.update { it.copy(contactId = contactId, currentName = currentName, newName = currentName) }
        Log.i(TAG, "RenameContactVM: contactId=$contactId currentName=$currentName")
    }

    fun onNewNameChanged(value: String) {
        _uiState.update { it.copy(newName = value, error = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun onRenameClicked() {
        val newName = _uiState.value.newName.trim()
        val contactId = _uiState.value.contactId

        if (newName.isBlank()) {
            _uiState.update { it.copy(error = "Name cannot be empty") }
            return
        }
        if (contactId.isBlank()) {
            _uiState.update { it.copy(error = "Contact ID is missing") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = contactRepository.renameContact(contactId, newName)
            result
                .onSuccess {
                    Log.i(TAG, "Contact renamed: $contactId -> $newName")
                    _uiState.update { it.copy(isLoading = false, renamed = true) }
                }
                .onFailure { e ->
                    Log.e(TAG, "Rename failed", e)
                    _uiState.update { it.copy(isLoading = false, error = e.message ?: "Rename failed") }
                }
        }
    }
}
