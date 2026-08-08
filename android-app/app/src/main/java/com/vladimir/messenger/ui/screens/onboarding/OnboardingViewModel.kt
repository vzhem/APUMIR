package com.vladimir.messenger.ui.screens.onboarding

// =============================================================================
// ONBOARDINGVIEWMODEL.KT
// =============================================================================
// Управляет логикой онбординга:
//   1. Проверяет есть ли уже профиль
//   2. Создаёт новый профиль (имя + генерация ключей)
//   3. Предоставляет invite-ссылку для первого контакта
// =============================================================================

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.domain.usecase.CreateIdentityUseCase
import com.vladimir.messenger.domain.usecase.CheckIdentityUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// UI State — всё что нужно экрану
data class OnboardingUiState(
    val step: OnboardingStep   = OnboardingStep.EnterName,
    val displayName: String    = "",
    val nameError: String?     = null,
    val isLoading: Boolean     = false,
    val createdInviteLink: String? = null,
    val fingerprint: String?   = null,
    val error: String?         = null,
)

enum class OnboardingStep {
    EnterName,      // Ввод имени
    Generating,     // Генерация ключей (анимация)
    ShowInvite,     // Показ QR-кода / invite-ссылки
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val createIdentityUseCase: CreateIdentityUseCase,
    private val checkIdentityUseCase: CheckIdentityUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    // ------------------------------------------------------------------
    // Пользователь вводит имя
    // ------------------------------------------------------------------
    fun onDisplayNameChanged(name: String) {
        _uiState.update { state ->
            state.copy(
                displayName = name.take(50), // Максимум 50 символов
                nameError   = null            // Сбрасываем ошибку при вводе
            )
        }
    }

    // ------------------------------------------------------------------
    // Нажата кнопка "Создать профиль"
    // ------------------------------------------------------------------
    fun onCreateProfileClicked() {
        val name = _uiState.value.displayName.trim()

        // Валидация
        if (name.length < 2) {
            _uiState.update { it.copy(nameError = "Имя должно быть минимум 2 символа") }
            return
        }
        if (name.length > 50) {
            _uiState.update { it.copy(nameError = "Имя не должно превышать 50 символов") }
            return
        }

        viewModelScope.launch {
            // Показываем анимацию генерации ключей
            _uiState.update { it.copy(
                step      = OnboardingStep.Generating,
                isLoading = true,
                error     = null
            )}

            // Создаём идентичность (генерация Ed25519/X25519 ключей в Rust)
            createIdentityUseCase(name)
                .onSuccess { identity ->
                    _uiState.update { it.copy(
                        step            = OnboardingStep.ShowInvite,
                        isLoading       = false,
                        createdInviteLink = identity.inviteLink,
                        fingerprint     = identity.fingerprint,
                    )}
                }
                .onFailure { e ->
                    _uiState.update { it.copy(
                        step      = OnboardingStep.EnterName,
                        isLoading = false,
                        error     = "Ошибка создания профиля: ${e.message}"
                    )}
                }
        }
    }

    // ------------------------------------------------------------------
    // Пользователь завершил онбординг
    // ------------------------------------------------------------------
    fun onFinishOnboarding(): Boolean {
        return _uiState.value.step == OnboardingStep.ShowInvite
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}