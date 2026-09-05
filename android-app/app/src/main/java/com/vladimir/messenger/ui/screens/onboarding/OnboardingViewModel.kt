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
    /** @никнейм: главное имя человека, по нему же он и восстанавливается. */
    val nickname: String       = "",
    val nicknameError: String? = null,
    val password: String       = "",
    val passwordRepeat: String = "",
    val passwordError: String? = null,
    /** Вкладка «Я уже зарегистрирован»: вход по никнейму и паролю. */
    val restoreMode: Boolean   = false,
    val isLoading: Boolean     = false,
    val createdInviteLink: String? = null,
    val fingerprint: String?   = null,
    val error: String?         = null,
) {
    /**
     * Готова ли форма. Экран показывает это подсказками под полями, а не
     * молча тёмной кнопкой: раньше человек видел неактивную кнопку и не
     * понимал, чего от него хотят.
     */
    val canSubmit: Boolean
        get() = if (restoreMode) {
            nickname.isNotBlank() && password.isNotEmpty() && !isLoading
        } else {
            displayName.trim().length >= 2 &&
                nickname.isNotBlank() &&
                password.length >= MIN_PASSWORD_LENGTH &&
                password == passwordRepeat &&
                !isLoading
        }
}

/** Столько знаков минимум в пароле - короче слишком легко подобрать. */
const val MIN_PASSWORD_LENGTH = 8

enum class OnboardingStep {
    EnterName,      // Ввод имени, никнейма и пароля
    Generating,     // Генерация ключей (анимация)
    ShowInvite,     // Показ QR-кода / invite-ссылки
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val createIdentityUseCase: CreateIdentityUseCase,
    private val checkIdentityUseCase: CheckIdentityUseCase,
    private val nicknameDao: NicknameDao,
    private val identityBackup: com.vladimir.messenger.data.security.IdentityBackup,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    /**
     * Закрепить выбранный человеком @никнейм.
     *
     * Никнейм теперь задаётся при регистрации и служит именем, по которому
     * человек возвращает себя после переустановки, поэтому случайный больше
     * не назначается.
     */
    private suspend fun claimUsername(me: String, nick: String) {
        val now = System.currentTimeMillis()
        nicknameDao.upsert(NicknameEntity(ownerId = me, name = nick, registeredAtMs = now))
        appContext.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("my_username", nick)
            .putLong("my_nick_registered_at", now)
            .apply()
        UsernameHolder.set(appContext, nick)
    }

    /** Занят ли никнейм кем-то другим в известной части сети. */
    suspend fun isNicknameTaken(nick: String, me: String): Boolean =
        runCatching { nicknameDao.byName(nick).any { it.ownerId != me } }.getOrDefault(false)

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

    /** Никнейм чистим на лету: только буквы, цифры и подчёркивание. */
    fun onNicknameChanged(value: String) {
        val clean = value.trimStart('@').filter { it.isLetterOrDigit() || it == '_' }.take(32)
        _uiState.update { it.copy(nickname = clean, nicknameError = null, error = null) }
    }

    fun onPasswordChanged(value: String) {
        _uiState.update { it.copy(password = value, passwordError = null, error = null) }
    }

    fun onPasswordRepeatChanged(value: String) {
        _uiState.update { it.copy(passwordRepeat = value, passwordError = null, error = null) }
    }

    /** Переключение между «Новый профиль» и «Я уже зарегистрирован». */
    fun onRestoreModeChanged(restore: Boolean) {
        _uiState.update {
            it.copy(
                restoreMode = restore,
                nameError = null,
                nicknameError = null,
                passwordError = null,
                error = null,
            )
        }
    }

    /**
     * Вход по никнейму и паролю для тех, кто уже зарегистрирован.
     *
     * Возвращает ПРЕЖНЮЮ личность: тот же адрес, ранг и приглашения. Для
     * собеседников человек не менялся, поэтому ничего склеивать не нужно.
     */
    fun onRestoreClicked() {
        val state = _uiState.value
        val nick = state.nickname.trim().trimStart('@')
        if (nick.isBlank()) {
            _uiState.update { it.copy(nicknameError = "Введите свой никнейм") }
            return
        }
        if (state.password.isEmpty()) {
            _uiState.update { it.copy(passwordError = "Введите пароль") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = identityBackup.restore(appContext, nick, state.password)) {
                is com.vladimir.messenger.data.security.IdentityBackup.RestoreResult.Success -> {
                    UsernameHolder.set(appContext, nick)
                    _uiState.update {
                        it.copy(
                            step = OnboardingStep.ShowInvite,
                            isLoading = false,
                            fingerprint = result.nodeId,
                            createdInviteLink = com.vladimir.messenger.util.OwnInvite.link(appContext)
                                ?: "p2p://invite/${result.nodeId}",
                        )
                    }
                }
                com.vladimir.messenger.data.security.IdentityBackup.RestoreResult.NotFound ->
                    fail("Под этим никнеймом ничего не сохранено. Проверьте написание.")
                com.vladimir.messenger.data.security.IdentityBackup.RestoreResult.WrongPassword ->
                    fail("Никнейм или пароль не подошли.")
                com.vladimir.messenger.data.security.IdentityBackup.RestoreResult.NetworkFailed ->
                    fail("Нет связи с сервером. Попробуйте позже.")
            }
        }
    }

    private fun fail(text: String) {
        _uiState.update { it.copy(isLoading = false, error = text) }
    }

    // ------------------------------------------------------------------
    // Нажата кнопка "Создать профиль"
    // ------------------------------------------------------------------
    fun onCreateProfileClicked() {
        val state = _uiState.value
        val name = state.displayName.trim()
        val nick = state.nickname.trim().trimStart('@')

        // Валидация
        if (name.length < 2) {
            _uiState.update { it.copy(nameError = "Имя должно быть минимум 2 символа") }
            return
        }
        if (name.length > 50) {
            _uiState.update { it.copy(nameError = "Имя не должно превышать 50 символов") }
            return
        }
        if (nick.length < 3) {
            _uiState.update { it.copy(nicknameError = "Никнейм минимум 3 знака") }
            return
        }
        if (state.password.length < MIN_PASSWORD_LENGTH) {
            _uiState.update {
                it.copy(passwordError = "Пароль минимум $MIN_PASSWORD_LENGTH знаков")
            }
            return
        }
        if (state.password != state.passwordRepeat) {
            _uiState.update { it.copy(passwordError = "Пароли не совпадают") }
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
                    // Занятость проверяем ПОСЛЕ создания ключей: до этого нет
                    // своего адреса, а чужой владелец того же имени определяется
                    // именно сравнением с ним.
                    if (isNicknameTaken(nick, identity.fingerprint)) {
                        _uiState.update {
                            it.copy(
                                step = OnboardingStep.EnterName,
                                isLoading = false,
                                nicknameError = "Этот никнейм уже занят, выберите другой",
                            )
                        }
                        return@onSuccess
                    }
                    // Никнейм человек выбрал сам - он же служит именем для
                    // восстановления, поэтому случайный больше не назначаем.
                    try {
                        claimUsername(identity.fingerprint, nick)
                    } catch (e: Exception) {
                        Log.e("OnboardingVM", "username claim failed", e)
                    }
                    // Запираем личность сразу: если отложить, человек рискует
                    // потерять себя при первой же переустановке. Сбой сети не
                    // должен мешать регистрации - пароль можно задать позже
                    // в «Настройки - Безопасность».
                    val saved = runCatching {
                        identityBackup.save(appContext, nick, state.password)
                    }.getOrNull()
                    // SavedLocally - штатный исход без сети: пароль уже
                    // действует, конверт дошлётся сам.
                    val vaultOk = saved == com.vladimir.messenger.data.security.IdentityBackup.SaveResult.Success ||
                        saved == com.vladimir.messenger.data.security.IdentityBackup.SaveResult.SavedLocally
                    if (!vaultOk) {
                        Log.w("OnboardingVM", "identity vault not stored: $saved")
                    }
                    // Ссылку пересобираем ЗДЕСЬ, а не берём из identity:
                    // та строилась до сохранения никнейма и получалась без
                    // него. QR с регистрации отличался от QR с главной, и
                    // отсканировавший не узнавал @имя собеседника.
                    val inviteWithNick = com.vladimir.messenger.util.OwnInvite.link(appContext)
                        ?: identity.inviteLink
                    _uiState.update { it.copy(
                        step            = OnboardingStep.ShowInvite,
                        isLoading       = false,
                        createdInviteLink = inviteWithNick,
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