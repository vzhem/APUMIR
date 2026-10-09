package com.vladimir.messenger.ui.screens.settings

import com.vladimir.messenger.R
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
                        message = context.getString(R.string.id_saved_ok),
                        failed = false,
                    )
                }
                IdentityBackup.SaveResult.BadInput -> fail(
                    context.getString(R.string.id_check_input, IdentityBackupMin.PASSWORD)
                )
                IdentityBackup.SaveResult.SavedLocally -> _uiState.update {
                    it.copy(
                        busy = false,
                        protectedNickname = backup.protectedNickname(context),
                        // Не ошибка: пароль уже работает, просто вход с ДРУГОГО
                        // устройства станет возможен после досылки.
                        message = context.getString(R.string.id_saved_local),
                        failed = false,
                    )
                }
                IdentityBackup.SaveResult.NoIdentity -> fail(
                    context.getString(R.string.id_no_identity)
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
                            message = context.getString(R.string.id_restored),
                            failed = false,
                        )
                    }
                }
                IdentityBackup.RestoreResult.NotFound -> fail(
                    context.getString(R.string.id_not_found)
                )
                IdentityBackup.RestoreResult.WrongPassword -> fail(
                    context.getString(R.string.id_wrong_creds)
                )
                IdentityBackup.RestoreResult.NetworkFailed -> fail(
                    context.getString(R.string.id_no_network)
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
