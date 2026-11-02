package com.vladimir.messenger.data.update

import android.app.DownloadManager
import android.content.Context
import android.util.Log
import com.vladimir.messenger.service.UpdateChecker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Removes completed public APK downloads only after the matching version is installed. */
@Singleton
class ApkDownloadMaintenance @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updateChecker: UpdateChecker,
) {
    data class Result(val deleted: Int, val freedBytes: Long, val failed: Int)

    fun cleanupInstalledDownloads(installedVersion: String): Result {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return Result(0, 0, 0)
        var deleted = 0
        var freedBytes = 0L
        var failed = 0
        for (download in updateChecker.completedApkDownloads()) {
            if (!UpdateStoragePolicy.mayDeleteInstalledDownload(download.title, installedVersion)) continue
            val bytesBefore = download.file.takeIf { it.isFile }?.length() ?: 0L
            val removed = try {
                manager.remove(download.downloadId)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "installed APK download cleanup failed: ${error.message}")
                failed++
                continue
            }
            if (removed <= 0) continue
            deleted++
            val bytesAfter = download.file.takeIf { it.isFile }?.length() ?: 0L
            freedBytes = Math.addExact(freedBytes, (bytesBefore - bytesAfter).coerceAtLeast(0L))
            if (bytesAfter > 0L) {
                Log.w(TAG, "DownloadManager removed an installed APU APK but its file remains")
                failed++
            }
        }
        if (deleted > 0 || failed > 0) {
            Log.i(TAG, "installed APK downloads cleaned: deleted=$deleted freed_bytes=$freedBytes failed=$failed")
        }
        return Result(deleted, freedBytes, failed)
    }

    companion object {
        private const val TAG = "ApkDownloadMaintenance"
    }
}
