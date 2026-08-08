package com.vladimir.messenger.service

import android.util.Log
import com.vladimir.messenger.data.repository.MtProxyRepository
import com.vladimir.messenger.domain.model.MtProtoProxy
import com.vladimir.messenger.domain.model.ProxyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Скрапер прокси из публичных источников.
 *
 * Источники (в порядке приоритета):
 * 1. Telegram-каналы (t.me/s/channel) — через активный SOCKS5/HTTP
 * 2. GitHub raw (обычно не блокируются)
 * 3. Публичные API (proxy-list.download и т.п.)
 *
 * Fallback логика: если активный прокси упал — пробуем следующие из пула.
 */
@Singleton
class TelegramChannelScraper @Inject constructor(
    private val proxyRepo: MtProxyRepository,
) {
    companion object {
        private const val TAG = "ChannelScraper"
        private const val HTTP_TIMEOUT = 15000
        private const val MAX_PROXY_ATTEMPTS = 3

        val TELEGRAM_CHANNELS = listOf(
            "proxy_mtproto",
            "socks5list",
            "mtproto_proxy",
            "MTProxyTg",
        )

        // GitHub raw URLs с прокси (обычно не блокируются)
        val GITHUB_SOURCES = listOf(
            "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/socks5.txt",
            "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt",
            "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt",
            "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt",
            "https://raw.githubusercontent.com/mmpx12/proxy-list/master/socks5.txt",
            "https://raw.githubusercontent.com/mmpx12/proxy-list/master/http.txt",
        )
    }

    data class ScrapeResult(
        val source: String,
        val parsed: Int,
        val added: Int,
        val error: String? = null,
    )

    /**
     * Собрать прокси со всех источников.
     */
    suspend fun collectAll(): List<ScrapeResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ScrapeResult>()

        // 1. GitHub raw (сначала — они обычно работают без прокси)
        Log.i(TAG, "Collecting from ${GITHUB_SOURCES.size} GitHub sources")
        for (url in GITHUB_SOURCES) {
            try {
                results.add(collectGitHub(url))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to collect from $url", e)
                results.add(ScrapeResult(url, 0, 0, e.message))
            }
        }

        // 2. Telegram каналы (через SOCKS5/HTTP прокси с fallback)
        Log.i(TAG, "Collecting from ${TELEGRAM_CHANNELS.size} Telegram channels")
        for (channel in TELEGRAM_CHANNELS) {
            try {
                results.add(collectTelegramChannel(channel))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to collect from $channel", e)
                results.add(ScrapeResult(channel, 0, 0, e.message))
            }
        }

        val totalParsed = results.sumOf { it.parsed }
        val totalAdded = results.sumOf { it.added }
        Log.i(TAG, "Collection done: parsed=$totalParsed, added=$totalAdded")
        results
    }

    private suspend fun collectGitHub(url: String): ScrapeResult {
        // GitHub raw — пробуем напрямую (обычно не заблокирован)
        val text = fetchText(url, Proxy.NO_PROXY) ?: return ScrapeResult(url, 0, 0, "fetch failed")

        val entities = com.vladimir.messenger.data.repository.MtProxyParser.parseMultiple(text, source = "GITHUB")
        val parsed = entities.size
        val added = insertNew(entities)

        Log.i(TAG, "GitHub ${url.substringAfterLast("/")}: parsed=$parsed, added=$added")
        return ScrapeResult(url, parsed, added)
    }

    private suspend fun collectTelegramChannel(channel: String): ScrapeResult {
        val url = "https://t.me/s/$channel"

        // Пробуем через несколько прокси по очереди
        val proxyCandidates = getProxyCandidates()
        Log.d(TAG, "Channel $channel: ${proxyCandidates.size} proxy candidates")

        var html: String? = null
        var usedProxy: String = "direct"

        for (proxy in proxyCandidates) {
            html = fetchText(url, proxy)
            if (html != null) {
                usedProxy = proxy.toString()
                break
            }
        }

        // Если через прокси не получилось — пробуем напрямую (может быть не заблокирован)
        if (html == null) {
            html = fetchText(url, Proxy.NO_PROXY)
            usedProxy = "direct (fallback)"
        }

        if (html == null) {
            return ScrapeResult(channel, 0, 0, "all proxies failed")
        }

        Log.d(TAG, "Channel $channel: fetched via $usedProxy")
        val messageTexts = extractMessageTexts(html)
        val combined = messageTexts.joinToString("\n\n")
        val entities = com.vladimir.messenger.data.repository.MtProxyParser.parseMultiple(combined, source = "CHANNEL")
        val parsed = entities.size
        val added = insertNew(entities)

        Log.i(TAG, "Channel $channel: parsed=$parsed, added=$added (via $usedProxy)")
        return ScrapeResult(channel, parsed, added)
    }

    /**
     * Получить список прокси для попыток:
     * 1. Активный SOCKS5/HTTP
     * 2. Другие рабочие SOCKS5/HTTP (successCount > 0)
     * 3. Все SOCKS5/HTTP
     */
    private suspend fun getProxyCandidates(): List<Proxy> {
        val all = proxyRepo.getAll()
        val socksHttp = all.filter { it.isSocksOrHttp() }

        val candidates = mutableListOf<MtProtoProxy>()

        // 1. Активный
        val active = socksHttp.firstOrNull { it.isActive }
        if (active != null) candidates.add(active)

        // 2. Рабочие (successCount > 0)
        candidates.addAll(socksHttp.filter { it.successCount > 0 && it.id != active?.id }.take(2))

        // 3. Остальные
        candidates.addAll(socksHttp.filter { it.successCount == 0 && it.id != active?.id }.take(2))

        return candidates.take(MAX_PROXY_ATTEMPTS).map { buildProxy(it) }
    }

    private fun buildProxy(p: MtProtoProxy): Proxy {
        if (p.username.isNotEmpty()) {
            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication() =
                    PasswordAuthentication(p.username, p.password.toCharArray())
            })
        } else {
            Authenticator.setDefault(null)
        }
        val proxyType = when (p.type) {
            ProxyType.SOCKS5 -> Proxy.Type.SOCKS
            ProxyType.HTTP -> Proxy.Type.HTTP
            ProxyType.MTProto -> return Proxy.NO_PROXY
        }
        return Proxy(proxyType, InetSocketAddress(p.host, p.port))
    }

    private suspend fun fetchText(url: String, proxy: Proxy): String? = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection(proxy) as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            connectTimeout = HTTP_TIMEOUT
            readTimeout = HTTP_TIMEOUT
        }
        try {
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "GET $url returned $code (proxy=$proxy)")
                return@withContext null
            }
            BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "Fetch $url failed (proxy=$proxy): ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun insertNew(entities: List<com.vladimir.messenger.data.local.entity.MtProtoProxyEntity>): Int {
        var added = 0
        for (entity in entities) {
            val existing = proxyRepo.getById(entity.id)
            if (existing == null) {
                proxyRepo.insertEntity(entity)
                added++
            }
        }
        return added
    }

    private fun extractMessageTexts(html: String): List<String> {
        val pattern = Regex(
            """<div class="tgme_widget_message_text[^"]*"[^>]*>(.*?)</div>""",
            RegexOption.DOT_MATCHES_ALL
        )
        return pattern.findAll(html).map { match ->
            match.groupValues[1]
                .replace(Regex("<[^>]+>"), " ")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace(Regex("\\s+"), " ")
                .trim()
        }.filter { it.isNotBlank() }.toList()
    }
}
