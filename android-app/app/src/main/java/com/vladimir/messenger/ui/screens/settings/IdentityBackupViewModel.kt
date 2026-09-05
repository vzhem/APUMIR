package com.vladimir.messenger.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.data.security.IdentityBackup
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class IdentityBackupUiState(
    val protectedNickname: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val failed: Boolean = false,
)

@HiltViewModel
class IdentityBackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backup: IdentityBackup,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        IdentityBackupUiState(protectedNickname = backup.protectedNickname(context)),
    )
    val uiState = _uiState.asStateFlow()

    /**
     * Запереть личность паролем.
     *
     * Работа идёт в фоне: вывод ключа намеренно медленный (сотни тысяч
     * повторов), и на главном потоке это подвесило бы экран.
     */
    fun save(nickname: String, password: String) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, message = null, failed = false) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                backup.save(context, nickname, password)
            }
            when (result) {
                IdentityBackup.SaveResult.Success -> _uiState.update {
                    it.copy(
                        busy = false,
                        protectedNickname = backup.protectedNickname(context),
                        message = "Готово. Запомните никнейм и пароль - только они вернут вас после переустановки.",
                        failed = false,
                    )
                }
                IdentityBackup.SaveResult.BadInput -> fail(
                    "Проверьте никнейм и пароль: пароль не короче ${IdentityBackupMin.PASSWORD} знаков."
                )
                IdentityBackup.SaveResult.NoIdentity -> fail(
                    "Личность ещё не создана - сначала завершите регистрацию."
                )
                IdentityBackup.SaveResult.NetworkFailed -> fail(
                    "Нет связи с сервером. Личность не сохранена, попробуйте позже."
                )
            }
        }
    }

    /** Вернуть прежнюю личность и перезапустить движок под её адресом. */
    fun restore(nickname: String, password: String) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, message = null, failed = false) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                backup.restore(context, nickname, password)
            }
            when (result) {
                is IdentityBackup.RestoreResult.Success -> {
                    _uiState.update {
                        it.copy(
                            busy = false,
                            protectedNickname = backup.protectedNickname(context),
                            message = "Личность возвращена. Перезапустите приложение, чтобы " +
                                "собеседники снова увидели вас прежним.",
                            failed = false,
                        )
                    }
                }
                IdentityBackup.RestoreResult.NotFound -> fail(
                    "Под этим никнеймом ничего не сохранено. Проверьте написание."
                )
                IdentityBackup.RestoreResult.WrongPassword -> fail(
                    "Никнейм или пароль не подошли."
                )
                IdentityBackup.RestoreResult.NetworkFailed -> fail(
                    "Нет связи с сервером. Попробуйте позже."
                )
            }
        }
    }

    private fun fail(text: String) {
        _uiState.update { it.copy(busy = false, message = text, failed = true) }
    }
}

/** Держим порог в одном месте, чтобы текст ошибки не разошёлся с проверкой. */
object IdentityBackupMin {
    const val PASSWORD = 8
}
