package com.vladimir.messenger.domain.model

enum class ProxySource { MANUAL, BOT, CHANNEL, WEB }
enum class ProxyType { MTProto, SOCKS5, HTTP }

data class MtProtoProxy(
    val id: String,
    val host: String,
    val port: Int,
    val secret: String = "",       // Для MTProto — secret, для SOCKS5/HTTP может быть пустым
    val username: String = "",     // Для SOCKS5/HTTP с авторизацией
    val password: String = "",     // Для SOCKS5/HTTP с авторизацией
    val type: ProxyType = ProxyType.MTProto,
    val source: ProxySource = ProxySource.MANUAL,
    val addedAt: Long = System.currentTimeMillis(),
    val lastCheck: Long = 0L,
    val failCount: Int = 0,
    val successCount: Int = 0,
    val isActive: Boolean = false,
) {
    fun toTgUri(): String = when (type) {
        ProxyType.MTProto -> "tg://proxy?server=$host&port=$port&secret=$secret"
        ProxyType.SOCKS5 -> if (username.isNotEmpty()) "socks5://$username:$password@$host:$port" else "socks5://$host:$port"
        ProxyType.HTTP -> if (username.isNotEmpty()) "http://$username:$password@$host:$port" else "http://$host:$port"
    }

    fun isSocksOrHttp(): Boolean = type == ProxyType.SOCKS5 || type == ProxyType.HTTP
}
