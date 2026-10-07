package com.vladimir.messenger.data.security

import android.content.Context

/**
 * «Сообщения от собеседника не открываются» — пометка для ЧЕЛОВЕКА.
 *
 * Зачем: конверт не вскрылся, потому что собеседник запечатал для нашей
 * ПРЕЖНЕЙ копии ключа (он переустановил приложение, восстановил профиль или у
 * него остался старый QR). Лечится это только на его стороне: пусть заново
 * отсканирует наш QR-код. Раньше об этом знал лишь отчёт «Логи» — то есть
 * человек видел «сообщения не приходят» и не понимал, что делать.
 *
 * Здесь живёт ровно то, что нужно для подсказки: узел собеседника, когда
 * заметили и сколько раз. Ни текста, ни имён, ни ключей — только адрес узла,
 * который и так лежит в переписке. Пометка сама гаснет через [TTL_MS] и
 * снимается, как только от собеседника пришло нормально вскрытое сообщение.
 */
object KeyDesyncNotice {

    private const val PREFS = "apu_key_desync"
    private const val PREFIX = "n:"

    /** Сколько собеседников помним: больше и не нужно, это подсказка, не журнал. */
    private const val MAX_CONTACTS = 64

    /** Через сутки пометка устаревает: человек мог уже всё починить. */
    const val TTL_MS = 24L * 60 * 60 * 1000

    /** Запомнить: от этого собеседника сообщение не вскрылось. */
    fun note(context: Context, nodeId: String, nowMs: Long = System.currentTimeMillis()) {
        if (!nodeId.startsWith("pk_")) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = state(prefs, nodeId, nowMs) ?: 0
        prefs.edit().putString(PREFIX + nodeId, "${seen + 1}|$nowMs").apply()
        prune(prefs, nodeId, nowMs)
    }

    /** Пометка «не открывается» для этого собеседника (false — нечего показывать). */
    fun isPending(context: Context, nodeId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!nodeId.startsWith("pk_")) return false
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return state(prefs, nodeId, nowMs) != null
    }

    /** Сколько раз подряд не вскрывалось (для честной подписи), 0 — не было. */
    fun countOf(context: Context, nodeId: String, nowMs: Long = System.currentTimeMillis()): Int {
        if (!nodeId.startsWith("pk_")) return 0
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return state(prefs, nodeId, nowMs) ?: 0
    }

    /** Переписка с ним снова открывается — подсказка больше не нужна. */
    fun clear(context: Context, nodeId: String) {
        if (!nodeId.startsWith("pk_")) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(PREFIX + nodeId)) prefs.edit().remove(PREFIX + nodeId).apply()
    }

    /** Число «счётчик|время» либо null, если пометки нет или она устарела. */
    private fun state(
        prefs: android.content.SharedPreferences,
        nodeId: String,
        nowMs: Long,
    ): Int? {
        val raw = prefs.getString(PREFIX + nodeId, null) ?: return null
        val parts = raw.split('|')
        if (parts.size != 2) {
            prefs.edit().remove(PREFIX + nodeId).apply()
            return null
        }
        val count = parts[0].toIntOrNull() ?: return null
        val at = parts[1].toLongOrNull() ?: return null
        if (nowMs - at > TTL_MS || nowMs < at) {
            // Устарело (или часы уехали назад) — молча убираем, чтобы не пугать.
            prefs.edit().remove(PREFIX + nodeId).apply()
            return null
        }
        return count
    }

    /**
     * Держим список маленьким: лишние — самые давние. Без этого исключённые
     * собеседники копились бы годами в настройках телефона.
     */
    private fun prune(prefs: android.content.SharedPreferences, keepNodeId: String, nowMs: Long) {
        val entries = prefs.all
            .filterKeys { it.startsWith(PREFIX) }
            .mapNotNull { (key, value) ->
                val at = (value as? String)?.split('|')?.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
                Triple(key, at, key == PREFIX + keepNodeId)
            }
        val expired = entries.filter { nowMs - it.second > TTL_MS }
        val overflow = entries
            .filterNot { it.third }
            .sortedBy { it.second }
            .take((entries.size - MAX_CONTACTS).coerceAtLeast(0))
        val doomed = (expired + overflow).map { it.first }.toSet()
        if (doomed.isEmpty()) return
        val editor = prefs.edit()
        doomed.forEach { editor.remove(it) }
        editor.apply()
    }
}
