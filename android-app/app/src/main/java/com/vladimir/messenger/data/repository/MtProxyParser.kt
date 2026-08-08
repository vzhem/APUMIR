package com.vladimir.messenger.data.repository

import android.util.Log
import com.vladimir.messenger.data.local.entity.MtProtoProxyEntity
import java.net.URI
import java.security.MessageDigest

/**
 * Универсальный парсер прокси.
 *
 * Поддерживаемые форматы:
 * - tg://proxy?server=X&port=Y&secret=Z         → MTProto
 * - https://t.me/proxy?server=X&port=Y&secret=Z → MTProto
 * - socks5://host:port                          → SOCKS5
 * - socks5://user:pass@host:port                → SOCKS5 с авторизацией
 * - http://host:port                            → HTTP proxy
 * - http://user:pass@host:port                  → HTTP proxy с авторизацией
 * - host:port:secret                            → MTProto (legacy)
 * - host:port                                   → SOCKS5 (без auth)
 *
 * Многострочный формат из Telegram-каналов:
 *   Server: 185.170.114.22 (или Unknown/недоступен)
 *   Port: 443
 *   Secret: ee6a0333...
 *   @ProxyMTProto   ← игнорируется
 */
object MtProxyParser {
    private const val TAG = "MtProxyParser"

    fun parse(input: String, source: String = "MANUAL"): MtProtoProxyEntity? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        val proxy = when {
            trimmed.startsWith("tg://proxy") || trimmed.startsWith("https://t.me/proxy") -> parseTgUri(trimmed)
            trimmed.startsWith("socks5://") -> parseSocks5Uri(trimmed)
            trimmed.startsWith("http://") -> parseHttpUri(trimmed)
            trimmed.contains("Secret:", ignoreCase = true) -> parseKeyValueFormat(trimmed)
            trimmed.contains("Server:", ignoreCase = true) -> parseKeyValueFormat(trimmed)
            else -> parseDirect(trimmed, source)
        }?.copy(source = source)

        return proxy
    }

    private fun parseTgUri(uri: String): MtProtoProxyEntity? {
        return try {
            val parsed = URI(uri.replace("tg://", "http://"))
            val query = parsed.query ?: return null
            val params = query.split("&").associate {
                val (key, value) = it.split("=", limit = 2)
                key to value
            }
            val host = params["server"] ?: return null
            val port = params["port"]?.toIntOrNull() ?: return null
            val secret = params["secret"] ?: return null
            createEntity(host, port, secret = secret, type = "MTProto")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse tg:// URI", e)
            null
        }
    }

    private fun parseSocks5Uri(uri: String): MtProtoProxyEntity? {
        return try {
            val parsed = URI(uri.replace("socks5://", "http://"))
            val host = parsed.host ?: return null
            val port = if (parsed.port == -1) 1080 else parsed.port
            val userInfo = parsed.userInfo
            var username = ""
            var password = ""
            if (userInfo != null && userInfo.contains(":")) {
                val parts = userInfo.split(":", limit = 2)
                username = parts[0]
                password = parts[1]
            }
            createEntity(host, port, username = username, password = password, type = "SOCKS5")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse socks5:// URI", e)
            null
        }
    }

    private fun parseHttpUri(uri: String): MtProtoProxyEntity? {
        return try {
            val parsed = URI(uri)
            val host = parsed.host ?: return null
            val port = if (parsed.port == -1) 8080 else parsed.port
            val userInfo = parsed.userInfo
            var username = ""
            var password = ""
            if (userInfo != null && userInfo.contains(":")) {
                val parts = userInfo.split(":", limit = 2)
                username = parts[0]
                password = parts[1]
            }
            createEntity(host, port, username = username, password = password, type = "HTTP")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse http:// URI", e)
            null
        }
    }

    /**
     * Парсинг формата из Telegram-каналов:
     *   Server: 185.170.114.22
     *   Port: 443
     *   Secret: ee6a0333...
     *   @ProxyMTProto
     */
    private fun parseKeyValueFormat(text: String): MtProtoProxyEntity? {
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

        var host: String? = null
        var port: Int? = null
        var secret: String? = null
        var type: String = "MTProto"

        for (line in lines) {
            // Пропускаем @mentions
            if (line.startsWith("@")) continue
            // Пропускаем строки с "Link:" (tg://)
            if (line.startsWith("Link:", ignoreCase = true)) continue
            if (line.startsWith("tg://")) continue

            val parts = line.split(":", limit = 2)
            if (parts.size != 2) continue

            val key = parts[0].trim().lowercase()
            val value = parts[1].trim()

            when {
                key in listOf("server", "ip", "host", "адрес") -> {
                    // Проверить что это не "Unknown" / "недоступен"
                    if (value.equals("unknown", ignoreCase = true) ||
                        value.equals("недоступен", ignoreCase = true) ||
                        value == "—" || value == "-") {
                        Log.d(TAG, "Skipping proxy with unknown host")
                        return null
                    }
                    // Убрать возможные "(MTProto)" / "(SOCKS)" в конце
                    host = value.replace(Regex("\\s*\\([^)]*\\)\\s*$"), "").trim()
                }
                key == "port" || key == "порт" -> {
                    port = value.toIntOrNull()
                }
                key == "secret" || key == "секрет" -> {
                    secret = value
                }
                key == "type" || key == "тип" -> {
                    when (value.lowercase()) {
                        "socks5", "socks" -> type = "SOCKS5"
                        "http" -> type = "HTTP"
                        "mtproto" -> type = "MTProto"
                    }
                }
                key == "username" || key == "user" || key == "логин" -> {
                    // Для SOCKS5/HTTP — это username
                }
                key == "password" || key == "pass" || key == "пароль" -> {
                    // Для SOCKS5/HTTP — это password
                }
            }
        }

        if (host == null || port == null) {
            Log.w(TAG, "KV parse failed: missing host or port")
            return null
        }

        return when (type) {
            "MTProto" -> {
                if (secret.isNullOrEmpty()) {
                    Log.w(TAG, "MTProto proxy missing secret")
                    null
                } else {
                    createEntity(host, port, secret = secret, type = "MTProto")
                }
            }
            else -> createEntity(host, port, type = type)
        }
    }

    /**
     * Прямой ввод: host:port:secret (MTProto) или host:port (SOCKS5)
     */
    private fun parseDirect(input: String, source: String): MtProtoProxyEntity? {
        val parts = input.split(":")
        return when (parts.size) {
            3 -> {
                val host = parts[0].trim()
                val port = parts[1].trim().toIntOrNull() ?: return null
                val secret = parts[2].trim()
                createEntity(host, port, secret = secret, type = "MTProto")
            }
            2 -> {
                val host = parts[0].trim()
                val port = parts[1].trim().toIntOrNull() ?: return null
                createEntity(host, port, type = "SOCKS5")
            }
            else -> {
                Log.w(TAG, "Unknown format: ${input.take(50)}")
                null
            }
        }
    }

    private fun createEntity(
        host: String,
        port: Int,
        secret: String = "",
        username: String = "",
        password: String = "",
        type: String,
    ): MtProtoProxyEntity {
        val idInput = "$type:$host:$port:$secret:$username:$password"
        val id = generateId(idInput)
        return MtProtoProxyEntity(
            id = id,
            host = host,
            port = port,
            secret = secret,
            username = username,
            password = password,
            type = type,
            source = "MANUAL",
            addedAt = System.currentTimeMillis(),
        )
    }

    private fun generateId(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * Парсит текст (буфер обмена) и извлекает все прокси.
     * Поддерживает смешанные форматы:
     * - URL в одну строку
     * - Блоки Server/Port/Secret (разделённые пустой строкой)
     */
    fun parseMultiple(text: String, source: String = "IMPORT"): List<MtProtoProxyEntity> {
        val proxies = mutableListOf<MtProtoProxyEntity>()
        val seenIds = mutableSetOf<String>()

        // Разбить текст на блоки по пустым строкам
        val blocks = text.split(Regex("\n\\s*\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        for (block in blocks) {
            // Попытаться распарсить весь блок как единый KV формат
            if (block.contains("Secret:", ignoreCase = true) ||
                block.contains("Server:", ignoreCase = true)) {
                val proxy = parse(block, source)
                if (proxy != null && proxy.id !in seenIds) {
                    proxies.add(proxy)
                    seenIds.add(proxy.id)
                }
                continue
            }

            // Иначе — разбить на строки и попробовать каждую как отдельный URL
            val lines = block.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            for (line in lines) {
                val proxy = parse(line, source)
                if (proxy != null && proxy.id !in seenIds) {
                    proxies.add(proxy)
                    seenIds.add(proxy.id)
                }
            }
        }

        Log.i(TAG, "Parsed ${proxies.size} proxies from text (${text.length} chars)")
        return proxies
    }
}
