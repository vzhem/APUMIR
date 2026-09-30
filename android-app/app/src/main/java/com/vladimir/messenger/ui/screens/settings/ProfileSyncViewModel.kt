package com.vladimir.messenger.ui.screens.settings

// =============================================================================
// PROFILESYNCVIEWMODEL.KT — «Синхронизировать аккаунт» (прямой перенос)
// =============================================================================
// Раунд 224: без облака. Источник собирает копию и раздаёт её напрямую по
// Wi-Fi одноразовым кодом; приёмник забирает по адресу и коду, дальше -
// штатный путь файла-копии (stage -> подтверждение -> перезапуск).
// =============================================================================

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.data.backup.BackupCipher
import com.vladimir.messenger.data.backup.BackupManifest
import com.vladimir.messenger.data.backup.ProfileBackup
import com.vladimir.messenger.data.backup.ProfileSyncDirect
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Состояние окна синхронизации (прямой перенос). */
data class ProfileSyncUiState(
    val busy: Boolean = false,
    val password: String = "",
    /** Режим источника: сервер раздачи поднят. */
    val sharing: Boolean = false,
    val shareAddress: ProfileSyncDirect.ShareAddress? = null,
    /** Поля приёмника. */
    val pullAddress: String = "",
    val pullToken: String = "",
    /** Скачанная копия, ждущая подтверждения. */
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

    private var server: ProfileSyncDirect.ShareServer? = null

    override fun onCleared() {
        stopShare()
        super.onCleared()
    }

    fun onPasswordChange(value: String) {
        _uiState.value = _uiState.value.copy(password = value.take(128))
    }

    fun onPullAddressChange(value: String) {
        _uiState.value = _uiState.value.copy(pullAddress = value.take(64))
    }

    fun onPullTokenChange(value: String) {
        _uiState.value = _uiState.value.copy(pullToken = value.take(16).trim())
    }

    // ── Источник: собрать копию и раздавать ─────────────────────────────────

    /** Собрать копию этого устройства и поднять одноразовую раздачу. */
    fun startShare() {
        if (_uiState.value.busy || _uiState.value.sharing) return
        val chars = _uiState.value.password.toCharArray()
        _uiState.value = _uiState.value.copy(busy = true, message = null, failed = false)
        viewModelScope.launch {
            val shareFile = withContext(Dispatchers.IO) {
                val temp = File(context.applicationContext.noBackupFilesDir, "profile_share.apubak")
                temp.delete()
                val created = backup.createFile(temp, chars, includeReceived = false)
                if (created is ProfileBackup.CreateResult.Success) temp else null
            }
            chars.fill('\u0000')
            if (shareFile == null) {
                _uiState.value = _uiState.value.copy(
                    busy = false,
                    message = "Копию не собрать: нужен пароль от 8 знаков и созданный профиль",
                    failed = true,
                )
                return@launch
            }
            val ip = withContext(Dispatchers.IO) { ProfileSyncDirect.lanIpv4() }
            if (ip == null) {
                _uiState.value = _uiState.value.copy(
                    busy = false,
                    message = "Нет Wi-Fi сети: подключите оба устройства к одной сети",
                    failed = true,
                )
                return@launch
            }
            val address = ProfileSyncDirect.ShareAddress(
                host = ip,
                port = ProfileSyncDirect.DEFAULT_PORT,
                token = ProfileSyncDirect.newToken(),
            )
            val newServer = ProfileSyncDirect.ShareServer(
                port = address.port,
                token = address.token,
                provideFile = { shareFile.takeIf { it.isFile } },
                onDone = { served, reason ->
                    stopShare()
                    _uiState.value = _uiState.value.copy(
                        message = if (served) "Копия передана" else "Передача не удалась: ${reason ?: "отмена"}",
                        failed = !served,
                    )
                },
            )
            server = newServer
            newServer.start()
            _uiState.value = _uiState.value.copy(
                busy = false,
                sharing = true,
                shareAddress = address,
                message = "Копия готова: отдайте адрес и код устройству-приёмнику",
                failed = false,
            )
        }
    }

    fun stopShare() {
        server?.stop()
        server = null
        _uiState.value = _uiState.value.copy(sharing = false, shareAddress = null)
    }

    // ── Приёмник: забрать копию напрямую ────────────────────────────────────

    /** Забрать копию по адресу и коду, открыть паролем и подготовить. */
    fun pullAndStage() {
        if (_uiState.value.busy) return
        val address = _uiState.value.pullAddress.trim().removePrefix("http://").removePrefix("https://")
        val token = _uiState.value.pullToken.trim()
        val host = address.substringBefore(':')
        val port = address.substringAfter(':', "").toIntOrNull() ?: 0
        if (host.isBlank() || port !in 1..65535 || token.length < 4) {
            _uiState.value = _uiState.value.copy(
                message = "Введите адрес вида 192.168.1.5:48126 и код с экрана телефона",
                failed = true,
            )
            return
        }
        val chars = _uiState.value.password.toCharArray()
        _uiState.value = _uiState.value.copy(busy = true, message = null, failed = false)
        viewModelScope.launch {
            val dest = File(context.applicationContext.noBackupFilesDir, "profile_direct.apubak")
            dest.delete()
            val pulled = withContext(Dispatchers.IO) {
                ProfileSyncDirect.pull(host, port, token, dest)
            }
            if (!pulled) {
                chars.fill('\u0000')
                _uiState.value = _uiState.value.copy(
                    busy = false,
                    message = "Не удалось забрать копию: проверьте адрес, код и что оба устройства в одной сети Wi-Fi",
                    failed = true,
                )
                return@launch
            }
            stageDownloaded(Uri.fromFile(dest), chars)
            chars.fill('\u0000')
        }
    }

    private suspend fun stageDownloaded(source: Uri, chars: CharArray) {
        val staged = withContext(Dispatchers.IO) { backup.stage(source, chars) }
        when (staged) {
            is ProfileBackup.StageResult.Ready -> {
                _uiState.value = _uiState.value.copy(
                    busy = false, staged = staged.manifest, message = null, failed = false,
                )
            }
            is ProfileBackup.StageResult.WrongPassword -> {
                backup.discardStaged()
                _uiState.value = _uiState.value.copy(
                    busy = false, message = "Неверный пароль копии", failed = true,
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
                    busy = false, message = "Копию не удалось открыть", failed = true,
                )
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
