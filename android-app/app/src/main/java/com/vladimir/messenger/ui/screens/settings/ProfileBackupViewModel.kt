package com.vladimir.messenger.ui.screens.settings

import com.vladimir.messenger.R
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.data.backup.BackupCipher
import com.vladimir.messenger.data.backup.BackupManifest
import com.vladimir.messenger.data.backup.BackupSchedule
import com.vladimir.messenger.data.backup.ProfileBackup
import com.vladimir.messenger.data.swarm.StorageSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class BusyKind { NONE, BUILDING, OPENING, ENABLING }

data class ProfileBackupUiState(
    val busy: Boolean = false,
    /** Что сейчас делаем - подпись под индикатором. */
    val busyText: String = "",
    /** Код текущего действия: экран смотрит на него, а не на текст (текст зависит от языка). */
    val busyKind: BusyKind = BusyKind.NONE,
    val message: String? = null,
    val failed: Boolean = false,
    /** Сколько весят полученные файлы - чтобы человек решил, класть ли их в копию. */
    val receivedBytes: Long = 0L,
    /** Подготовленная копия ждёт перезапуска. */
    val stagedManifest: BackupManifest? = null,
    /** Профиль на этом телефоне есть (иначе экран открыт с первого экрана - только восстановление). */
    val hasIdentity: Boolean = true,
    /** Подтверждено: приложение закрывается, копия применится при следующем запуске. */
    val restarting: Boolean = false,
    /** Файл, только что записанный руками: в него можно включить автообновление. */
    val lastSaved: Uri? = null,
    /** Расписание автообновления (что включено, куда, когда в последний раз). */
    val schedule: BackupSchedule.State? = null,
    /**
     * Раунд 250: копии, найденные на телефоне автоматически (по сохранённым
     * разрешениям SAF и файлу автообновления). Экран предлагает их первыми,
     * чтобы не рыскать по проводнику.
     */
    val foundBackups: List<FoundBackup> = emptyList(),
)

/** Найденная автоматически копия профиля: uri + человекочитаемое имя и вес. */
data class FoundBackup(val uri: Uri, val name: String, val size: Long)

/**
 * Экран «Резервная копия»: создать файл и восстановиться из файла.
 *
 * Сами файлы выбирает система (SAF), поэтому разрешений на хранилище не
 * нужно, а человек волен положить копию хоть на флешку, хоть в облако.
 */
@HiltViewModel
class ProfileBackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backup: ProfileBackup,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileBackupUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val received = runCatching { StorageSettings.usage(context).receivedBytes }.getOrDefault(0L)
            val staged = if (backup.hasStaged()) backup.stagedManifest() else null
            val hasIdentity = context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
                .getBoolean("identity_created", false)
            val schedule = BackupSchedule.state(context)
            _uiState.update {
                it.copy(receivedBytes = received, stagedManifest = staged, hasIdentity = hasIdentity, schedule = schedule)
            }
            // Раунд 250: сразу ищем сохранённые копии - восстановление начнёт с них.
            _uiState.update { it.copy(foundBackups = scanFoundBackups()) }
        }
    }

    /** Перечитать расписание (после возврата на экран, когда задача могла отработать). */
    fun refreshSchedule() {
        viewModelScope.launch(Dispatchers.IO) {
            val schedule = BackupSchedule.state(context)
            _uiState.update { it.copy(schedule = schedule) }
        }
    }

    /**
     * Раунд 250: автопоиск сохранённых копий - «Восстановить» начинает с них,
     * а не с проводника. Кандидаты: файл автообновления и все документы, на
     * которые у приложения осталось разрешение SAF (сохранённые при создании
     * и открытии копии). Каждый проверяем по магии APUBAK - в подборку не
     * попадает постороннее.
     */
    fun refreshFoundBackups() {
        viewModelScope.launch(Dispatchers.IO) {
            val found = scanFoundBackups()
            _uiState.update { it.copy(foundBackups = found) }
        }
    }

    private fun scanFoundBackups(): List<FoundBackup> {
        val cr = context.contentResolver
        val candidates = LinkedHashSet<Uri>()
        runCatching { BackupSchedule.targetUri(context) }.getOrNull()?.let { candidates.add(it) }
        runCatching {
            cr.persistedUriPermissions.forEach { p ->
                if (p.isReadPermission && p.uri.scheme == "content") candidates.add(p.uri)
            }
        }
        val found = mutableListOf<FoundBackup>()
        for (uri in candidates) {
            runCatching {
                if (!isApuBackup(cr, uri)) return@runCatching
                var name = "APU backup"
                var size = 0L
                cr.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null, null, null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0) ?: name
                        size = if (c.isNull(1)) 0L else c.getLong(1)
                    }
                }
                found.add(FoundBackup(uri, name, size))
            }
        }
        // Свежие по имени первыми: в имени штамп yyyy-MM-dd_HH-mm.
        return found.sortedByDescending { it.name }
    }

    /** Магия файла: первые байты APUBAK. Читается мало, файл не расшифровывается. */
    private fun isApuBackup(cr: android.content.ContentResolver, uri: Uri): Boolean {
        val head = ByteArray(BackupCipher.MAGIC.length)
        var read = 0
        cr.openInputStream(uri)?.use { ins ->
            while (read < head.size) {
                val n = ins.read(head, read, head.size - read)
                if (n <= 0) break
                read += n
            }
        } ?: return false
        return read == head.size && String(head, Charsets.US_ASCII) == BackupCipher.MAGIC
    }

    /** Имя файла по умолчанию для диалога сохранения. */
    fun suggestedFileName(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        val nick = context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
            .getString("my_username", null)?.takeIf { it.isNotBlank() }?.let { "_$it" } ?: ""
        return "APU$nick" + "_$stamp.apubak"
    }

    fun create(target: Uri, password: String, includeReceived: Boolean) {
        if (_uiState.value.busy) return
        if (password.length < BackupCipher.MIN_PASSWORD_LENGTH) {
            fail(context.getString(R.string.bk_pw_short, BackupCipher.MIN_PASSWORD_LENGTH))
            return
        }
        _uiState.update { it.copy(busy = true, busyKind = BusyKind.BUILDING, busyText = context.getString(R.string.bk_building), message = null, failed = false) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                backup.create(target, password.toCharArray(), includeReceived)
            }
            when (result) {
                is ProfileBackup.CreateResult.Success -> {
                    // Расписание уже смотрит в этот файл - обновляем сведения о последней записи.
                    val schedule = withContext(Dispatchers.IO) {
                        if (BackupSchedule.state(context).enabled && BackupSchedule.targetUri(context) == target) {
                            BackupSchedule.recordSuccess(context, result.bytes)
                        }
                        BackupSchedule.state(context)
                    }
                    _uiState.update {
                        it.copy(
                            busy = false,
                            message = if (result.receivedFiles > 0) context.getString(R.string.bk_saved_files, humanBytes(result.bytes), result.receivedFiles) else context.getString(R.string.bk_saved, humanBytes(result.bytes)),
                            failed = false,
                            lastSaved = target,
                            schedule = schedule,
                        )
                    }
                }
                ProfileBackup.CreateResult.NoIdentity -> fail(context.getString(R.string.bk_no_identity))
                ProfileBackup.CreateResult.BadPassword -> fail(context.getString(R.string.bk_pw_short, BackupCipher.MIN_PASSWORD_LENGTH))
                is ProfileBackup.CreateResult.Failed -> fail(context.getString(R.string.bk_write_failed, result.reason))
            }
        }
    }

    fun stage(source: Uri, password: String) {
        if (_uiState.value.busy) return
        if (password.isEmpty()) {
            fail(context.getString(R.string.bk_enter_pw))
            return
        }
        _uiState.update { it.copy(busy = true, busyKind = BusyKind.OPENING, busyText = context.getString(R.string.bk_opening), message = null, failed = false) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { backup.stage(source, password.toCharArray()) }
            when (result) {
                is ProfileBackup.StageResult.Ready -> _uiState.update {
                    it.copy(
                        busy = false,
                        stagedManifest = result.manifest,
                        message = null,
                        failed = false,
                    )
                }
                ProfileBackup.StageResult.WrongPassword -> fail(context.getString(R.string.bk_wrong_pw))
                ProfileBackup.StageResult.NotBackupFile -> fail(context.getString(R.string.bk_not_backup))
                is ProfileBackup.StageResult.TooNew -> fail(
                    if (result.appVersion.isNotBlank()) context.getString(R.string.bk_too_new_ver, result.appVersion) else context.getString(R.string.bk_too_new),
                )
                ProfileBackup.StageResult.Truncated -> fail(context.getString(R.string.bk_truncated))
                is ProfileBackup.StageResult.Failed -> fail(context.getString(R.string.bk_read_failed, result.reason))
            }
        }
    }

    /** Включить автообновление только что сохранённого файла тем же паролем. */
    fun enableAutoUpdate(password: String, includeReceived: Boolean, period: BackupSchedule.Period) {
        val target = _uiState.value.lastSaved ?: run {
            fail(context.getString(R.string.bk_save_first))
            return
        }
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, busyKind = BusyKind.ENABLING, busyText = context.getString(R.string.bk_enabling), message = null, failed = false) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                BackupSchedule.enable(context, target, password.toCharArray(), includeReceived, period)
            }
            val schedule = withContext(Dispatchers.IO) { BackupSchedule.state(context) }
            when (result) {
                BackupSchedule.EnableResult.Ok -> _uiState.update {
                    it.copy(
                        busy = false,
                        schedule = schedule,
                        message = "Файл будет обновляться ${period.title}. Пароль тот же; " +
                            "он хранится в защищённом хранилище телефона.",
                        failed = false,
                    )
                }
                BackupSchedule.EnableResult.NoPersistentAccess -> _uiState.update {
                    it.copy(
                        busy = false,
                        schedule = schedule,
                        message = context.getString(R.string.bk_storage_no_access),
                        failed = true,
                    )
                }
                is BackupSchedule.EnableResult.Failed -> _uiState.update {
                    it.copy(busy = false, schedule = schedule, message = context.getString(R.string.bk_enable_failed, result.reason), failed = true)
                }
            }
        }
    }

    /**
     * Раунд 251: «флажок» автообновления для остановленного расписания
     * (например, после восстановления профиля): включить заново на уже
     * существующем файле - найденном на телефоне или выбранном в проводнике.
     * Пароль - от самого файла; он заворачивается ключом телефона.
     */
    fun enableAutoUpdateFor(target: Uri, password: String, includeReceived: Boolean, period: BackupSchedule.Period) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, busyKind = BusyKind.ENABLING, busyText = context.getString(R.string.bk_enabling), message = null, failed = false) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                BackupSchedule.enable(context, target, password.toCharArray(), includeReceived, period)
            }
            val schedule = withContext(Dispatchers.IO) { BackupSchedule.state(context) }
            when (result) {
                BackupSchedule.EnableResult.Ok -> _uiState.update {
                    it.copy(
                        busy = false,
                        schedule = schedule,
                        message = "Автообновление включено: файл будет перезаписываться ${period.title}.",
                        failed = false,
                    )
                }
                BackupSchedule.EnableResult.NoPersistentAccess -> _uiState.update {
                    it.copy(
                        busy = false,
                        schedule = schedule,
                        message = context.getString(R.string.bk_storage_no_access),
                        failed = true,
                    )
                }
                is BackupSchedule.EnableResult.Failed -> _uiState.update {
                    it.copy(busy = false, schedule = schedule, message = context.getString(R.string.bk_enable_failed, result.reason), failed = true)
                }
            }
        }
    }

    fun setAutoPeriod(period: BackupSchedule.Period) {
        viewModelScope.launch(Dispatchers.IO) {
            BackupSchedule.setPeriod(context, period)
            val schedule = BackupSchedule.state(context)
            _uiState.update { it.copy(schedule = schedule) }
        }
    }

    fun disableAutoUpdate() {
        viewModelScope.launch(Dispatchers.IO) {
            BackupSchedule.disable(context)
            val schedule = BackupSchedule.state(context)
            _uiState.update { it.copy(schedule = schedule, message = context.getString(R.string.bk_auto_off), failed = false) }
        }
    }

    /** Обновить файл сейчас (в фоне, той же задачей, что и по расписанию). */
    fun runAutoNow() {
        viewModelScope.launch(Dispatchers.IO) {
            BackupSchedule.runNow(context)
            _uiState.update { it.copy(message = context.getString(R.string.bk_update_bg), failed = false) }
        }
    }

    fun discardStaged() {
        viewModelScope.launch(Dispatchers.IO) {
            backup.discardStaged()
            _uiState.update { it.copy(stagedManifest = null, message = null, failed = false) }
        }
    }

    /**
     * Подтвердить восстановление и закрыть приложение. Копия применяется в
     * `MessengerApplication.onCreate` при следующем запуске - до Room и сервисов,
     * пока базу никто не держит. Сервис останавливаем явно, чтобы он не
     * пережил процесс и не был перезапущен системой со старым состоянием.
     * Автоматически перезапустить себя приложение на новых Android не может
     * (запуск из фона запрещён), поэтому честно просим открыть его снова.
     */
    fun confirmAndExit(onExit: () -> Unit) {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { backup.confirmStaged() }
            if (!ok) {
                fail(context.getString(R.string.bk_staged_missing))
                return@launch
            }
            _uiState.update { it.copy(restarting = true, message = null, failed = false) }
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

    private fun fail(text: String) {
        _uiState.update { it.copy(busy = false, message = text, failed = true) }
    }

    companion object {
        fun humanBytes(bytes: Long): String = when {
            bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f ГБ", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f МБ", bytes / (1024.0 * 1024))
            bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f КБ", bytes / 1024.0)
            else -> "$bytes Б"
        }
    }
}
