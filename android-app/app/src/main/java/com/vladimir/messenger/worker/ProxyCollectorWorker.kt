package com.vladimir.messenger.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vladimir.messenger.data.repository.MtProxyRepository
import com.vladimir.messenger.service.MtProxyHealthChecker
import com.vladimir.messenger.service.TelegramChannelScraper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * WorkManager worker для периодического сбора прокси.
 * Запускается раз в 6 часов.
 *
 * Алгоритм:
 * 1. Собрать прокси из GitHub + Telegram-каналов
 * 2. Удалить старые прокси (> 7 дней без success)
 * 3. Ограничить пул до 500 прокси (приоритет: активный > рабочие > свежие)
 * 4. Запустить healthcheck для топ-50
 */
@HiltWorker
class ProxyCollectorWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scraper: TelegramChannelScraper,
    private val healthChecker: MtProxyHealthChecker,
    private val proxyRepo: MtProxyRepository,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ProxyCollectorWorker"
        const val WORK_NAME = "proxy_collector"
        private const val POOL_LIMIT = 500
        private const val STALE_DAYS = 7
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "ProxyCollectorWorker started")

        return try {
            // 1. Собрать прокси
            val results = scraper.collectAll()
            val totalAdded = results.sumOf { it.added }
            Log.i(TAG, "Collected: $totalAdded new proxies")

            // 2. Удалить старые (без successCount, старше 7 дней)
            val staleDeleted = proxyRepo.cleanupStale(STALE_DAYS)
            Log.i(TAG, "Stale cleanup: $staleDeleted removed")

            // 3. Ограничить размер пула
            val excessDeleted = proxyRepo.enforcePoolLimit(POOL_LIMIT)
            Log.i(TAG, "Pool limit: $excessDeleted excess removed")

            // 4. Healthcheck топ-50
            healthChecker.checkAll()

            // 5. Cleanup мёртвых
            val dead = proxyRepo.cleanupDead()
            Log.i(TAG, "Dead cleanup: $dead removed")

            val total = proxyRepo.getAll().size
            Log.i(TAG, "Final pool size: $total proxies")

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Worker failed", e)
            Result.retry()
        }
    }
}
