package com.vladimir.messenger.ui.screens.settings

// =============================================================================
// PROFILESYNCVIEWMODEL.KT — разовый перенос профиля
// =============================================================================
// Раунд 225: профили находят себя сами (полка по нику, код - из пароля) и
// сами синхронизируются (опрос, авто-скачивание, уведомление). Два пути:
//  - «через сеть APU» - любые сети, включая мобильные (одноразовый релей);
//  - «по Wi-Fi напрямую» - без интернета (раунд 224).
// Применение копии - всегда вручную (профиль молча не подменяем).
// =============================================================================

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.data.backup.BackupCipher
import com.vladimir.messenger.data.backup.BackupManifest
import com.vladimir.messenger.data.backup.ProfileBackup
import com.vladimir.messenger.data.backup.ProfileSyncAuto
import com.vladimir.messenger.data.backup.ProfileSyncDirect
import com.vladimir.messenger.data.backup.ProfileSyncNet
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Состояние окна синхронизации. */
data class ProfileSyncUiState(
    val busy: Boolean = false,
    val password: String = "",
    /** Раунд 256: пароль подставлен из входа - человеку вводить нечего. */
    val passwordAuto: Boolean = false,
    /** Режим источника: сервер раздачи поднят (Wi-Fi напрямую). */
    val sharing: Boolean = false,
    val shareAddress: ProfileSyncDirect.ShareAddress? = null,
    /** Поля приёмника (Wi-Fi напрямую). */
    val pullAddress: String = "",
    val pullToken: String = "",
    /** «Через сеть APU»: что сейчас видно в релее. */
    val netChecking: Boolean = false,
    val netMeta: ProfileSyncNet.NetMeta? = null,
    val netMessage: String? = null,
    val netFailed: Boolean = false,
    val netSent: Boolean = false,
    /** Авто-проверка по расписанию. */
    val autoEnabled: Boolean = false,
    /** Идентификатор этой установки; nodeId оставлен для старых меток копии. */
    val myDeviceId: String = "",
    val myAccountNodeId: String = "",
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
    private var pollJob: Job? = null

    init {
        // Раунд 256: пароль входа был заперт при входе - подставляем сам,
        // чтобы «один флажок - и всё само» работало без повторного ввода.
        val stored = ProfileSyncAuto.unwrap(context)
        _uiState.value = _uiState.value.copy(
            autoEnabled = ProfileSyncAuto.isEnabled(context),
            myDeviceId = ProfileSyncNet.deviceIdOf(context),
            myAccountNodeId = ProfileSyncNet.accountNodeIdOf(context),
            password = stored?.let { String(it) }.orEmpty(),
            passwordAuto = stored != null,
        )
        stored?.fill(0.toChar())
        // «Сами синхронизировались»: пока окно открыто - сами ищем копию,
        // сами скачиваем и готовим. Применение - одним тапом человека.
        pollJob = viewModelScope.launch {
            while (isActive) {
                pollNet()
                delay(15_000)
            }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        stopShare()
        super.onCleared()
    }

    fun onPasswordChange(value: String) {
        _uiState.value = _uiState.value.copy(password = value.take(128), passwordAuto = false)
        // Пароль изменился - старый результат авто-поиска мог быть от другого пароля.
        if (_uiState.value.staged != null && value.length < BackupCipher.MIN_PASSWORD_LENGTH) {
            _uiState.value = _uiState.value.copy(staged = null)
        }
    }

    fun onPullAddressChange(value: String) {
        _uiState.value = _uiState.value.copy(pullAddress = value.take(64))
    }

    fun onPullTokenChange(value: String) {
        _uiState.value = _uiState.value.copy(pullToken = value.take(16).trim())
    }

    private fun hasPassword(): Boolean =
        _uiState.value.password.length >= BackupCipher.MIN_PASSWORD_LENGTH

    // ── «Через сеть APU»: нашли сами, скачали сами ──────────────────────────

    /** Разовая проверка релея (иначе - по циклу раз в 15 с). */
    fun pollNet() {
        if (_uiState.value.busy || _uiState.value.staged != null) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(netChecking = true)
            val meta = ProfileSyncNet.meta(context)
            _uiState.value = _uiState.value.copy(netChecking = false, netMeta = meta)
            val foreign = ProfileSyncNet.isForeignDevice(
                meta,
                _uiState.value.myDeviceId,
                _uiState.value.myAccountNodeId,
            )
            if (foreign && hasPassword()) {
                netFetchAndStage() // чужая копия: сами качаем и готовим
            }
        }
    }

    /** Отправить копию этого устройства в сеть APU (одноразово, до забора). */
    fun netUpload() {
        if (_uiState.value.busy) return
        val chars = _uiState.value.password.toCharArray()
        _uiState.value = _uiState.value.copy(busy = true, netMessage = null, netFailed = false)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                ProfileSyncNet.uploadBlocking(context, backup, chars)
            }
            // Раунд 256: пароль сработал - запираем для автоподстановки
            // (до обнуления массива!).
            if (result is ProfileSyncNet.UploadResult.Ok) {
                ProfileSyncAuto.rememberPassword(context, chars)
            }
            chars.fill('\u0000')
            val (message, failed) = when (result) {
                is ProfileSyncNet.UploadResult.Ok -> {
                    _uiState.value = _uiState.value.copy(netSent = true)
                    "Копия в сети APU: второе устройство с тем же ником и паролем " +
                        "найдёт и заберёт её само. Копия исчезнет после забора " +
                        "(или через сутки)." to false
                }
                ProfileSyncNet.UploadResult.NoIdentity ->
                    "Сначала создайте профиль" to true
                ProfileSyncNet.UploadResult.BadPassword ->
                    "Пароль короткий - нужно минимум ${BackupCipher.MIN_PASSWORD_LENGTH} знаков" to true
                is ProfileSyncNet.UploadResult.TooBig ->
                    "Копия ${result.megaBytes} МБ - больше лимита сети APU (24 МБ без медиа)" to true
                is ProfileSyncNet.UploadResult.Failed ->
                    "Не удалось отправить: ${result.reason}" to true
            }
            _uiState.value = _uiState.value.copy(busy = false, netMessage = message, netFailed = failed)
        }
    }

    /** Найти копию в релее и подготовить (используется и опросом, и кнопкой). */
    fun netFetchAndStage() {
        if (_uiState.value.busy) return
        val chars = _uiState.value.password.toCharArray()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true, netMessage = null, netFailed = false)
            when (val fetched = ProfileSyncNet.fetch(context, chars)) {
                is ProfileSyncNet.FetchResult.Ready -> {
                    ProfileSyncAuto.noteFetchedShelf(context)
                    stageDownloaded(Uri.fromFile(fetched.file), chars)
                }
                ProfileSyncNet.FetchResult.NotFound -> {
                    _uiState.value = _uiState.value.copy(
                        busy = false,
                        netMessage = "В сети APU копии нет - отправьте её с телефона с данными",
                        netFailed = true,
                    )
                }
                ProfileSyncNet.FetchResult.WrongPassword -> {
                    _uiState.value = _uiState.value.copy(
                        busy = false,
                        netMessage = "Копия защищена другим паролем - введите пароль с основного устройства",
                        netFailed = true,
                    )
                }
                is ProfileSyncNet.FetchResult.Failed -> {
                    _uiState.value = _uiState.value.copy(
                        busy = false,
                        netMessage = "Не удалось забрать: ${fetched.reason}",
                        netFailed = true,
                    )
                }
            }
            chars.fill('\u0000')
        }
    }

    /** Включить/выключить фоновую авто-проверку (раз в ~6 ч, с уведомлением). */
    fun setAutoEnabled(enabled: Boolean) {
        if (enabled) {
            val chars = _uiState.value.password.toCharArray()
            val ok = ProfileSyncAuto.enable(context, chars)
            chars.fill('\u0000')
            _uiState.value = _uiState.value.copy(
                autoEnabled = ok,
                netMessage = if (ok) "Автопоиск включён: телефон проверяет сам в фоне. " +
                    "Подтверждение понадобится только перед заменой данных."
                else "Один раз введите пароль из «Защиты личности» (не короче " +
                    "${BackupCipher.MIN_PASSWORD_LENGTH} знаков); он хранится только в защищённом хранилище телефона.",
                netFailed = !ok,
            )
        } else {
            ProfileSyncAuto.disable(context)
            _uiState.value = _uiState.value.copy(
                autoEnabled = false,
                netMessage = "Авто-проверка выключена",
                netFailed = false,
            )
        }
    }

    // ── Wi-Fi напрямую (раунд 224) ──────────────────────────────────────────

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

    /** Забрать копию по адресу и коду (Wi-Fi напрямую). */
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

    // ── Общий финал: подготовка и применение ────────────────────────────────

    private suspend fun stageDownloaded(source: Uri, chars: CharArray) {
        val staged = withContext(Dispatchers.IO) { backup.stage(source, chars) }
        when (staged) {
            is ProfileBackup.StageResult.Ready -> {
                // Раунд 256: пароль подошёл к копии - запираем его для
                // автоподстановки, чтобы дальше всё работало без ввода.
                ProfileSyncAuto.rememberPassword(context, chars)
                _uiState.value = _uiState.value.copy(
                    busy = false, staged = staged.manifest, message = null, failed = false,
                    passwordAuto = true,
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
            // Копия применена: авто-режим запоминает отпечаток и не дёргает одинаковым.
            ProfileSyncAuto.markApplied(context, _uiState.value.staged?.dataFp ?: "")
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
