package com.vladimir.messenger.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vladimir.messenger.data.maintenance.AutomaticMaintenance
import kotlinx.coroutines.CancellationException
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MaintenanceEntryPoint {
    fun fileTransferRouter(): com.vladimir.messenger.data.file.FileTransferRouter
    fun updateStorageMaintenance(): com.vladimir.messenger.data.update.UpdateStorageMaintenance
}

class MaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        AutomaticMaintenance.run(applicationContext)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val entry = EntryPointAccessors.fromApplication(applicationContext, MaintenanceEntryPoint::class.java)
            entry.fileTransferRouter().purgeExpiredTransfers()
            runCatching { entry.updateStorageMaintenance().cleanup() }
                .onFailure { android.util.Log.w("MaintenanceWorker", "update copy cleanup failed: ${it.message}") }
        }
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Result.retry()
    }
}
