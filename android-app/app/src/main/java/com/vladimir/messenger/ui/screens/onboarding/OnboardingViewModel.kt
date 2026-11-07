package com.vladimir.messenger.ui.screens.onboarding

import com.vladimir.messenger.R
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
import com.vladimir.messenger.data.local.entity.NicknameSuppressionEntity
import com.vladimir.messenger.data.security.DeviceIdentityGossip
import com.vladimir.messenger.data.security.DeviceIdentityHistory
import com.vladimir.messenger.data.security.DeviceProfileAnchor
import com.vladimir.messenger.data.security.DeviceProfileBinding
import com.vladimir.messenger.domain.usecase.CreateIdentityUseCase
import com.vladimir.messenger.domain.usecase.CheckIdentityUseCase
import com.vladimir.messenger.service.BotApi
import com.vladimir.messenger.ui.theme.UsernameHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
    val nodeId: String?        = null,
    /** Прежний профиль, найденный по отпечатку ANDROID_ID этого телефона после переустановки. */
    val recognizedDeviceProfile: DeviceProfileAnchor.DeviceHintRecord? = null,
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
    private val botApi: BotApi,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val found = runCatching {
                DeviceProfileBinding.discoverPreviousProfile(appContext, botApi)
            }.onFailure { Log.w("OnboardingVM", "device hint check skipped: ${it.message}") }
                .getOrNull() ?: return@launch
            _uiState.update { current ->
                val untouched = current.displayName.isBlank() &&
                    current.nickname.isBlank() &&
                    current.password.isEmpty() &&
                    current.step == OnboardingStep.EnterName
                if (untouched && found.nickname.isNotBlank()) {
                    current.copy(
                        recognizedDeviceProfile = found,
                        restoreMode = true,
                        nickname = found.nickname,
                    )
                } else {
                    current.copy(recognizedDeviceProfile = found)
                }
            }
        }
    }

    fun onSwitchToRecognizedProfile() {
        val found = _uiState.value.recognizedDeviceProfile ?: return
        _uiState.update {
            it.copy(
                restoreMode = true,
                nickname = found.nickname.ifBlank { it.nickname },
                nameError = null,
                nicknameError = null,
                passwordError = null,
                error = null,
            )
        }
    }

    /**
     * Закрепить выбранный человеком @никнейм.
     *
     * Никнейм теперь задаётся при регистрации и служит именем, по которому
     * человек возвращает себя после переустановки, поэтому случайный больше
     * не назначается.
     */
    private suspend fun claimUsername(me: String, nick: String) {
        val now = System.currentTimeMillis()
        // A current profile is never blocked by a tombstone left for an older
        // incarnation of the same owner/name pair.  Clear both the Room row and
        // the Keystore marker when the user explicitly restores that identity.
        val normalizedNick = nick.trim().trimStart('@').lowercase()
        nicknameDao.unsuppress(me, normalizedNick)
        com.vladimir.messenger.data.security.NicknameRevocationHistory
            .clear(appContext, me, normalizedNick)
        nicknameDao.upsert(NicknameEntity(ownerId = me, name = normalizedNick, registeredAtMs = now))
        DeviceIdentityHistory.remember(appContext, me)
        appContext.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("my_username", nick)
            .putLong("my_nick_registered_at", now)
            .apply()
        UsernameHolder.set(appContext, nick)
    }

    /** Занят ли никнейм кем-то другим в известной части сети. */
    suspend fun isNicknameTaken(nick: String, me: String): Boolean =
        runCatching {
            nicknameDao.byName(nick).any {
                it.ownerId != me && !DeviceProfileBinding.belongsToThisPhysicalDevice(appContext, it.ownerId)
            }
        }.getOrDefault(false)

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
            _uiState.update { it.copy(nicknameError = appContext.getString(R.string.ob_enter_nick)) }
            return
        }
        if (state.password.isEmpty()) {
            _uiState.update { it.copy(passwordError = appContext.getString(R.string.ob_enter_pw)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = identityBackup.restore(appContext, nick, state.password)) {
                is com.vladimir.messenger.data.security.IdentityBackup.RestoreResult.Success -> {
                    // A clearApplicationUserData wiped the local registry too;
                    // restore the live owner row so the current address is
                    // visible and can be the safe destination for history.
                    runCatching { claimUsername(result.nodeId, nick) }
                        .onFailure { Log.w("OnboardingVM", "restored username row failed", it) }
                    UsernameHolder.set(appContext, nick)
                    // Раунд 256: запираем пароль входа для автосинхронизации -
                    // окно переноса подставит его само, без повторного ввода.
                    com.vladimir.messenger.data.backup.ProfileSyncAuto
                        .rememberPassword(appContext, state.password.toCharArray())
                    _uiState.update {
                        it.copy(
                            step = OnboardingStep.ShowInvite,
                            isLoading = false,
                            fingerprint = result.nodeId,
                            nodeId = result.nodeId,
                            createdInviteLink = com.vladimir.messenger.util.OwnInvite.link(appContext)
                                ?: "p2p://invite/${result.nodeId}",
                        )
                    }
                }
                com.vladimir.messenger.data.security.IdentityBackup.RestoreResult.NotFound ->
                    fail(appContext.getString(R.string.id_not_found))
                com.vladimir.messenger.data.security.IdentityBackup.RestoreResult.WrongPassword ->
                    fail(appContext.getString(R.string.id_wrong_creds))
                com.vladimir.messenger.data.security.IdentityBackup.RestoreResult.NetworkFailed ->
                    fail(appContext.getString(R.string.id_no_network))
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
            _uiState.update { it.copy(nameError = appContext.getString(R.string.ob_name_min2)) }
            return
        }
        if (name.length > 50) {
            _uiState.update { it.copy(nameError = appContext.getString(R.string.ob_name_max50)) }
            return
        }
        if (nick.length < 3) {
            _uiState.update { it.copy(nicknameError = appContext.getString(R.string.ob_nick_min3)) }
            return
        }
        if (state.password.length < MIN_PASSWORD_LENGTH) {
            _uiState.update {
                it.copy(passwordError = appContext.getString(R.string.ob_pw_min2, MIN_PASSWORD_LENGTH))
            }
            return
        }
        if (state.password != state.passwordRepeat) {
            _uiState.update { it.copy(passwordError = appContext.getString(R.string.ob_pw_mismatch)) }
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
                    if (isNicknameTaken(nick, identity.nodeId)) {
                        _uiState.update {
                            it.copy(
                                step = OnboardingStep.EnterName,
                                isLoading = false,
                                nicknameError = appContext.getString(R.string.ob_nick_taken),
                            )
                        }
                        return@onSuccess
                    }
                    // Никнейм человек выбрал сам - он же служит именем для
                    // восстановления, поэтому случайный больше не назначаем.
                    val previousOwner = state.recognizedDeviceProfile?.nodeId
                        ?.takeIf { it != identity.nodeId }
                    try {
                        if (previousOwner != null) {
                            val oldNick = state.recognizedDeviceProfile.nickname.trim().lowercase()
                            com.vladimir.messenger.data.security.NicknameRevocationHistory
                                .revokeOwner(appContext, previousOwner)
                            if (oldNick.isNotBlank()) {
                                com.vladimir.messenger.data.security.NicknameRevocationHistory
                                    .revoke(appContext, previousOwner, oldNick)
                                nicknameDao.suppress(
                                    NicknameSuppressionEntity(
                                        ownerId = previousOwner,
                                        name = oldNick,
                                        revokedAtMs = System.currentTimeMillis(),
                                    ),
                                )
                            }
                            nicknameDao.deleteByOwner(previousOwner)
                        }
                        claimUsername(identity.nodeId, nick)
                    } catch (e: Exception) {
                        Log.e("OnboardingVM", "username claim failed", e)
                    }
                    runCatching {
                        DeviceProfileBinding.saveCurrentProfile(
                            context = appContext,
                            botApi = botApi,
                            nodeId = identity.nodeId,
                            nickname = nick,
                            displayName = name,
                            previousOwnerId = previousOwner,
                        )
                        DeviceIdentityGossip.onProfileOrContactsChanged()
                    }.onFailure { Log.w("OnboardingVM", "device profile save skipped: ${it.message}") }
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
                    // Раунд 256: пароль регистрации запираем для автосинхронизации -
                    // окно переноса подставит его само, без повторного ввода.
                    if (state.password.isNotEmpty()) {
                        com.vladimir.messenger.data.backup.ProfileSyncAuto
                            .rememberPassword(appContext, state.password.toCharArray())
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
                        nodeId          = identity.nodeId,
                    )}
                }
                .onFailure { e ->
                    _uiState.update { it.copy(
                        step      = OnboardingStep.EnterName,
                        isLoading = false,
                        error     = appContext.getString(R.string.ob_profile_error, e.message)
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