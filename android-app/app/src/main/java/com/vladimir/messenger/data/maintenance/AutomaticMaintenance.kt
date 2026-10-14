package com.vladimir.messenger.data.maintenance

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.vladimir.messenger.data.update.DownloadTrashCleanup
import com.vladimir.messenger.worker.MaintenanceWorker
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** No network requirement, no full cache clear, no user-history/key/backup deletion. */
object AutomaticMaintenance {
    private const val PREFS = "automatic_maintenance_health"
    private val mutex = Mutex()

    fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniquePeriodicWork("apu-storage-maintenance", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MaintenanceWorker>(6, TimeUnit.HOURS)
                .setInitialDelay(15, TimeUnit.MINUTES).build())
        manager.enqueueUniqueWork("apu-storage-maintenance-startup", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MaintenanceWorker>().setInitialDelay(1, TimeUnit.MINUTES).build())
    }

    suspend fun run(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val last = prefs.getLong("last_run_ms", 0)
            if (now >= last && now - last < 10 * 60 * 1000L) return@withLock
            val roots = listOf(
                File(app.cacheDir, "story-camera") to false,
                File(app.cacheDir, "story-source") to false,
                File(app.cacheDir, "shared") to false,
                File(app.noBackupFilesDir, "file_transfers/v1") to true,
            )
            var examined = 0; var deleted = 0; var bytes = 0L; var failed = 0
            for ((root, atomicOnly) in roots) {
                val result = TemporaryFileSweep.sweep(root, now, atomicOnly)
                examined += result.examined; deleted += result.deleted
                bytes += result.freedBytes; failed += result.failed
            }
            // Known top-level import leftovers only; never descend into voice/cache libraries.
            val extra = listOf(
                TemporaryFileSweep.sweep(app.cacheDir, now, depthLimit = 0, acceptName = {
                    (it.startsWith("apu_pick_") && it.endsWith(".apk")) ||
                        (it.startsWith("gif-mirror-") && it.endsWith(".gif"))
                }),
                TemporaryFileSweep.sweep(File(app.noBackupFilesDir, "giflib"), now,
                    depthLimit = 0, acceptName = { it.endsWith(".tmp") }),
                TemporaryFileSweep.sweep(File(app.noBackupFilesDir, "stickers/v1"), now,
                    depthLimit = 0, acceptName = { it.endsWith(".tmp") }),
            )
            for (result in extra) {
                examined += result.examined; deleted += result.deleted
                bytes += result.freedBytes; failed += result.failed
            }
            runCatching { DownloadTrashCleanup.clean(app) }.onFailure { failed++ }
            runCatching { DownloadTrashCleanup.cleanOldApks(app) }.onFailure { failed++ }
            prefs.edit().putLong("last_run_ms", now).putInt("examined", examined)
                .putInt("deleted", deleted).putLong("freed_bytes", bytes).putInt("failed", failed)
                .putLong("total_deleted", prefs.getLong("total_deleted", 0) + deleted)
                .putLong("total_freed_bytes", prefs.getLong("total_freed_bytes", 0) + bytes).apply()
            Log.i("AutoMaintenance", "temporary sweep: examined=$examined deleted=$deleted freed_bytes=$bytes failed=$failed")
        }
    }

    fun report(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return "\n=== Автоматическое обслуживание ===\n" +
            "last_run_ms=${prefs.getLong("last_run_ms", 0)}; examined=${prefs.getInt("examined", 0)}; " +
            "deleted=${prefs.getInt("deleted", 0)}; freed_bytes=${prefs.getLong("freed_bytes", 0)}; " +
            "failed=${prefs.getInt("failed", 0)}; total_deleted=${prefs.getLong("total_deleted", 0)}; " +
            "total_freed_bytes=${prefs.getLong("total_freed_bytes", 0)}\n" +
            "Политика: временные копии старше 7 суток; голосовые, история, полученные документы, резервные копии и ключи не очищаются.\n"
    }
}
