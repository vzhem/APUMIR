package com.vladimir.messenger.data.swarm

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Рекомендованный сервис покупки индивидуальных IPv6-прокси (SOCKS5 / HTTP),
 * отображаемый в соседней карточке раздела «Настройки → Сеть».
 * Пользователь вправе использовать его или любой другой провайдер/свой сервер.
 */
const val IPV6_RECOMMENDED_SHOP_URL = "https://ru.shopproxy.net/buy-proxy/ipv6/"

/** Протокол прокси, который выдаёт продавец при покупке IPv6. */
enum class Ipv6ProxyProtocol(val displayCode: String) {
    SOCKS5("SOCKS5"),
    HTTP("HTTP(S)");

    companion object {
        fun fromStorage(raw: String?): Ipv6ProxyProtocol = when (raw?.trim()?.uppercase()) {
            "HTTP", "HTTPS", "HTTP(S)" -> HTTP
            else -> SOCKS5
        }
    }
}

/**
 * Пользовательские сетевые реквизиты купленного IPv6-прокси.
 *
 * `host` всегда хранится без квадратных скобок (`2a00:1fa0::1`, `185.10.20.30`,
 * `gate.example.com`), чтобы одинаково приниматься `InetSocketAddress` и
 * Rust-мостом `setMqttSocks5Proxy`.
 */
data class Ipv6ProxyConfig(
    val enabled: Boolean = false,
    val protocol: Ipv6ProxyProtocol = Ipv6ProxyProtocol.SOCKS5,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
) {
    val hasAuth: Boolean
        get() = username.isNotEmpty()

    val isIpv6Host: Boolean
        get() = host.contains(':')

    val isConfigured: Boolean
        get() = Ipv6ProxyParser.isValidHost(host) &&
            port in 1..65535 &&
            Ipv6ProxyParser.areCredentialsValid(username, password)

    val isActive: Boolean
        get() = enabled && isConfigured

    fun formattedEndpoint(): String =
        if (!isConfigured) ""
        else if (isIpv6Host) "[$host]:$port"
        else "$host:$port"

    /** Безопасная сводка без пароля и логина для экрана настроек и логов. */
    fun safeSummary(): String =
        if (!isConfigured) ""
        else "${protocol.displayCode} · ${formattedEndpoint()}"
}

/**
 * Разбор и проверка реквизитов IPv6-прокси во всех форматах, которые выдают
 * магазины прокси (`shopproxy.net`, `proxy6`, личные серверы `3proxy`/`xray`):
 *  - отдельные поля (хост, порт, логин, пароль, протокол);
 *  - `ip:port:login:password` и `[2a00:...]:port:login:password`;
 *  - `login:password@ip:port` и `login:password@[2a00:...]:port`;
 *  - `socks5://login:password@host:port`, `http://login:password@host:port`;
 *  - `ip:port` и `[2a00:...]:port` (привязка по IP без пароля);
 *  - многострочная карточка из личного кабинета (`IP:`, `Порт:`, `Логин:`, `Пароль:`).
 */
object Ipv6ProxyParser {

    private val HOST_DNS_IPV4_REGEX = Regex("^[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$")
    private val IPV6_CHARS_REGEX = Regex("^[0-9A-Fa-f:.]{2,45}$")
    private val KV_KEYS = setOf(
        "ip", "host", "server", "address", "сервер", "адрес", "хост",
        "port", "порт", "socks5", "http", "https",
        "type", "protocol", "тип", "протокол",
        "login", "user", "username", "логин", "пользователь",
        "password", "pass", "пароль",
    )

    fun normalizeHost(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val clean = if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length > 2) {
            trimmed.substring(1, trimmed.length - 1).trim()
        } else {
            trimmed
        }
        if (clean.isEmpty() || clean.length > 253) return null
        if (clean.any { it.isWhitespace() || it == '|' || it == '/' || it == '?' || it == '#' || it == '@' || it.code < 32 }) {
            return null
        }
        return if (clean.contains(':')) {
            if (!IPV6_CHARS_REGEX.matches(clean)) return null
            val colonCount = clean.count { it == ':' }
            if (colonCount < 2) return null
            clean.lowercase()
        } else {
            if (!HOST_DNS_IPV4_REGEX.matches(clean) || clean.contains("..")) return null
            clean
        }
    }

    fun isValidHost(host: String): Boolean = normalizeHost(host) == host

    fun areCredentialsValid(username: String, password: String): Boolean {
        if (username.isEmpty() && password.isNotEmpty()) return false
        if (username.any { it == '\r' || it == '\n' } || password.any { it == '\r' || it == '\n' }) {
            return false
        }
        val userBytes = username.toByteArray(StandardCharsets.UTF_8).size
        val passBytes = password.toByteArray(StandardCharsets.UTF_8).size
        return userBytes <= 255 && passBytes <= 255
    }

    /**
     * Собрать конфигурацию из отдельных полей формы.
     * Если человек вставил готовую строку `ip:port:user:pass` прямо в поле адреса
     * и оставил порт пустым — разбираем её автоматически.
     */
    fun fromFields(
        enabled: Boolean,
        protocol: Ipv6ProxyProtocol,
        hostInput: String,
        portInput: String,
        usernameInput: String,
        passwordInput: String,
    ): Ipv6ProxyConfig? {
        val rawHost = hostInput.trim()
        val rawPort = portInput.trim()
        val cleanUser = usernameInput.trim()
        val cleanPass = passwordInput.trimEnd('\r', '\n')

        if (rawPort.isEmpty() && rawHost.isNotEmpty()) {
            val parsed = parseQuickInput(rawHost, fallbackProtocol = protocol)
            if (parsed != null) {
                val finalUser = cleanUser.ifEmpty { parsed.username }
                val finalPass = if (cleanUser.isNotEmpty() && cleanPass.isNotEmpty()) cleanPass else parsed.password
                val merged = parsed.copy(
                    enabled = enabled,
                    username = finalUser,
                    password = finalPass,
                )
                return merged.takeIf { it.isConfigured }
            }
        }

        val host = normalizeHost(rawHost) ?: return null
        val port = rawPort.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        if (!areCredentialsValid(cleanUser, cleanPass)) return null

        return Ipv6ProxyConfig(
            enabled = enabled,
            protocol = protocol,
            host = host,
            port = port,
            username = cleanUser,
            password = if (cleanUser.isEmpty()) "" else cleanPass,
        )
    }

    /**
     * Разобрать готовую строку или многострочный блок из личного кабинета провайдера.
     */
    fun parseQuickInput(
        raw: String,
        fallbackProtocol: Ipv6ProxyProtocol = Ipv6ProxyProtocol.SOCKS5,
    ): Ipv6ProxyConfig? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.contains('\n')) {
            return parseMultilineBlock(trimmed, fallbackProtocol)
        }

        var protocol = fallbackProtocol
        var body = trimmed
        if (body.contains("://")) {
            val scheme = body.substringBefore("://").trim().lowercase()
            protocol = when (scheme) {
                "socks5", "socks5h", "socks" -> Ipv6ProxyProtocol.SOCKS5
                "http", "https" -> Ipv6ProxyProtocol.HTTP
                else -> return null
            }
            body = body.substringAfter("://")
        }
        body = body.substringBefore('/').substringBefore('?').substringBefore('#').trim()
        if (body.isEmpty()) return null

        // Формат login:password@host:port (разделяем по последнему '@', если в пароле есть '@').
        // Если после '@' нет валидного host:port — значит '@' входит в сам пароль формата
        // ip:port:login:p@ss, поэтому продолжаем позиционный разбор ниже.
        if (body.contains('@')) {
            val atIdx = body.lastIndexOf('@')
            val authPart = body.substring(0, atIdx)
            val endpointPart = body.substring(atIdx + 1)
            val hostPort = splitHostAndPort(endpointPart)
            val userPass = splitUserPass(authPart)
            if (hostPort != null && userPass != null) {
                buildValidated(
                    protocol = protocol,
                    rawHost = hostPort.first,
                    port = hostPort.second,
                    username = urlDecode(userPass.first),
                    password = urlDecode(userPass.second),
                )?.let { return it }
            }
            if (trimmed.contains("://")) return null
        }

        // Формат с IPv6 в скобках: [2a00:1fa0::1]:1080 или [2a00:1fa0::1]:1080:login:password.
        if (body.startsWith("[")) {
            val closeIdx = body.indexOf(']')
            if (closeIdx <= 1) return null
            val hostRaw = body.substring(1, closeIdx)
            val after = body.substring(closeIdx + 1)
            if (!after.startsWith(":")) return null
            val tail = after.drop(1)
            val tailParts = tail.split(":", limit = 3)
            val port = tailParts[0].trim().toIntOrNull() ?: return null
            return when (tailParts.size) {
                1 -> buildValidated(protocol, hostRaw, port, "", "")
                3 -> buildValidated(protocol, hostRaw, port, tailParts[1].trim(), tailParts[2])
                else -> null
            }
        }

        // Формат host:port или host:port:login:password (для IPv4 и доменных шлюзов).
        val parts = body.split(":", limit = 4)
        return when (parts.size) {
            2 -> {
                val hostRaw = parts[0].trim()
                val port = parts[1].trim().toIntOrNull() ?: return null
                buildValidated(protocol, hostRaw, port, "", "")
            }
            4 -> {
                val hostRaw = parts[0].trim()
                // Отсекаем голый IPv6 без скобок вида 2a00:1fa0:4000:1, чтобы не принять 2-й хекстет за порт.
                if (!hostRaw.contains('.') && !hostRaw.equals("localhost", ignoreCase = true)) {
                    return null
                }
                val port = parts[1].trim().toIntOrNull() ?: return null
                buildValidated(protocol, hostRaw, port, parts[2].trim(), parts[3])
            }
            else -> null
        }
    }

    /**
     * Локальные и внутрисетевые адреса всегда ходят напрямую, мимо внешнего прокси
     * (локальные мосты 127.0.0.1, обмен в домашнем Wi-Fi, mDNS).
     */
    fun isBypassHost(rawHost: String?): Boolean {
        val host = rawHost?.trim()?.trimStart('[')?.trimEnd(']')?.lowercase() ?: return true
        if (host.isEmpty()) return true
        if (host == "localhost" || host.endsWith(".local") || host == "::1" || host == "0:0:0:0:0:0:0:1") {
            return true
        }
        if (host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("169.254.")) {
            return true
        }
        if (host.startsWith("172.")) {
            val second = host.removePrefix("172.").substringBefore('.').toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        if (host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd")) {
            if (host.contains(':')) return true
        }
        return false
    }

    private fun parseMultilineBlock(
        block: String,
        fallbackProtocol: Ipv6ProxyProtocol,
    ): Ipv6ProxyConfig? {
        val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size == 1) return parseQuickInput(lines[0], fallbackProtocol)

        var protocol = fallbackProtocol
        var host: String? = null
        var port: Int? = null
        var user = ""
        var pass = ""

        for (line in lines) {
            if (!line.contains(':')) continue
            val rawKey = line.substringBefore(':').trim().lowercase()
            // Если внутри блока встретилась готовая строка ip:port:user:pass (не ключ формы) — берём её.
            if (!line.contains(' ') && rawKey !in KV_KEYS) {
                parseQuickInput(line, protocol)?.let { return it }
            }
            val key = rawKey
            val value = line.substringAfter(':').trim()
            when (key) {
                "ip", "host", "server", "address", "сервер", "адрес", "хост" -> host = value
                "port", "порт" -> port = value.toIntOrNull()
                "socks5", "socks5 порт", "порт socks5" -> {
                    protocol = Ipv6ProxyProtocol.SOCKS5
                    value.toIntOrNull()?.let { port = it }
                }
                "http", "https", "http порт", "порт http" -> {
                    protocol = Ipv6ProxyProtocol.HTTP
                    value.toIntOrNull()?.let { port = it }
                }
                "type", "protocol", "тип", "протокол" -> {
                    protocol = Ipv6ProxyProtocol.fromStorage(value)
                }
                "login", "user", "username", "логин", "пользователь" -> user = value
                "password", "pass", "пароль" -> pass = value
            }
        }

        val resolvedHost = host ?: return null
        val resolvedPort = port ?: return null
        return buildValidated(protocol, resolvedHost, resolvedPort, user, pass)
    }

    private fun splitHostAndPort(endpoint: String): Pair<String, Int>? {
        val trimmed = endpoint.trim()
        if (trimmed.startsWith("[")) {
            val end = trimmed.indexOf(']')
            if (end <= 1) return null
            val host = trimmed.substring(1, end)
            val rest = trimmed.substring(end + 1)
            if (!rest.startsWith(":")) return null
            val port = rest.drop(1).toIntOrNull() ?: return null
            return host to port
        }
        val colon = trimmed.lastIndexOf(':')
        if (colon <= 0) return null
        val host = trimmed.substring(0, colon)
        val port = trimmed.substring(colon + 1).toIntOrNull() ?: return null
        return host to port
    }

    private fun splitUserPass(auth: String): Pair<String, String>? {
        if (auth.isEmpty()) return null
        val colon = auth.indexOf(':')
        return if (colon < 0) {
            auth to ""
        } else {
            val user = auth.substring(0, colon)
            val pass = auth.substring(colon + 1)
            if (user.isEmpty()) null else user to pass
        }
    }

    private fun urlDecode(value: String): String =
        try {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            // Непроцентная строка вроде пароля со знаком '%' остаётся как есть.
            value
        }

    private fun buildValidated(
        protocol: Ipv6ProxyProtocol,
        rawHost: String,
        port: Int,
        username: String,
        password: String,
    ): Ipv6ProxyConfig? {
        val cleanHost = normalizeHost(rawHost) ?: return null
        if (port !in 1..65535) return null
        if (!areCredentialsValid(username, password)) return null
        return Ipv6ProxyConfig(
            enabled = true,
            protocol = protocol,
            host = cleanHost,
            port = port,
            username = username,
            password = if (username.isEmpty()) "" else password,
        )
    }
}
