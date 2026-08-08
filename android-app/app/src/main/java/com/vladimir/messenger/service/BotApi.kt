package com.vladimir.messenger.service

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Клиент для Cloudflare Worker Registry (p2p-relay).
 *
 * Endpoints:
 * - POST /register  — регистрация своего {node_id, public_key, display_name}
 * - GET /lookup?node_id=XX — получение данных о другом node
 * - GET /version — последняя версия APK (Phase 7)
 */
@Singleton
class BotApi @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "BotApi"
        private const val REGISTRY_URL = "https://p2p-relay.1985vzhem.workers.dev"
        private const val HTTP_TIMEOUT = 15000
        const val BOT_USERNAME = "p2p_messenger_relay_bot"
    }

    data class NodeInfo(
        val nodeId: String,
        val publicKey: String,
        val displayName: String,
        val registeredAt: Long,
    )

    /**
     * Зарегистрировать текущее устройство в registry.
     * Вызывается при старте приложения.
     */
    suspend fun registerMyself(
        nodeId: String,
        publicKey: String,
        displayName: String,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("node_id", nodeId)
                put("public_key", publicKey)
                put("display_name", displayName)
            }
            val response = postJson("$REGISTRY_URL/register", body.toString())
            if (response != null) {
                val json = JSONObject(response)
                val success = json.optBoolean("success", false)
                Log.i(TAG, "Register myself: success=$success")
                success
            } else {
                Log.w(TAG, "Register myself: no response")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Register failed", e)
            false
        }
    }

    /**
     * Запросить информацию о другом node по его ID.
     * Используется когда пользователь открывает share link.
     */
    suspend fun lookupNode(nodeId: String): NodeInfo? = withContext(Dispatchers.IO) {
        try {
            val response = getJson("$REGISTRY_URL/lookup?node_id=$nodeId") ?: return@withContext null
            val json = JSONObject(response)
            if (json.has("error")) {
                Log.w(TAG, "Lookup failed: ${json.optString("error")}")
                return@withContext null
            }
            NodeInfo(
                nodeId = json.getString("node_id"),
                publicKey = json.getString("public_key"),
                displayName = json.optString("display_name", "Unknown"),
                registeredAt = json.optLong("registered_at", 0),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Lookup failed", e)
            null
        }
    }

    /**
     * Сгенерировать share link для текущего пользователя.
     */
    fun generateShareLink(nodeId: String): String {
        return "https://t.me/$BOT_USERNAME?start=$nodeId"
    }

    /**
     * Получить последнюю версию APK (для Phase 7).
     */
    suspend fun getLatestVersion(): String? = withContext(Dispatchers.IO) {
        try {
            val response = getJson("$REGISTRY_URL/version") ?: return@withContext null
            val json = JSONObject(response)
            json.optString("version", null)
        } catch (e: Exception) {
            Log.e(TAG, "Get version failed", e)
            null
        }
    }

    private fun postJson(url: String, body: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = HTTP_TIMEOUT
            readTimeout = HTTP_TIMEOUT
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "POST $url returned $code")
                return null
            }
            BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "POST failed: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun getJson(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = HTTP_TIMEOUT
            readTimeout = HTTP_TIMEOUT
        }
        return try {
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "GET $url returned $code")
                return null
            }
            BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "GET failed: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }
}
