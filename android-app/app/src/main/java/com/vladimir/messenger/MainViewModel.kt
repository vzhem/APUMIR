package com.vladimir.messenger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.domain.usecase.CheckIdentityUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainUiState(
    val isLoading: Boolean    = true,
    val hasIdentity: Boolean  = false,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val checkIdentityUseCase: CheckIdentityUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        checkIdentity()
    }

    private fun checkIdentity() {
        viewModelScope.launch {
            val hasIdentity = checkIdentityUseCase()
            _uiState.update { it.copy(
                isLoading   = false,
                hasIdentity = hasIdentity,
            )}
        }
    }
}