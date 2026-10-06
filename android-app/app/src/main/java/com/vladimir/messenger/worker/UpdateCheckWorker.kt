package com.vladimir.messenger.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vladimir.messenger.service.UpdateChecker
import com.vladimir.messenger.service.UpdateNotifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Фоновая проверка выпуска: работает и когда основной экран APU не открыт.
 *
 * WorkManager сам ждёт сеть и переживает перезапуск телефона. Найденный релиз
 * передаётся [UpdateNotifier], который не повторяет системное уведомление для
 * одной и той же версии.
 */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val updateChecker: UpdateChecker,
    private val updateNotifier: UpdateNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val currentVersion = runCatching {
            applicationContext.packageManager
                .getPackageInfo(applicationContext.packageName, 0)
                .versionName
                .orEmpty()
        }.getOrDefault("")
        if (currentVersion.isBlank()) return Result.success()

        return try {
            val release = updateChecker.checkForUpdate(currentVersion)
            if (release != null) {
                updateNotifier.notifyOfficialRelease(release)
                Log.i(TAG, "Update ${release.version} announced by background check")
            }
            Result.success()
        } catch (error: Exception) {
            // UpdateChecker deliberately treats a missing network/release as
            // «nothing found». A genuine unexpected exception gets a retry.
            Log.w(TAG, "Background update check failed: ${error.message}", error)
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "apu_update_check"
        private const val TAG = "UpdateCheckWorker"
    }
}
