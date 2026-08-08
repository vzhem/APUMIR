package com.vladimir.messenger.service

import android.util.Log
import com.vladimir.messenger.data.repository.MtProxyRepository
import com.vladimir.messenger.domain.model.ProxyType
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URL

/**
 * Telegram Bot relay — запасной канал доставки сообщений.
 * Поддерживает SOCKS5/HTTP прокси для обхода блокировок api.telegram.org.
 *
 * Стратегия выбора прокси:
 * - Использует активный SOCKS5/HTTP прокси из MtProxyRepository
 * - Если активный прокси упал — помечает как failed и пробует следующий
 * - Если нет работающих прокси — работает напрямую (fallback)
 */
class TelegramRelay(
    private val botToken: String,
    private val myNodeId: String,
    private val scope: CoroutineScope,
    private val proxyRepo: MtProxyRepository,
) {
    companion object {
        private const val TAG = "TelegramRelay"
        private const val API_BASE = "https://api.telegram.org"
        private const val POLL_INTERVAL = 5000L
        private const val HTTP_TIMEOUT = 15000
    }

    private var lastUpdateId = 0L
    private var running = false
    private var pollJob: Job? = null
    private var currentProxyId: String? = null
    private var currentProxy: Proxy? = null

    var onMessageReceived: ((senderId: String, payload: String) -> Unit)? = null

    fun start() {
        if (running) return
        running = true
        pollJob = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "Telegram relay started for $myNodeId")
            while (running && isActive) {
                try {
                    refreshProxyIfNeeded()
                    pollUpdates()
                } catch (e: Exception) {
                    Log.e(TAG, "Poll error: ${e.message}")
                    markCurrentProxyFailed()
                }
                delay(POLL_INTERVAL)
            }
        }
    }

    fun stop() {
        running = false
        pollJob?.cancel()
    }

    /**
     * Обновить активный прокси если он изменился.
     */
    private suspend fun refreshProxyIfNeeded() {
        val active = proxyRepo.getActive()
        if (active == null || !active.isSocksOrHttp()) {
            if (currentProxy != null) {
                Log.d(TAG, "No SOCKS5/HTTP proxy — switching to direct")
                currentProxy = null
                currentProxyId = null
            }
            return
        }

        if (active.id == currentProxyId) return  // Already using this proxy

        Log.i(TAG, "Switching to proxy: ${active.type} ${active.host}:${active.port}")
        currentProxy = buildProxy(active.type, active.host, active.port, active.username, active.password)
        currentProxyId = active.id
    }

    private fun buildProxy(type: ProxyType, host: String, port: Int, username: String, password: String): Proxy {
        // Если есть авторизация — установить Authenticator
        if (username.isNotEmpty()) {
            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(username, password.toCharArray())
                }
            })
        } else {
            Authenticator.setDefault(null)
        }

        val proxyType = when (type) {
            ProxyType.SOCKS5 -> Proxy.Type.SOCKS
            ProxyType.HTTP -> Proxy.Type.HTTP
            ProxyType.MTProto -> return Proxy.NO_PROXY
        }
        return Proxy(proxyType, InetSocketAddress(host, port))
    }

    private fun markCurrentProxyFailed() {
        val id = currentProxyId ?: return
        scope.launch {
            proxyRepo.markFailed(id)
            Log.w(TAG, "Proxy $id marked as failed")
            currentProxy = null
            currentProxyId = null
        }
    }

    private fun markCurrentProxySuccess() {
        val id = currentProxyId ?: return
        scope.launch {
            proxyRepo.markSuccess(id)
        }
    }

    private fun pollUpdates() {
        val url = "$API_BASE/bot$botToken/getUpdates?offset=${lastUpdateId + 1}&timeout=1"
        val response = httpGet(url) ?: return
        markCurrentProxySuccess()

        val json = JSONObject(response)
        if (!json.optBoolean("ok", false)) return

        val result = json.optJSONArray("result") ?: return
        for (i in 0 until result.length()) {
            val update = result.getJSONObject(i)
            val updateId = update.optLong("update_id")
            if (updateId > lastUpdateId) lastUpdateId = updateId

            val message = update.optJSONObject("message") ?: continue
            val text = message.optString("text", "")
            val from = message.optJSONObject("from") ?: continue
            val senderId = from.optString("id", "")

            if (text.startsWith("/send ")) {
                val payload = text.substring(6)
                Log.i(TAG, "Message from $senderId via Telegram (${payload.length} bytes)")
                onMessageReceived?.invoke(senderId, payload)
            }
        }
    }

    suspend fun sendMessage(recipientChatId: String, payload: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$API_BASE/bot$botToken/sendMessage"
                val body = JSONObject().apply {
                    put("chat_id", recipientChatId)
                    put("text", "/send $payload")
                }
                val code = httpPost(url, body.toString())
                if (code == 200) {
                    markCurrentProxySuccess()
                    true
                } else {
                    markCurrentProxyFailed()
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "send failed: ${e.message}")
                markCurrentProxyFailed()
                false
            }
        }
    }

    private fun httpGet(urlStr: String): String? {
        val conn = (URL(urlStr).openConnection(currentProxy ?: Proxy.NO_PROXY) as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = HTTP_TIMEOUT
            readTimeout = HTTP_TIMEOUT
        }
        return try {
            val code = conn.responseCode
            if (code != 200) {
                conn.disconnect()
                return null
            }
            BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "GET $urlStr failed: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPost(urlStr: String, body: String): Int {
        val conn = (URL(urlStr).openConnection(currentProxy ?: Proxy.NO_PROXY) as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = HTTP_TIMEOUT
            readTimeout = HTTP_TIMEOUT
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }
}
