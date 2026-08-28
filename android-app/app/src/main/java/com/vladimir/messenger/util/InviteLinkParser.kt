package com.vladimir.messenger.util

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Parses APUMIR invite links from external apps, QR codes, Telegram links and legacy formats.
 *
 * Supported forms:
 * - p2pmessenger://add?node_id=pk_...
 * - p2pmessenger://add?nodeId=pk_...
 * - p2pm://connect?node=pk_...
 * - p2p://invite/pk_... and p2p://key/pk_... (the QR the app shows on the profile)
 * - bare pk_... (a key-only QR)
 * - https://t.me/p2p_messenger_relay_bot?start=pk_...
 * - https://t.me/p2p_messenger_relay_bot?start=add_pk_...
 * - https://t.me/P2PMessengerBot?start=add_pk_... (legacy)
 */
object InviteLinkParser {
    const val TELEGRAM_BOT_USERNAME = "p2p_messenger_relay_bot"
    const val LEGACY_TELEGRAM_BOT_USERNAME = "P2PMessengerBot"

    data class Invite(
        val nodeId: String,
        val publicKey: String? = null,
        val displayName: String? = null,
        val source: Source,
        val original: String,
    )

    enum class Source {
        APP_LINK,
        TELEGRAM_LINK,
        LEGACY_TELEGRAM_LINK,
        RUST_CONNECT_LINK,
    }

    fun parse(raw: String?): Invite? {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return null

        // QR может содержать голый ключ контакта — это тоже приглашение.
        if (text.startsWith("pk_") && !text.any { it.isWhitespace() }) {
            return Invite(nodeId = text, source = Source.APP_LINK, original = text)
        }

        return try {
            val uri = URI(text)
            when (uri.scheme?.lowercase()) {
                "p2pmessenger" -> parseAppLink(uri, text)
                "p2pm" -> parseRustConnectLink(uri, text)
                "p2p" -> parseP2pLink(uri, text)
                "https", "http" -> parseHttpLink(uri, text)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Форматы, которые приложение само показывает в QR профиля: p2p://invite/pk_... */
    private fun parseP2pLink(uri: URI, original: String): Invite? {
        val host = uri.host?.lowercase() ?: return null
        if (host != "invite" && host != "key" && host != "connect") return null
        val query = parseQuery(uri.rawQuery)
        val nodeId = query.firstNonBlank("node", "node_id", "nodeId", "public_key", "publicKey")
            ?.normalizeNodeId()
            ?: uri.path.orEmpty().trim('/').trim().takeIf { it.isNotBlank() }
            ?: return null
        return Invite(
            nodeId = nodeId,
            publicKey = query.firstNonBlank("public_key", "publicKey")?.normalizeNodeId(),
            displayName = query.firstNonBlank("name", "display_name", "displayName"),
            source = Source.RUST_CONNECT_LINK,
            original = original,
        )
    }

    private fun parseAppLink(uri: URI, original: String): Invite? {
        if (!uri.host.equals("add", ignoreCase = true)) return null
        val query = parseQuery(uri.rawQuery)
        val nodeId = query.firstNonBlank("node_id", "nodeId", "node", "public_key", "publicKey")
            ?.normalizeNodeId()
            ?: return null
        val publicKey = query.firstNonBlank("public_key", "publicKey")?.normalizeNodeId()
        val displayName = query.firstNonBlank("name", "display_name", "displayName")
        return Invite(
            nodeId = nodeId,
            publicKey = publicKey,
            displayName = displayName,
            source = Source.APP_LINK,
            original = original,
        )
    }

    private fun parseRustConnectLink(uri: URI, original: String): Invite? {
        if (!uri.host.equals("connect", ignoreCase = true)) return null
        val query = parseQuery(uri.rawQuery)
        val nodeId = query.firstNonBlank("node", "node_id", "nodeId")
            ?.normalizeNodeId()
            ?: return null
        return Invite(
            nodeId = nodeId,
            publicKey = query.firstNonBlank("public_key", "publicKey")?.normalizeNodeId(),
            displayName = query.firstNonBlank("name", "display_name", "displayName"),
            source = Source.RUST_CONNECT_LINK,
            original = original,
        )
    }

    private fun parseHttpLink(uri: URI, original: String): Invite? {
        val host = uri.host?.lowercase() ?: return null
        if (host != "t.me" && host != "telegram.me") return null

        val botName = uri.path.orEmpty().trim('/').substringBefore('/')
        val source = when {
            botName.equals(TELEGRAM_BOT_USERNAME, ignoreCase = true) -> Source.TELEGRAM_LINK
            botName.equals(LEGACY_TELEGRAM_BOT_USERNAME, ignoreCase = true) -> Source.LEGACY_TELEGRAM_LINK
            else -> return null
        }

        val query = parseQuery(uri.rawQuery)
        val start = query["start"]?.firstOrNull()?.trim().orEmpty()
        if (start.isBlank()) return null

        val nodeId = start
            .removePrefix("add_")
            .removePrefix("node_")
            .normalizeNodeId()

        return Invite(
            nodeId = nodeId,
            publicKey = null,
            displayName = query.firstNonBlank("name", "display_name", "displayName"),
            source = source,
            original = original,
        )
    }

    private fun parseQuery(rawQuery: String?): Map<String, List<String>> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&')
            .filter { it.isNotBlank() }
            .map { part ->
                val key = part.substringBefore('=', part)
                val value = part.substringAfter('=', "")
                decode(key) to decode(value)
            }
            .groupBy({ it.first }, { it.second })
    }

    private fun Map<String, List<String>>.firstNonBlank(vararg keys: String): String? {
        for (key in keys) {
            val value = this[key]?.firstOrNull { it.isNotBlank() }
            if (!value.isNullOrBlank()) return value.trim()
        }
        return null
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun String.normalizeNodeId(): String = trim()
}
