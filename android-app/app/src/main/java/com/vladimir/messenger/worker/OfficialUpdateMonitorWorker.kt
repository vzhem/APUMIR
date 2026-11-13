package com.vladimir.messenger.worker

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vladimir.messenger.data.update.ApkSeeder
import com.vladimir.messenger.service.OfficialDownloadResult
import com.vladimir.messenger.service.OfficialUpdateDownloads
import com.vladimir.messenger.service.UpdateChecker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** DownloadManager can pause before a final failure and outlive our process. */
internal object OfficialUpdateMonitor {
    private const val KEY_ID = "id"
    private const val KEY_ONCE = "once"
    private const val TAG = "OfficialUpdateMonitor"

    fun watch(context: Context, id: Long) = schedule(context, id, once = false)
    fun completed(context: Context, id: Long) = schedule(context, id, once = true)

    /** Also recover a missed completion or an interrupted source transition after process death. */
    fun restore(context: Context) {
        val id = OfficialUpdateDownloads.trackedId(context) ?: return
        completed(context, id)
        watch(context, id)
    }

    private fun schedule(context: Context, id: Long, once: Boolean) {
        if (id < 0L) return
        try {
            val builder = OneTimeWorkRequestBuilder<OfficialUpdateMonitorWorker>()
                .setInputData(workDataOf(KEY_ID to id, KEY_ONCE to once))
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
            if (!once) {
                builder.setInitialDelay(15, TimeUnit.SECONDS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            }
            val name = "apu_official_update_${if (once) "complete" else "watch"}_$id"
            WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, builder.build())
        } catch (error: Exception) {
            // A completion broadcast or the next app start can schedule it again.
            Log.w(TAG, "Could not schedule update monitor: ${error.message}")
        }
    }

    fun id(params: WorkerParameters): Long = params.inputData.getLong(KEY_ID, -1L)
    fun once(params: WorkerParameters): Boolean = params.inputData.getBoolean(KEY_ONCE, false)
}

@HiltWorker
class OfficialUpdateMonitorWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted private val params: WorkerParameters,
    private val updateChecker: UpdateChecker,
    private val apkSeeder: Provider<ApkSeeder>,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = OfficialUpdateMonitor.id(params)
        if (id < 0L) return Result.success()
        return try {
            val outcome = withContext(Dispatchers.IO) { updateChecker.reconcileOfficialDownload(id) }
            if (outcome == OfficialDownloadResult.VERIFIED) {
                // The seeder must not see a managed APK before validation.
                apkSeeder.get().adoptCompletedDownloads()
            }
            if (outcome == OfficialDownloadResult.ACTIVE && !OfficialUpdateMonitor.once(params)) {
                Result.retry()
            } else Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w("OfficialUpdateMonitor", "Update monitoring failed: ${error.message}")
            Result.retry()
        }
    }
}

/** System completion can start this receiver even when the app process is gone. */
class OfficialUpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (!OfficialUpdateDownloads.isTracked(context, id)) return
        OfficialUpdateMonitor.completed(context, id)
    }
}
