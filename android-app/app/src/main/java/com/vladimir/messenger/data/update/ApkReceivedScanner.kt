package com.vladimir.messenger.data.update

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.local.dao.FileTransferDao
import com.vladimir.messenger.data.transfer.FileTransferRouter
import java.io.File

/**
 * Поиск полученных в чатах APK-файлов и извлечение версии из манифеста/имени файла
 * для раздела «Обновления» (вынесено из `ApkSeeder` для снижения размера файла).
 */
internal object ApkReceivedScanner {
    private const val TAG = "ApkReceivedScanner"

    /**
     * Версия, вшитая в сам APK (`versionName` из AndroidManifest). Это
     * надёжнее угадывания по имени файла: человек не вводит ничего.
     * null — архив не читается (не APK или манифест не разобран).
     */
    fun archiveVersion(context: Context, file: File): String? = runCatching {
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        info?.versionName?.takeIf { version -> version.isNotBlank() }
    }.onFailure {
        Log.w(TAG, "archiveVersion failed for ${file.name}: ${it.message}")
    }.getOrNull()

    /**
     * Угадать версию из имени файла: первое числовое `11.70.29` в строке
     * (APU-v11.70.29.apk, «APU 11.70.29 beta.apk»). null — не найдено.
     */
    fun guessVersionFromName(fileName: String): String? = ApkUpdate.versionFromName(fileName)

    /**
     * Список входящих `.apk`-файлов из обычных чатов, которые новее текущей версии
     * (или чью версию не удалось распознать автоматически).
     */
    suspend fun scan(
        context: Context,
        transferDao: FileTransferDao,
        router: FileTransferRouter,
        currentVersionRaw: String,
        maxItems: Int,
    ): List<ReceivedApk> {
        val rows = runCatching { transferDao.getCompleted() }
            .onFailure { Log.w(TAG, "getCompleted failed: ${it.message}") }
            .getOrDefault(emptyList())
            .filter {
                it.direction == "INCOMING" &&
                    it.chatId != ApkUpdate.CHAT_ID &&
                    it.displayName.lowercase().endsWith(".apk")
            }
        val current = ApkUpdate.normalize(currentVersionRaw)
        return rows.mapNotNull { row ->
            val file = router.receivedFileFor(row) ?: return@mapNotNull null
            if (!file.isFile) return@mapNotNull null
            val versionGuess = archiveVersion(context, file) ?: guessVersionFromName(row.displayName)
            if (versionGuess != null) {
                val vsCurrent = ApkUpdate.compareVersions(ApkUpdate.normalize(versionGuess), current)
                if (vsCurrent != null && vsCurrent <= 0) return@mapNotNull null
            }
            ReceivedApk(
                transferId = row.transferId,
                chatId = row.chatId,
                fromNodeId = row.peerNodeId,
                displayName = row.displayName,
                sizeBytes = row.totalBytes,
                atMs = row.updatedAtMs,
                versionGuess = versionGuess,
            )
        }.sortedByDescending { it.atMs }
            .take(maxItems)
    }
}
