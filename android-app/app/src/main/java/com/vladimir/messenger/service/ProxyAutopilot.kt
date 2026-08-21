package com.vladimir.messenger.service

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.file.FileTransferRankPolicy
import com.vladimir.messenger.data.referral.ReferralRankStore
import com.vladimir.messenger.data.repository.MtProxyRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Прокси-автопилот (владелец: «автоматический поиск, автоматический выбор лучшего и подключение
 * через него; нерабочие прокси удалять сразу»).
 *
 * Один цикл = [проверить пул] → [мёртвых удалить немедленно] → [лучшего по latency сделать
 * активным]. Пул автоматически пополняется сборщиком, когда опустел ниже минимума. Цикл
 * запускается: рабочим каждые 6 часов, при старте сервиса и МГНОВЕННО при сбое активного
 * прокси в TelegramRelay — вместо вечных попыток достучаться до мёртвого.
 *
 * Правило мгновенной чистки: не-Manual прокси, проваливший текущую проверку и набравший
 * ≥ [MtProxyRepository.IMMEDIATE_PURGE_FAILS] суммарных провалов, удаляется сразу.
 */
@Singleton
class ProxyAutopilot @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val proxyRepo: MtProxyRepository,
    private val healthChecker: MtProxyHealthChecker,
    private val scraper: TelegramChannelScraper,
) {
    data class CycleSummary(
        val collected: Int,
        val checked: Int,
        val okCount: Int,
        val purged: Int,
        val bestProxy: String?,
        val bestLatencyMs: Long?,
    )

    /**
     * @param forceCollect собрать свежие списки даже при полном пуле (шестичасовой обход).
     */
    suspend fun cycle(forceCollect: Boolean = false): CycleSummary {
        if (!FileTransferRankPolicy.canUseAutomaticProxy(
                ReferralRankStore.qualifiedDirectCount(appContext)
            )
        ) {
            Log.d(TAG, "Cycle skipped: automatic proxy requires rank")
            return CycleSummary(0, 0, 0, 0, null, null)
        }

        var collected = 0
        if (forceCollect || proxyRepo.needsMoreProxies()) {
            collected = runCatching { scraper.collectAll().sumOf { it.added } }
                .onFailure { Log.w(TAG, "Collection failed: ${it.message}") }
                .getOrDefault(0)
            Log.i(TAG, "Collected $collected new proxies")
        }

        val results = runCatching { healthChecker.checkAll() }
            .onFailure { Log.w(TAG, "Healthcheck failed: ${it.message}") }
            .getOrDefault(emptyList())
        val failedIds = results.filter { !it.success }.map { it.proxy.id }
        val purged = proxyRepo.purgeFailedNow(failedIds)

        val best = results.filter { it.success }.minByOrNull { it.latencyMs }
        if (best != null) {
            proxyRepo.setActive(best.proxy.id)
        } else {
            Log.w(TAG, "No working proxy in this cycle")
        }

        val summary = CycleSummary(
            collected = collected,
            checked = results.size,
            okCount = results.size - failedIds.size,
            purged = purged,
            bestProxy = best?.let { "${it.proxy.type} ${it.proxy.host}:${it.proxy.port}" },
            bestLatencyMs = best?.latencyMs,
        )
        Log.i(
            TAG,
            "Cycle done: checked=${summary.checked} ok=${summary.okCount} purged=${summary.purged} " +
                "best=${summary.bestProxy ?: "none"} (${summary.bestLatencyMs ?: "-"}ms)",
        )
        return summary
    }

    companion object {
        private const val TAG = "ProxyAutopilot"
    }
}
