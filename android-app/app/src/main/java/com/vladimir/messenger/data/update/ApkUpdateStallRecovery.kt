package com.vladimir.messenger.data.update

import android.util.Log
import com.vladimir.messenger.data.file.FileTransferReceiver
import com.vladimir.messenger.data.file.FileTransferRouter
import com.vladimir.messenger.data.local.dao.FileTransferDao
import com.vladimir.messenger.data.local.entity.FileTransferEntity

/**
 * Восстановление зависшего приёма обновления от соседей.
 *
 * Жалоба владельца 2026-11-01: «приём от соседей остановился, хотя показывает
 * 4 соседа». Старый код только повторял `upwant`, но оставлял старую входящую
 * строку. Новый сид отвечал предложением, а [ApkSeeder.routeApkOffer] видел
 * незавершённую строку и отвечал `Duplicate` — круг замыкался, ход не шёл.
 *
 * Теперь строка, которая не двигалась дольше [ApkUpdate.STALLED_DOWNLOAD_REASK_MS],
 * снимается (CANCEL соседу + удаление кусков), и просьба уходит заново. Ожидание
 * свободного места не считаем зависанием: там нужен не другой сид, а место.
 */
internal object ApkUpdateStallRecovery {

    private const val TAG = "ApkUpdateStallRecovery"

    suspend fun activeTransfer(transferDao: FileTransferDao, sha256: String): FileTransferEntity? =
        transferDao.getForFile(ApkUpdate.CHAT_ID, sha256)
            .firstOrNull { it.direction == "INCOMING" && it.state !in TERMINAL }

    /** Снять зависшие входящие строки этого APK. Возвращает true, если что-то снято. */
    suspend fun dropStalledIncoming(
        transferDao: FileTransferDao,
        router: FileTransferRouter,
        sha256: String,
        nowMs: Long,
    ): Boolean {
        var dropped = false
        for (row in transferDao.getForFile(ApkUpdate.CHAT_ID, sha256)) {
            if (row.direction != "INCOMING") continue
            val waitingForSpace = row.errorCode == FileTransferReceiver.ERROR_NO_SPACE
            if (!ApkUpdate.shouldDropStalledIncoming(row.state, waitingForSpace, row.updatedAtMs, nowMs)) continue
            runCatching { router.declineIncoming(row) }
                .onFailure { Log.w(TAG, "decline stalled update failed: ${it.message}") }
            runCatching { router.dropTransfer(row.transferId) }
                .onFailure { Log.w(TAG, "drop stalled update failed: ${it.message}") }
            dropped = true
        }
        return dropped
    }

    private val TERMINAL = setOf("COMPLETE", "FAILED", "CANCELLED", "EXPIRED")
}
