package com.vladimir.messenger.data.update

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.file.FileTransferChunkStore
import com.vladimir.messenger.data.file.FileTransferKeyVault
import com.vladimir.messenger.data.file.FileTransferRouter
import com.vladimir.messenger.data.file.ReceivedFileStore
import com.vladimir.messenger.data.local.dao.FileTransferDao
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.Files
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Removes redundant *update* copies, never user documents.
 *
 * One verified encrypted swarm copy is kept as the active seed. Once an update
 * is installed, redundant plaintext and older/duplicate encrypted copies are
 * removed; a newer pending APK and the current active seed are kept.
 */
@Singleton
class UpdateStorageMaintenance @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transferDao: FileTransferDao,
    private val router: FileTransferRouter,
    private val updateChecker: com.vladimir.messenger.service.UpdateChecker,
    private val apkDownloadMaintenance: ApkDownloadMaintenance,
) {
    private val mutex = Mutex()

    data class CleanupResult(val deleted: Int, val freedBytes: Long, val failed: Int) {
        operator fun plus(other: CleanupResult) =
            CleanupResult(deleted + other.deleted, freedBytes + other.freedBytes, failed + other.failed)
    }

    data class Report(
        val installedApkBytes: Long,
        val extractedNativeBytes: Long,
        val seedPlaintextBytes: Long,
        val encryptedUpdateBytes: Long,
        val receivedUpdatePlaintextBytes: Long,
        val installHandoffBytes: Long,
        val publicDownloadBytes: Long,
    ) {
        val updateCopyBytes: Long
            get() = seedPlaintextBytes + encryptedUpdateBytes + receivedUpdatePlaintextBytes +
                installHandoffBytes + publicDownloadBytes
    }

    suspend fun cleanup(nowMs: Long = System.currentTimeMillis()): CleanupResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val installed = installedVersion()
            var result = UpdateInstallHandoff.cleanup(app, installed, nowMs).let {
                CleanupResult(it.deleted, it.freedBytes, it.failed)
            }
            val downloads = apkDownloadMaintenance.cleanupInstalledDownloads(installed)
            result += CleanupResult(downloads.deleted, downloads.freedBytes, downloads.failed)

            val seedRoot = UpdateFileRoots.seed(app)
            val store = ApkUpdateStore(seedRoot)
            val seed = store.loadSeed()
            val patch = store.loadPatchSeed()
            val receivedStore = ReceivedFileStore(File(app.noBackupFilesDir, "file_received/v1"))
            val chunkStore = FileTransferChunkStore.forApplication(app)
            val seedVersionComparison = seed?.let { ApkUpdate.compareVersions(it.version, installed) }
            val protectedSeedTransferId = if (seed != null && seedVersionComparison != null && seedVersionComparison >= 0) {
                runCatching {
                    transferDao.getForFile(ApkUpdate.CHAT_ID, seed.sha256)
                        .firstOrNull { router.groupSeeder.canSeed(it, router::hasTransferKey) }
                        ?.transferId
                }.onFailure { Log.w(TAG, "update cleanup: active seed check failed: ${it.message}") }
                    .getOrNull()
            } else null
            val installedSeedReady = seedVersionComparison == 0 && protectedSeedTransferId != null

            // An obsolete completed update is no longer needed once a newer
            // version is installed. Keep the active installed seed and every
            // newer pending update; remove duplicate/older encrypted copies.
            val rows = runCatching { transferDao.getForChatLimited(ApkUpdate.CHAT_ID, 128) }
                .getOrElse {
                    Log.w(TAG, "update cleanup: transfer query failed: ${it.message}")
                    emptyList()
                }
            for (row in rows) {
                if (UpdateStoragePolicy.mayDeleteRedundantEncryptedTransfer(
                        chatId = row.chatId,
                        direction = row.direction,
                        state = row.state,
                        displayName = row.displayName,
                        transferId = row.transferId,
                        installedVersion = installed,
                        protectedSeedTransferId = protectedSeedTransferId,
                        installedSeedReady = installedSeedReady,
                    )
                ) {
                    result += deleteRedundantTransfer(app, row, chunkStore, receivedStore)
                    continue
                }
                // Plaintext of an already-installed received update is redundant
                // when its encrypted seed remains available for neighbors.
                val ready = runCatching { router.groupSeeder.canSeed(row, router::hasTransferKey) }
                    .getOrDefault(false)
                if (!UpdateStoragePolicy.mayDeleteReceivedPlaintext(
                        row.chatId, row.direction, row.state, row.displayName, installed, ready,
                        transferExpired = row.expiresAtMs <= nowMs,
                    )
                ) continue
                val file = receivedStore.receivedFile(row.transferId, row.displayName) ?: continue
                val bytes = file.length()
                if (runCatching { receivedStore.deleteTransfer(row.transferId) }.getOrDefault(false)) {
                    result += CleanupResult(1, bytes, 0)
                } else {
                    result += CleanupResult(0, 0, 1)
                }
            }

            // Plaintext seed copy of the installed version is redundant once the
            // encrypted group copy can serve neighbors.
            if (seed != null) {
                val ready = runCatching {
                    transferDao.getForFile(ApkUpdate.CHAT_ID, seed.sha256)
                        .any { router.groupSeeder.canSeed(it, router::hasTransferKey) }
                }.getOrDefault(false)
                if (UpdateStoragePolicy.mayDeleteSeedPlaintext(seed.version, installed, ready)) {
                    result += delete(File(seedRoot, "${seed.sha256}.apk"))
                }
            }

            // Hash-named artifacts left by interrupted preparations. Active seed
            // and patch are protected; fresh files get a grace period.
            val children = seedRoot.listFiles().orEmpty().sortedBy { it.lastModified() }
            for (file in children.take(256)) {
                if (!file.isFile || Files.isSymbolicLink(file.toPath())) continue
                if (!UpdateStoragePolicy.isOrphanSeedArtifact(file.name, seed?.sha256, patch?.patchSha256)) continue
                val modified = file.lastModified()
                if (modified <= 0 || modified > nowMs || nowMs - modified < GRACE_MS) continue
                result += delete(file)
            }
            if (result.deleted > 0 || result.failed > 0) {
                Log.i(TAG, "update storage cleanup: deleted=${result.deleted} freed_bytes=${result.freedBytes} failed=${result.failed}")
            }
            result
        }
    }

    suspend fun report(): Report = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val info = app.applicationInfo
        val installed = listOfNotNull(info.sourceDir) + info.splitSourceDirs.orEmpty().toList()
        val installedBytes = installed.sumOf { path -> File(path).takeIf { it.isFile }?.length() ?: 0L }
        val nativeBytes = info.nativeLibraryDir?.let { boundedTreeSize(File(it)) } ?: 0L
        val seedRoot = UpdateFileRoots.seed(app)
        val seedBytes = seedRoot.listFiles().orEmpty()
            .filter { it.isFile && (it.name.endsWith(".apk") || it.name.endsWith(".bspatch")) }
            .sumOf { it.length() }
        val encrypted = runCatching { FileTransferChunkStore.forApplication(app).quotaExemptBytes() }
            .getOrElse {
                Log.w(TAG, "update report: encrypted size failed: ${it.message}")
                0L
            }
        val rows = runCatching { transferDao.getForChatLimited(ApkUpdate.CHAT_ID, 128) }
            .getOrElse {
                Log.w(TAG, "update report: transfer query failed: ${it.message}")
                emptyList()
            }
        val receivedStore = ReceivedFileStore(File(app.noBackupFilesDir, "file_received/v1"))
        val receivedBytes = rows.asSequence()
            .filter { it.direction == "INCOMING" && it.state == "COMPLETE" && it.displayName.endsWith(".apk", true) }
            .mapNotNull { receivedStore.receivedFile(it.transferId, it.displayName) }
            .sumOf { it.length() }
        val handoff = boundedTreeSize(File(app.cacheDir, "updates"))
        val public = runCatching {
            updateChecker.completedApkDownloads().sumOf { it.file.length() }
        }.getOrElse {
            Log.w(TAG, "update report: public downloads failed: ${it.message}")
            0L
        }
        Report(installedBytes, nativeBytes, seedBytes, encrypted, receivedBytes, handoff, public)
    }

    private suspend fun deleteRedundantTransfer(
        app: Context,
        row: FileTransferEntity,
        chunkStore: FileTransferChunkStore,
        receivedStore: ReceivedFileStore,
    ): CleanupResult {
        val transferId = row.transferId
        var freedBytes = 0L
        try {
            // This path is limited by policy to completed update copies (or an
            // old seeding row), never an ordinary file or the protected seed.
            router.groupSeeder.forget(transferId)
            val chunksBefore = chunkStore.transferChunkBytes(transferId)
            val receivedFile = receivedStore.receivedFile(transferId, row.displayName)
            val receivedBefore = receivedFile?.takeIf { it.isFile }?.length() ?: 0L

            val chunksDeleted = chunkStore.deleteTransfer(transferId)
            val chunksAfter = chunkStore.transferChunkBytes(transferId)
            freedBytes += (chunksBefore - chunksAfter).coerceAtLeast(0L)
            if (!chunksDeleted) {
                Log.w(TAG, "update cleanup: encrypted transfer files could not be fully removed")
                return CleanupResult(0, freedBytes, 1)
            }

            val receivedDeleted = receivedStore.deleteTransfer(transferId)
            val receivedAfter = receivedFile?.takeIf { it.isFile }?.length() ?: 0L
            freedBytes += (receivedBefore - receivedAfter).coerceAtLeast(0L)
            if (!receivedDeleted) {
                Log.w(TAG, "update cleanup: received APK copy could not be fully removed")
                return CleanupResult(0, freedBytes, 1)
            }
            if (!FileTransferKeyVault.deleteKey(app, transferId)) {
                Log.w(TAG, "update cleanup: wrapped key could not be removed")
                return CleanupResult(0, freedBytes, 1)
            }

            val rowsDeleted = transferDao.deleteTransfer(transferId)
            if (rowsDeleted == 0 && transferDao.getTransfer(transferId) != null) {
                Log.w(TAG, "update cleanup: obsolete transfer row could not be removed")
                return CleanupResult(0, freedBytes, 1)
            }
            return CleanupResult(1, freedBytes, 0)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "update cleanup: obsolete transfer removal failed: ${error.message}")
            return CleanupResult(0, freedBytes, 1)
        }
    }

    private fun delete(file: File): CleanupResult {
        if (!file.isFile || Files.isSymbolicLink(file.toPath())) return CleanupResult(0, 0, 0)
        val bytes = file.length()
        return if (file.delete()) CleanupResult(1, bytes, 0) else CleanupResult(0, 0, 1)
    }

    private fun installedVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    }.getOrElse {
        Log.w(TAG, "update cleanup: installed version unavailable: ${it.message}")
        "0"
    }

    /** Metadata-only walk with a hard entry budget; never follows symlinks. */
    private fun boundedTreeSize(root: File): Long {
        if (!root.exists() || Files.isSymbolicLink(root.toPath())) return 0L
        var total = 0L
        var examined = 0
        root.walkTopDown()
            .onEnter { dir -> !Files.isSymbolicLink(dir.toPath()) }
            .take(4096)
            .forEach { file ->
                examined++
                if (file.isFile && !Files.isSymbolicLink(file.toPath())) total += file.length()
            }
        return total
    }

    companion object {
        private const val TAG = "UpdateStorageMaintenance"
        private const val GRACE_MS = 24L * 60 * 60 * 1000
    }
}
