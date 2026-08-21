package com.vladimir.messenger.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vladimir.messenger.data.repository.MtProxyRepository
import com.vladimir.messenger.data.file.FileTransferRankPolicy
import com.vladimir.messenger.data.referral.ReferralRankStore
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
    private val proxyRepo: MtProxyRepository,
    private val autopilot: com.vladimir.messenger.service.ProxyAutopilot,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ProxyCollectorWorker"
        const val WORK_NAME = "proxy_collector"
        private const val POOL_LIMIT = 500
        private const val STALE_DAYS = 7
    }

    override suspend fun doWork(): Result {
        val qualified = ReferralRankStore.qualifiedDirectCount(applicationContext)
        if (!FileTransferRankPolicy.canUseAutomaticProxy(qualified)) {
            Log.i(TAG, "Automatic proxy collection skipped: rank 20 required")
            return Result.success()
        }
        Log.i(TAG, "Rank-entitled ProxyCollectorWorker started")

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

            // 4. Автопилот: healthcheck → мгновенная чистка мёртвых → выбор и подключение лучшего
            autopilot.cycle(forceCollect = false)

            val total = proxyRepo.getAll().size
            Log.i(TAG, "Final pool size: $total proxies")

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Worker failed", e)
            Result.retry()
        }
    }
}
