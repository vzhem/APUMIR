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
    /** Пользовательский выключатель из настроек: выключено — туннель снят, циклы не ходят. */
    fun tunnelEnabledByUser(): Boolean =
        appContext.getSharedPreferences("p2p_prefs", android.content.Context.MODE_PRIVATE)
            .getBoolean("proxy_tunnel_enabled", true)

    suspend fun cycle(forceCollect: Boolean = false): CycleSummary {
        if (!tunnelEnabledByUser()) {
            com.vladimir.messenger.data.RustBridge.clearMqttSocks5Proxy()
            Log.d(TAG, "Cycle skipped: proxy tunnel disabled by user")
            return CycleSummary(0, 0, 0, 0, null, null)
        }
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
        applyBestProxyToEngine(best)

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

    /**
     * «Любая сеть»: лучший живой SOCKS5-прокси туннелирует ВЕСЬ MQTT-трафик движка
     * (обычные и файловые mesh-конверты). HTTP/MTProto-прокси для туннеля не подходят —
     * при их выборе туннель честно очищается. Применяется при следующем переподключении
     * брокера; при сбое прокси движок сам откатывается на прямое соединение до смены.
     */
    private fun applyBestProxyToEngine(best: MtProxyHealthChecker.CheckResult?) {
        try {
            val proxy = best?.proxy
            if (proxy != null && proxy.type == com.vladimir.messenger.domain.model.ProxyType.SOCKS5) {
                com.vladimir.messenger.data.RustBridge.setMqttSocks5Proxy(
                    proxy.host,
                    proxy.port,
                    proxy.username,
                    proxy.password,
                )
            } else {
                com.vladimir.messenger.data.RustBridge.clearMqttSocks5Proxy()
            }
        } catch (e: Exception) {
            Log.w(TAG, "applyBestProxyToEngine failed: ${'$'}{e.message}")
        }
    }

    companion object {
        private const val TAG = "ProxyAutopilot"
    }
}
