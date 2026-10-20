package com.vladimir.messenger.data.profile

import android.content.Context
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Эмодзи-статусы у профиля: свой и чужие.
 *
 * Свой статус ставится одним значком из палитры и объявляется сети роевым
 * пакетом `emst` (как @имя и аватар: контакты получают и передают дальше с
 * ограничением прыжков, поэтому статус виден не только прямым контактам).
 * Пустой статус — отзыв: у всех он убирается. Свежесть решает метка времени
 * владельца: старый пакет не затирает новый статус.
 */
object EmojiStatusStore {
    private const val PREFS = "apu_emoji_status"
    private const val KEY_MINE = "mine"
    private const val KEY_ENTRIES = "entries"

    /** Палитра статусов: настроение и занятие, без осуждающих значков. */
    val PALETTE: List<String> = listOf(
        "😀", "😎", "🤔", "😴", "🌙",
        "💼", "☕", "📚", "🎧", "🎮",
        "🏖", "✈️", "🚗", "🏃", "🧘",
        "💪", "❤️", "🔥", "☀️", "🍕",
    )

    private val _mine = MutableStateFlow("")

    /** Мой статус: эмодзи или пустая строка (статуса нет). */
    val mine: StateFlow<String> = _mine.asStateFlow()

    private val _peers = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Узел → его эмодзи-статус. */
    val peers: StateFlow<Map<String, String>> = _peers.asStateFlow()

    /** Метки свежести по узлам: nodeId -> updatedAtMs (в память, не в файл). */
    private val peerUpdatedAt = mutableMapOf<String, Long>()

    fun init(context: Context) {
        val prefs = prefs(context)
        _mine.value = prefs.getString(KEY_MINE, null).orEmpty()
        _peers.value = decode(prefs.getString(KEY_ENTRIES, null))
    }

    /** Поставить или убрать (пустая строка) свой статус. */
    fun setMine(context: Context, emoji: String) {
        val clean = cleanEmoji(emoji)
        _mine.value = clean
        prefs(context).edit().putString(KEY_MINE, clean).apply()
    }

    fun mineValue(context: Context): String =
        _mine.value.ifBlank { prefs(context).getString(KEY_MINE, null).orEmpty() }

    /**
     * Чужое объявление статуса. Принимается только свежее уже известного;
     * пустой значок — отзыв, запись убирается.
     */
    fun applyPeer(context: Context, nodeId: String, emoji: String, updatedAtMs: Long) {
        if (nodeId.isBlank() || updatedAtMs < 0L) return
        val last = peerUpdatedAt[nodeId] ?: 0L
        if (updatedAtMs < last) return
        peerUpdatedAt[nodeId] = updatedAtMs
        val clean = cleanEmoji(emoji)
        val next = _peers.value.toMutableMap()
        if (clean.isEmpty()) {
            if (next.remove(nodeId) == null) return
        } else {
            if (next[nodeId] == clean) return
            next[nodeId] = clean
        }
        _peers.value = next
        prefs(context).edit().putString(KEY_ENTRIES, encode(next)).apply()
    }

    /** Один значок: короткая строка без служебных символов и разделителей. */
    fun cleanEmoji(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > 16) return ""
        if (trimmed.any { it.isISOControl() || it == '|' || it == '\n' }) return ""
        return trimmed
    }

    private fun encode(entries: Map<String, String>): String =
        entries.entries.joinToString("\n") { (nodeId, emoji) ->
            nodeId + "|" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(emoji.toByteArray(Charsets.UTF_8))
        }

    private fun decode(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val result = linkedMapOf<String, String>()
        for (line in raw.lineSequence()) {
            val parts = line.split('|')
            if (parts.size != 2) continue
            val emoji = runCatching {
                String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
            }.getOrNull() ?: continue
            val clean = cleanEmoji(emoji)
            if (parts[0].isNotBlank() && clean.isNotEmpty()) result[parts[0]] = clean
        }
        return result
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
