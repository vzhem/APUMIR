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
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Клиент для Cloudflare Worker Registry (p2p-relay).
 *
 * Endpoints:
 * - POST /register  — регистрация своего {node_id, public_key, display_name}
 * - GET /lookup?node_id=XX — получение данных о другом node
 * - GET /version — последняя версия APK (Phase 7)
 * - POST /short {target} — короткая ссылка для пересылки (data.link.ShortLinks)
 * - GET /short/<код> — куда ведёт короткая ссылка
 */
@Singleton
class BotApi @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "BotApi"
        private const val REGISTRY_URL = "https://p2p-relay.1985vzhem.workers.dev"
        private const val HTTP_TIMEOUT = 15000
        /** Короткие ссылки ждут человека у кнопки «Поделиться»: ждём меньше. */
        private const val SHORT_LINK_TIMEOUT = 5000
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
     * Положить запертый сундук личности на полку.
     *
     * Наружу уходят только непрозрачные байты: ключ и пароль зашифрованы на
     * телефоне ([com.vladimir.messenger.data.security.IdentityVault]), сервер
     * не может их прочитать и хранит запись как есть.
     *
     * Полка адресуется отпечатком никнейма, поэтому по ней нельзя понять, чей
     * это сундук.
     */
    suspend fun storeIdentityVault(shelf: String, sealedBase64: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("shelf", shelf)
                    put("vault", sealedBase64)
                }
                val response = postJson("$REGISTRY_URL/vault/put", body.toString())
                    ?: return@withContext false
                val ok = JSONObject(response).optBoolean("success", false)
                Log.i(TAG, "Vault store: success=$ok")
                ok
            } catch (e: Exception) {
                Log.e(TAG, "Vault store failed", e)
                false
            }
        }

    /**
     * Каталог GIF: поиск через НАШ сервер (ключ Tenor спрятан там).
     * query пустой/blank - популярные. Вернёт (гифки, курсор «ещё») или
     * null - сервер недоступен/не настроен (в json будет error).
     */
    suspend fun gifSearch(
        query: String,
        pos: String,
    ): Pair<List<com.vladimir.messenger.data.gif.GifItem>, String>? =
        withContext(Dispatchers.IO) {
            try {
                val sb = StringBuilder("$REGISTRY_URL/gif/search")
                val params = ArrayList<String>()
                if (query.isNotBlank()) {
                    params.add("q=" + java.net.URLEncoder.encode(query.trim(), "UTF-8"))
                }
                if (pos.isNotBlank()) params.add("pos=" + java.net.URLEncoder.encode(pos, "UTF-8"))
                if (params.isNotEmpty()) sb.append('?').append(params.joinToString("&"))
                val response = getJson(sb.toString()) ?: return@withContext null
                val json = JSONObject(response)
                if (json.has("error")) {
                    Log.i(TAG, "gif search: ${json.optString("error")}")
                    return@withContext null
                }
                val array = json.optJSONArray("results") ?: return@withContext null
                val items = ArrayList<com.vladimir.messenger.data.gif.GifItem>()
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val id = o.optString("id", "")
                    val preview = o.optString("preview", "")
                    val gif = o.optString("gif", "")
                    if (id.isNotBlank() && preview.isNotBlank() && gif.isNotBlank()) {
                        items.add(com.vladimir.messenger.data.gif.GifItem(id, preview, gif))
                    }
                }
                items to json.optString("next", "")
            } catch (e: Exception) {
                Log.e(TAG, "gif search failed", e)
                null
            }
        }

    /**
     * Скачать выбранную гифку (байты с CDN каталога). null - не вышло.
     */
    suspend fun downloadGif(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            require(url.startsWith("https://")) { "Only https" }
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = true
            try {
                if (connection.responseCode !in 200..299) return@withContext null
                val stream = connection.inputStream ?: return@withContext null
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                stream.use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > 25 * 1024 * 1024) return@withContext null
                        out.write(buffer, 0, read)
                    }
                }
                out.toByteArray()
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "gif download failed", e)
            null
        }
    }

    /**
     * Проверка живости сервера: время ответа /health в миллисекундах,
     * null - сервер не ответил.
     */
    suspend fun pingHealth(): Int? = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val response = getJson("$REGISTRY_URL/health") ?: return@withContext null
        (System.currentTimeMillis() - started).toInt().coerceAtLeast(1)
    }

    /**
     * Положить резервную копию азбуки адресов на полку.
     *
     * Как и сундук личности: наружу уходят только зашифрованные байты,
     * ключ выведен из приватного ключа узла и на сервер не уходит.
     */
    suspend fun storeAddressBook(shelf: String, book: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("shelf", shelf)
                    put("book", book)
                }
                val response = postJson("$REGISTRY_URL/addrbook/put", body.toString())
                    ?: return@withContext false
                JSONObject(response).optBoolean("success", false)
            } catch (e: Exception) {
                Log.e(TAG, "AddressBook store failed", e)
                false
            }
        }

    /**
     * Забрать резервную копию азбуки адресов. null - полка пуста или
     * сервер недоступен.
     */
    suspend fun fetchAddressBook(shelf: String): String? = withContext(Dispatchers.IO) {
        try {
            val response = getJson("$REGISTRY_URL/addrbook/get?shelf=$shelf")
                ?: return@withContext null
            val json = JSONObject(response)
            if (json.has("error")) {
                Log.i(TAG, "AddressBook fetch: ${json.optString("error")}")
                return@withContext null
            }
            json.optString("book", "").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "AddressBook fetch failed", e)
            null
        }
    }

    /**
     * Забрать сундук с полки. Открыть его сможет только тот, кто знает пароль.
     *
     * @return содержимое сундука в base64 или null, если полка пуста либо
     *         сервер недоступен.
     */
    suspend fun fetchIdentityVault(shelf: String): String? = withContext(Dispatchers.IO) {
        try {
            val response = getJson("$REGISTRY_URL/vault/get?shelf=$shelf") ?: return@withContext null
            val json = JSONObject(response)
            if (json.has("error")) {
                Log.i(TAG, "Vault fetch: ${json.optString("error")}")
                return@withContext null
            }
            json.optString("vault", "").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "Vault fetch failed", e)
            null
        }
    }

    /**
     * Сгенерировать share link для текущего пользователя.
     */
    fun generateShareLink(nodeId: String): String {
        val encodedNodeId = URLEncoder.encode(nodeId, "UTF-8")
        return "https://t.me/$BOT_USERNAME?start=$encodedNodeId"
    }

    /**
     * Спрятать длинную ссылку за коротким кодом. Сервис сам считает код как
     * отпечаток ссылки, поэтому одна и та же ссылка всегда даёт один код.
     *
     * @return код (обычно 10 знаков) либо null, если сервис недоступен или
     *         отказал (ссылка не того вида).
     */
    suspend fun shortenLink(target: String): String? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply { put("target", target) }
            val response = postJson("$REGISTRY_URL/short", body.toString(), SHORT_LINK_TIMEOUT)
                ?: return@withContext null
            JSONObject(response).optString("code", "").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "Shorten failed", e)
            null
        }
    }

    /**
     * Куда ведёт короткая ссылка. Null - код неизвестен сервису либо сервис
     * недоступен; вызывающий сам решает, что сказать человеку.
     */
    suspend fun expandShortLink(code: String): String? = withContext(Dispatchers.IO) {
        try {
            val response = getJson(
                "$REGISTRY_URL/short/" + URLEncoder.encode(code, "UTF-8"),
                SHORT_LINK_TIMEOUT,
            ) ?: return@withContext null
            JSONObject(response).optString("target", "").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "Expand failed", e)
            null
        }
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

    private fun postJson(url: String, body: String, timeoutMs: Int = HTTP_TIMEOUT): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
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

    private fun getJson(url: String, timeoutMs: Int = HTTP_TIMEOUT): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
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
