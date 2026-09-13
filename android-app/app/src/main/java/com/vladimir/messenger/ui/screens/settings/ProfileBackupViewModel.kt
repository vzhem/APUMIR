package com.vladimir.messenger.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vladimir.messenger.data.backup.BackupCipher
import com.vladimir.messenger.data.backup.BackupManifest
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

data class ProfileBackupUiState(
    val busy: Boolean = false,
    /** Что сейчас делаем - подпись под индикатором. */
    val busyText: String = "",
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
)

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
            _uiState.update { it.copy(receivedBytes = received, stagedManifest = staged, hasIdentity = hasIdentity) }
        }
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
            fail("Пароль не короче ${BackupCipher.MIN_PASSWORD_LENGTH} знаков.")
            return
        }
        _uiState.update { it.copy(busy = true, busyText = "Собираем копию…", message = null, failed = false) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                backup.create(target, password.toCharArray(), includeReceived)
            }
            when (result) {
                is ProfileBackup.CreateResult.Success -> _uiState.update {
                    it.copy(
                        busy = false,
                        message = "Копия сохранена (${humanBytes(result.bytes)}" +
                            (if (result.receivedFiles > 0) ", файлов: ${result.receivedFiles}" else "") +
                            "). Запомните пароль: без него файл не открыть.",
                        failed = false,
                    )
                }
                ProfileBackup.CreateResult.NoIdentity -> fail("Профиль ещё не создан.")
                ProfileBackup.CreateResult.BadPassword -> fail("Пароль не короче ${BackupCipher.MIN_PASSWORD_LENGTH} знаков.")
                is ProfileBackup.CreateResult.Failed -> fail("Не удалось записать копию: ${result.reason}")
            }
        }
    }

    fun stage(source: Uri, password: String) {
        if (_uiState.value.busy) return
        if (password.isEmpty()) {
            fail("Введите пароль от копии.")
            return
        }
        _uiState.update { it.copy(busy = true, busyText = "Открываем копию…", message = null, failed = false) }
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
                ProfileBackup.StageResult.WrongPassword -> fail("Пароль не подошёл.")
                ProfileBackup.StageResult.NotBackupFile -> fail("Это не файл резервной копии APU.")
                is ProfileBackup.StageResult.TooNew -> fail(
                    "Копия сделана более новой версией приложения" +
                        (if (result.appVersion.isNotBlank()) " (${result.appVersion})" else "") +
                        ". Сначала обновите APU.",
                )
                ProfileBackup.StageResult.Truncated -> fail("Файл копии повреждён или скопирован не до конца.")
                is ProfileBackup.StageResult.Failed -> fail("Не удалось прочитать копию: ${result.reason}")
            }
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
                fail("Подготовленная копия не найдена. Выберите файл ещё раз.")
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
