package com.vladimir.messenger.service

import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cloudflare Workers relay v2 — экономный режим.
 * - Worker v2: inbox массив в одном KV ключе (нет list())
 * - Polling каждые 10 секунд (вместо 3)
 * - Exponential backoff при ошибках (10s -> 20s -> 40s -> 60s max)
 * - Дедупликация входящих
 */
class CloudflareRelay(
    private val baseUrl: String,
    private val myNodeId: String,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "CloudflareRelay"
        private const val POLL_INTERVAL_OK = 10000L      // 10 сек при успехе
        private const val POLL_INTERVAL_ERR = 30000L     // 30 сек при ошибке (start)
        private const val POLL_INTERVAL_MAX = 60000L     // 60 сек максимум
        private const val DEDUP_CACHE_SIZE = 100

        @Volatile
        private var instance: CloudflareRelay? = null

        fun getInstance(): CloudflareRelay? = instance
    }

    private var running = false
    private var pollJob: Job? = null
    private val dedupCache = LinkedHashSet<String>(DEDUP_CACHE_SIZE)
    
    // Backoff: начинается с 10s, растёт при ошибках
    private var currentInterval = POLL_INTERVAL_OK
    private var consecutiveErrors = 0

    var onMessageReceived: ((senderId: String, payload: String) -> Unit)? = null

    fun start() {
        instance = this
        if (running) return
        running = true
        pollJob = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "Cloudflare relay v2 started: $baseUrl for $myNodeId")
            while (running && isActive) {
                try {
                    val ok = pollMessages()
                    if (ok) {
                        consecutiveErrors = 0
                        currentInterval = POLL_INTERVAL_OK
                    } else {
                        consecutiveErrors++
                        // Exponential backoff
                        currentInterval = minOf(
                            currentInterval * 2,
                            POLL_INTERVAL_MAX
                        )
                        Log.w(TAG, "Poll failed #$consecutiveErrors, backoff to ${currentInterval/1000}s")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Poll error: ${e.message}")
                    consecutiveErrors++
                    currentInterval = POLL_INTERVAL_ERR
                }
                delay(currentInterval)
            }
        }
    }

    fun stop() {
        running = false
        pollJob?.cancel()
        if (instance === this) instance = null
    }

    /**
     * Отправить сообщение через CF relay.
     * Payload должен быть уже JSON (с type=message и метаданными).
     */
    suspend fun sendMessage(recipientNodeId: String, payload: String): Boolean {
        return withContext(Dispatchers.IO) {
            for (attempt in 0..1) {
                try {
                    val conn = URL("$baseUrl/send").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.connectTimeout = 30000
                    conn.readTimeout = 30000

                    val body = JSONObject().apply {
                        put("to", recipientNodeId)
                        put("from", myNodeId)
                        put("payload", payload)
                    }
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }

                    val code = conn.responseCode
                    conn.disconnect()
                    Log.d(TAG, "send to $recipientNodeId: HTTP $code")
                    if (code == 200) return@withContext true
                } catch (e: Exception) {
                    Log.e(TAG, "send failed (attempt ${attempt + 1}): ${e.message}")
                    delay(1000)
                }
            }
            false
        }
    }

    /**
     * Poll входящих. Возвращает true если poll успешен.
     */
    private fun pollMessages(): Boolean {
        val conn = try {
            URL("$baseUrl/poll?node=$myNodeId")
                .openConnection() as HttpURLConnection
        } catch (e: Exception) {
            Log.w(TAG, "Poll connect failed: ${e.message}")
            return false
        }
        conn.connectTimeout = 30000
        conn.readTimeout = 30000

        if (conn.responseCode != 200) {
            Log.w(TAG, "Poll HTTP ${conn.responseCode}")
            conn.disconnect()
            return false
        }

        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()

        val json = try { JSONObject(body) } catch (e: Exception) {
            Log.e(TAG, "Poll JSON parse error: ${e.message}")
            return false
        }
        if (!json.optBoolean("ok", false)) {
            Log.w(TAG, "Poll API error: ${json.optString("error")}")
            return false
        }

        val messages: JSONArray = json.optJSONArray("messages") ?: JSONArray()
        val count = messages.length()
        
        if (count > 0) {
            Log.i(TAG, "Poll: received $count messages")
        }

        for (i in 0 until count) {
            val msg = messages.getJSONObject(i)
            val from = msg.optString("from", "unknown")
            val payload = msg.optString("payload", "")
            if (payload.isBlank()) continue

            // Дедупликация
            val dedupKey = "${from}:${payload.hashCode()}"
            if (dedupKey in dedupCache) {
                Log.d(TAG, "Duplicate skipped from $from")
                continue
            }

            dedupCache.add(dedupKey)
            while (dedupCache.size > DEDUP_CACHE_SIZE) {
                val it = dedupCache.iterator()
                if (it.hasNext()) { it.next(); it.remove() }
            }

            Log.i(TAG, "Message from $from (${payload.length} bytes)")
            onMessageReceived?.invoke(from, payload)
        }
        return true
    }

    suspend fun isAvailable(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val conn = URL("$baseUrl/health").openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                val ok = conn.responseCode == 200
                conn.disconnect()
                ok
            } catch (e: Exception) {
                false
            }
        }
    }
}
