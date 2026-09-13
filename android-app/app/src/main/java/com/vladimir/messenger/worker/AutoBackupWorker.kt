package com.vladimir.messenger.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vladimir.messenger.MainActivity
import com.vladimir.messenger.MessengerApplication
import com.vladimir.messenger.R
import com.vladimir.messenger.data.backup.BackupSchedule
import com.vladimir.messenger.data.backup.ProfileBackup
import com.vladimir.messenger.data.swarm.StorageSettings
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AutoBackupEntryPoint {
    fun profileBackup(): ProfileBackup
}

/**
 * Обновление резервной копии по расписанию ([BackupSchedule]).
 *
 * Пишем в два приёма, чтобы единственная копия человека не пострадала от
 * обрыва посреди долгой работы (система останавливает фоновую задачу через
 * 10 минут, телефон может выключиться):
 *  1. полная копия собирается в служебный файл в закрытой папке приложения
 *     (долго: снимок базы, сжатие, шифрование); рядом ставится метка «готово»;
 *  2. готовый файл переливается в файл человека (быстро). Если оборвало на
 *     этом шаге, следующая попытка не собирает копию заново, а повторяет
 *     только перелив из служебного файла.
 * Места под служебный файл нет - пишем сразу в файл человека (риск обрыва
 * остаётся, но копия хоть какая-то будет; система и так не запускает нас при
 * почти полном хранилище).
 *
 * Обычный Worker без Hilt-фабрики: зависимость берётся через EntryPoint, как
 * в MainActivity, - так задача не зависит от настройки WorkManager.
 */
class AutoBackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext
        val state = BackupSchedule.state(app)
        if (!state.enabled) {
            Log.i(TAG, "auto backup: disabled, nothing to do")
            return@withContext Result.success()
        }
        val target = BackupSchedule.targetUri(app)
        if (target == null) {
            stop(app, "файл копии не задан — сохраните копию заново и включите обновление")
            return@withContext Result.success()
        }
        val prefs = app.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
        val nodeId = prefs.getString("node_id", null) ?: prefs.getString("existing_public_key", null)
        if (nodeId == null || !prefs.getBoolean("identity_created", false)) {
            stop(app, "профиль на этом телефоне не найден")
            return@withContext Result.success()
        }
        val bound = BackupSchedule.boundNodeId(app)
        if (bound != null && bound != nodeId) {
            // Восстановили другой профиль: его копию в чужой файл не пишем.
            stop(app, "на телефоне теперь другой профиль — включите обновление заново")
            return@withContext Result.success()
        }
        if (!BackupSchedule.hasWriteAccess(app, target)) {
            stop(app, "доступ к файлу копии потерян (файл удалён или перемещён) — сохраните копию заново")
            return@withContext Result.success()
        }
        val password = BackupSchedule.password(app)
        if (password == null) {
            stop(app, "пароль копии не удалось прочитать из защищённого хранилища — включите обновление заново")
            return@withContext Result.success()
        }

        BackupSchedule.recordAttempt(app)
        val backup = EntryPointAccessors.fromApplication(app, AutoBackupEntryPoint::class.java).profileBackup()
        try {
            val staging = File(app.noBackupFilesDir, STAGING_DIR).apply { mkdirs() }
            val pending = File(staging, PENDING_NAME)
            val done = File(staging, DONE_NAME)
            val reuse = pending.isFile && done.isFile &&
                System.currentTimeMillis() - done.lastModified() < REUSE_WINDOW_MS

            if (!reuse) {
                pending.delete()
                done.delete()
                val needed = backup.estimateBytes(state.includeReceived) + SPARE_BYTES
                val free = StorageSettings.freeBytes(app)
                if (free in 1 until needed) {
                    // Служебного файла не поместить: пишем напрямую.
                    Log.w(TAG, "auto backup: low space (free=$free need=$needed), writing directly")
                    return@withContext finish(app, backup.create(target, password, state.includeReceived))
                }
                when (val built = backup.createFile(pending, password, state.includeReceived)) {
                    is ProfileBackup.CreateResult.Success -> done.writeText(built.bytes.toString())
                    else -> {
                        pending.delete()
                        return@withContext finish(app, built)
                    }
                }
            } else {
                Log.i(TAG, "auto backup: reusing prepared file from previous attempt")
            }

            val bytes = try {
                backup.copyFileTo(pending, target)
            } catch (e: Exception) {
                Log.w(TAG, "auto backup: copy to target failed: ${e.javaClass.simpleName}: ${e.message}")
                BackupSchedule.recordFailure(app, "не удалось записать в файл: ${e.message ?: e.javaClass.simpleName}")
                return@withContext retryOrGiveUp()
            }
            pending.delete()
            done.delete()
            BackupSchedule.recordSuccess(app, bytes)
            Log.i(TAG, "auto backup: updated, $bytes bytes, period=${state.period.name}")
            Result.success()
        } finally {
            password.fill('\u0000')
        }
    }

    private fun finish(app: Context, result: ProfileBackup.CreateResult): Result = when (result) {
        is ProfileBackup.CreateResult.Success -> {
            BackupSchedule.recordSuccess(app, result.bytes)
            Log.i(TAG, "auto backup: written directly, ${result.bytes} bytes")
            Result.success()
        }
        ProfileBackup.CreateResult.NoIdentity -> {
            stop(app, "профиль на этом телефоне не найден")
            Result.success()
        }
        ProfileBackup.CreateResult.BadPassword -> {
            stop(app, "сохранённый пароль не годится — включите обновление заново")
            Result.success()
        }
        is ProfileBackup.CreateResult.Failed -> {
            BackupSchedule.recordFailure(app, result.reason)
            Log.w(TAG, "auto backup failed: ${result.reason}")
            retryOrGiveUp()
        }
    }

    /** Несколько повторов с нарастающей паузой; дальше ждём следующего срока. */
    private fun retryOrGiveUp(): Result =
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()

    private fun stop(app: Context, reason: String) {
        BackupSchedule.disableWithError(app, reason)
        notifyStopped(app, reason)
    }

    /** Молча умершее автообновление хуже любого уведомления: говорим один раз, почему остановились. */
    private fun notifyStopped(app: Context, reason: String) {
        runCatching {
            val intent = Intent(app, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = PendingIntent.getActivity(
                app, NOTIFICATION_ID, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(app, MessengerApplication.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Автообновление резервной копии остановлено")
                .setContentText(reason)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$reason. Настройки → Безопасность → Резервная копия."))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "notification not shown: ${it.message}") }
    }

    companion object {
        private const val TAG = "AutoBackupWorker"
        private const val STAGING_DIR = "backup_auto"
        private const val PENDING_NAME = "pending.apubak"
        private const val DONE_NAME = "pending.done"
        private const val REUSE_WINDOW_MS = 2L * 60 * 60 * 1000
        private const val SPARE_BYTES = 64L * 1024 * 1024
        private const val MAX_ATTEMPTS = 3
        private const val NOTIFICATION_ID = 4101
    }
}
