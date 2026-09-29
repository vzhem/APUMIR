package com.vladimir.messenger.ui.screens.settings

// =============================================================================
// PROFILESYNCVIEWMODEL.KT — «Синхронизировать аккаунт» (Настройки)
// =============================================================================
// Раунд 223. Загрузить копию профиля в облако / скачать и подготовить
// восстановление / авто-синхронизация с периодичностью. Восстановление -
// штатный путь файла-копии: stage -> подтверждение -> перезапуск.
// =============================================================================

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.data.backup.BackupManifest
import com.vladimir.messenger.data.backup.ProfileBackup
import com.vladimir.messenger.data.backup.ProfileSync
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Состояние окна синхронизации. */
data class ProfileSyncUiState(
    val busy: Boolean = false,
    val sync: ProfileSync.State = ProfileSync.State(),
    val cloud: ProfileSync.CloudInfo = ProfileSync.CloudInfo(),
    val password: String = "",
    /** Копия, скачанная из облака и ждущая подтверждения. */
    val staged: BackupManifest? = null,
    val message: String? = null,
    val failed: Boolean = false,
    val restarting: Boolean = false,
)

@HiltViewModel
class ProfileSyncViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backup: ProfileBackup,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileSyncUiState())
    val uiState: StateFlow<ProfileSyncUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /** Строки расписания и облака. */
    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(sync = ProfileSync.state(context))
            _uiState.value = _uiState.value.copy(cloud = ProfileSync.cloudInfo(context))
        }
    }

    fun onPasswordChange(value: String) {
        _uiState.value = _uiState.value.copy(password = value.take(128))
    }

    private fun passwordChars(): CharArray = _uiState.value.password.toCharArray()

    /** Загрузить свежую копию этого устройства в облако. */
    fun uploadNow() {
        if (_uiState.value.busy) return
        val chars = passwordChars()
        _uiState.value = _uiState.value.copy(busy = true, message = null, failed = false)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                ProfileSync.uploadBlocking(context, backup, chars)
            }
            chars.fill('\u0000')
            val (message, failed) = when (result) {
                is ProfileSync.UploadResult.Ok ->
                    "Загружено: ${ProfileBackupViewModel.humanBytes(result.bytes)}" to false
                ProfileSync.UploadResult.NoIdentity ->
                    "Сначала создайте профиль" to true
                ProfileSync.UploadResult.BadPassword ->
                    "Пароль короткий - нужно минимум ${com.vladimir.messenger.data.backup.BackupCipher.MIN_PASSWORD_LENGTH} знаков" to true
                is ProfileSync.UploadResult.TooBig ->
                    "Копия ${result.megaBytes} МБ - не влезает в облако (лимит 24 МБ без медиа)" to true
                is ProfileSync.UploadResult.Failed ->
                    "Не удалось: ${result.reason}" to true
            }
            _uiState.value = _uiState.value.copy(busy = false, message = message, failed = failed)
            refresh()
        }
    }

    /** Включить/выключить авто-синхронизацию. Для включения нужен пароль. */
    fun setAutoEnabled(enabled: Boolean, period: ProfileSync.Period) {
        if (enabled) {
            val chars = passwordChars()
            val ok = ProfileSync.enable(context, chars, period)
            chars.fill('\u0000')
            _uiState.value = _uiState.value.copy(
                sync = ProfileSync.state(context),
                message = if (ok) "Автосинхронизация включена: ${period.title}" else null,
                failed = !ok,
            )
        } else {
            ProfileSync.disable(context)
            _uiState.value = _uiState.value.copy(
                sync = ProfileSync.state(context),
                message = "Автосинхронизация выключена",
                failed = false,
            )
        }
    }

    fun setPeriod(period: ProfileSync.Period) {
        ProfileSync.setPeriod(context, period)
        _uiState.value = _uiState.value.copy(sync = ProfileSync.state(context))
    }

    /** Скачать копию из облака и подготовить восстановление (без применения). */
    fun downloadAndStage() {
        if (_uiState.value.busy) return
        val chars = passwordChars()
        _uiState.value = _uiState.value.copy(busy = true, message = null, failed = false)
        viewModelScope.launch {
            val downloaded = ProfileSync.download(context)
            if (downloaded !is ProfileSync.DownloadResult.Ready) {
                chars.fill('\u0000')
                val (message, failed) = when (downloaded) {
                    ProfileSync.DownloadResult.NotFound ->
                        "В облаке ещё нет копии - загрузите её с основного устройства" to true
                    is ProfileSync.DownloadResult.Failed ->
                        "Не удалось скачать: ${downloaded.reason}" to true
                    else -> "Не удалось скачать" to true
                }
                _uiState.value = _uiState.value.copy(busy = false, message = message, failed = failed)
                return@launch
            }
            // Штатный путь файла-копии: открыть паролем и подготовить.
            val staged = withContext(Dispatchers.IO) {
                backup.stage(Uri.fromFile(downloaded.file), chars)
            }
            chars.fill('\u0000')
            when (staged) {
                is ProfileBackup.StageResult.Ready -> {
                    _uiState.value = _uiState.value.copy(
                        busy = false, staged = staged.manifest, message = null, failed = false,
                    )
                }
                is ProfileBackup.StageResult.WrongPassword -> {
                    backup.discardStaged()
                    _uiState.value = _uiState.value.copy(
                        busy = false,
                        message = "Неверный пароль копии",
                        failed = true,
                    )
                }
                is ProfileBackup.StageResult.TooNew -> {
                    backup.discardStaged()
                    _uiState.value = _uiState.value.copy(
                        busy = false,
                        message = "Копия сделана более новой версией APU (${staged.appVersion}) - сначала обновите это устройство",
                        failed = true,
                    )
                }
                else -> {
                    backup.discardStaged()
                    _uiState.value = _uiState.value.copy(
                        busy = false,
                        message = "Копию не удалось открыть",
                        failed = true,
                    )
                }
            }
        }
    }

    /** Отложить подготовленную копию (не применять). */
    fun discardStaged() {
        backup.discardStaged()
        _uiState.value = _uiState.value.copy(staged = null)
    }

    /**
     * Применить подготовленную копию при следующем старте и перезапустить
     * приложение (тот же порядок, что у «Резервной копии»).
     */
    fun confirmAndExit(onExit: () -> Unit) {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { backup.confirmStaged() }
            if (!ok) {
                _uiState.value = _uiState.value.copy(
                    message = "Подготовленная копия не найдена - скачайте ещё раз",
                    failed = true,
                )
                return@launch
            }
            _uiState.value = _uiState.value.copy(restarting = true, message = null, failed = false)
            val app = context.applicationContext
            runCatching {
                app.stopService(android.content.Intent(app, com.vladimir.messenger.service.CoreServerService::class.java))
            }
            onExit()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                android.os.Process.killProcess(android.os.Process.myPid())
            }, 400)
        }
    }
}
