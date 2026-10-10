package com.vladimir.messenger.ui.screens.settings

import com.vladimir.messenger.R
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
    // Раунд 257: релей на бесплатном плане KV имеет дневной лимит записи -
    // авто-забор НЕ повторяем каждые 15 с, а после серверной ошибки берём
    // паузу, чтобы не выжигать квоту.
    private var lastAutoFetchMetaTimeMs = 0L
    private var autoFetchCooldownUntilMs = 0L

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
        // Новый пароль - прежние попытки авто-забора не в счёт.
        lastAutoFetchMetaTimeMs = 0L
        autoFetchCooldownUntilMs = 0L
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
                // Раунд 257: одну и ту же копию авто-забором не дёргаем
                // (каждый успешный забор = записи в KV релея, там дневной
                // лимит); после серверной ошибки - пауза 5 минут.
                val metaTime = meta?.timeMs ?: 0L
                val now = System.currentTimeMillis()
                if (metaTime != lastAutoFetchMetaTimeMs && now >= autoFetchCooldownUntilMs) {
                    lastAutoFetchMetaTimeMs = metaTime
                    netFetchAndStage() // чужая копия: сами качаем и готовим
                }
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
                    context.getString(R.string.ps_net_copy_info) to false
                }
                ProfileSyncNet.UploadResult.NoIdentity ->
                    context.getString(R.string.ps_create_profile_first) to true
                ProfileSyncNet.UploadResult.BadPassword ->
                    context.getString(R.string.ps_pw_short, BackupCipher.MIN_PASSWORD_LENGTH) to true
                is ProfileSyncNet.UploadResult.TooBig ->
                    context.getString(R.string.ps_too_big, result.megaBytes) to true
                is ProfileSyncNet.UploadResult.Failed ->
                    context.getString(R.string.ps_send_failed, result.reason) to true
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
                        netMessage = context.getString(R.string.ps_none_on_net),
                        netFailed = true,
                    )
                }
                ProfileSyncNet.FetchResult.WrongPassword -> {
                    _uiState.value = _uiState.value.copy(
                        busy = false,
                        netMessage = context.getString(R.string.ps_other_pw),
                        netFailed = true,
                    )
                }
                is ProfileSyncNet.FetchResult.Failed -> {
                    // Раунд 257: серверная ошибка (например, дневной лимит KV) -
                    // авто-забор ставим на паузу, квоту не выжигаем.
                    autoFetchCooldownUntilMs = System.currentTimeMillis() + 5 * 60 * 1000L
                    lastAutoFetchMetaTimeMs = 0L
                    _uiState.value = _uiState.value.copy(
                        busy = false,
                        netMessage = context.getString(R.string.ps_fetch_failed, fetched.reason),
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
                netMessage = if (ok) context.getString(R.string.ps_autosearch_on)
                else "Один раз введите пароль из «Защиты личности» (не короче " +
                    "${BackupCipher.MIN_PASSWORD_LENGTH} знаков); он хранится только в защищённом хранилище телефона.",
                netFailed = !ok,
            )
        } else {
            ProfileSyncAuto.disable(context)
            _uiState.value = _uiState.value.copy(
                autoEnabled = false,
                netMessage = context.getString(R.string.ps_auto_off),
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
                    message = context.getString(R.string.ps_cant_build),
                    failed = true,
                )
                return@launch
            }
            val ip = withContext(Dispatchers.IO) { ProfileSyncDirect.lanIpv4() }
            if (ip == null) {
                _uiState.value = _uiState.value.copy(
                    busy = false,
                    message = context.getString(R.string.ps_no_wifi),
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
                        message = if (served) context.getString(R.string.sync_copy_handed) else context.getString(R.string.sync_transfer_failed, reason ?: context.getString(R.string.sync_cancelled)),
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
                message = context.getString(R.string.ps_ready),
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
                message = context.getString(R.string.ps_enter_addr),
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
                    message = context.getString(R.string.ps_fetch_check),
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
                    busy = false, message = context.getString(R.string.ps_wrong_pw), failed = true,
                )
            }
            is ProfileBackup.StageResult.TooNew -> {
                backup.discardStaged()
                _uiState.value = _uiState.value.copy(
                    busy = false,
                    message = context.getString(R.string.sync_newer_app, staged.appVersion),
                    failed = true,
                )
            }
            else -> {
                backup.discardStaged()
                _uiState.value = _uiState.value.copy(
                    busy = false, message = context.getString(R.string.ps_cant_open), failed = true,
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
                    message = context.getString(R.string.ps_staged_missing),
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
