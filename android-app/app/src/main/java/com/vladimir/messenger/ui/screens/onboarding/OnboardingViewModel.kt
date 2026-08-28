package com.vladimir.messenger.ui.screens.onboarding

// =============================================================================
// ONBOARDINGVIEWMODEL.KT
// =============================================================================
// Управляет логикой онбординга:
//   1. Проверяет есть ли уже профиль
//   2. Создаёт новый профиль (имя + генерация ключей)
//   3. Предоставляет invite-ссылку для первого контакта
// =============================================================================

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.data.local.dao.NicknameDao
import com.vladimir.messenger.data.local.entity.NicknameEntity
import com.vladimir.messenger.domain.usecase.CreateIdentityUseCase
import com.vladimir.messenger.domain.usecase.CheckIdentityUseCase
import com.vladimir.messenger.ui.theme.UsernameHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    private val nicknameDao: NicknameDao,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    companion object {
        // Короткие «космические» слова для случайных @имён при регистрации.
        private val NICK_WORDS = listOf(
            "astrid", "vega", "altair", "sirius", "lyra", "orion", "draco",
            "persei", "foton", "kvazar", "pulsar", "neon", "argon", "krypton",
            "xenon", "radon", "cobalt", "titan", "zircon", "osmium", "iridium",
            "hafnium", "photon", "quasar", "nova", "comet", "meteor", "orbit",
            "sputnik", "vostok",
        )
    }

    /**
     * Случайное свободное @имя: проверяем роевой реестр, чтобы в системе не
     * было двух одинаковых ников. Своё сразу регистрируем в реестре.
     */
    private suspend fun assignRandomUsername(me: String) {
        val now = System.currentTimeMillis()
        repeat(25) {
            val name = NICK_WORDS.random() + (200..999).random()
            val taken = nicknameDao.byName(name).any { it.ownerId != me }
            if (!taken) {
                nicknameDao.upsert(NicknameEntity(ownerId = me, name = name, registeredAtMs = now))
                appContext.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("my_username", name)
                    .putLong("my_nick_registered_at", now)
                    .apply()
                UsernameHolder.set(appContext, name)
                return
            }
        }
    }

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
                    // Регистрация: автоматически присваиваем случайное свободное @имя.
                    viewModelScope.launch {
                        try {
                            assignRandomUsername(identity.fingerprint)
                        } catch (e: Exception) {
                            Log.e("OnboardingVM", "username assign failed", e)
                        }
                    }
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