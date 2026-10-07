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
 * Владелец 2026-10-07 (отчёт по v11.74.199 и v11.74.200): «обменялся QR в обе
 * стороны, переписка начала работать — а плашка появилась опять». Почему так
 * было: обмен ключами чинит НОВЫЕ сообщения, а в сети ещё летят СТАРЫЕ копии,
 * запечатанные прежним ключом (своя очередь у собеседника тоже была забита).
 * Каждая такая копия выглядела как «опять не вскрылось» и зажигала плашку с
 * первого же конверта. Теперь пометка — про «ничего не открывается», а не про
 * «одна копия не открылась»:
 *
 * - копия, пришедшая сразу после успешно открытого сообщения, — остаток в
 *   пути: не считается и человека не тревожит;
 * - плашка появляется только после [MIN_FAILS] подряд и лишь если за последние
 *   [WORKING_QUIET_MS] от собеседника НИЧЕГО не открылось (переписка не идёт);
 * - любое успешно открытое сообщение или действие человека обнуляет счётчик.
 *
 * Здесь живёт ровно то, что нужно для подсказки: узел собеседника, когда
 * заметили и сколько раз. Ни текста, ни имён, ни ключей — только адрес узла,
 * который и так лежит в переписке. Пометка сама гаснет через [TTL_MS].
 */
object KeyDesyncNotice {

    private const val PREFS = "apu_key_desync"
    private const val PREFIX = "n:"

    /** Сколько собеседников помним: больше и не нужно, это подсказка, не журнал. */
    private const val MAX_CONTACTS = 64

    /** Через сутки пометка устаревает: человек мог уже всё починить. */
    const val TTL_MS = 24L * 60 * 60 * 1000

    /**
     * Сколько конвертов подряд должно не вскрыться, чтобы показать плашку.
     * Один — это ещё «копия в пути», а не рассинхрон ключа.
     */
    const val MIN_FAILS = 3

    /**
     * Если от собеседника что-то открылось за это время — ключ рабочий, и
     * плашку показывать нельзя, чем бы ни были те нерасшифрованные конверты.
     */
    const val WORKING_QUIET_MS = 3L * 60 * 1000

    /**
     * Конверт, не вскрывшийся в первые секунды после успешного, — почти
     * наверняка старая копия в пути: не считаем его вовсе.
     */
    const val LEFTOVER_MS = 60L * 1000

    /** Запомнить: от этого собеседника сообщение не вскрылось. */
    fun note(context: Context, nodeId: String, nowMs: Long = System.currentTimeMillis()) {
        if (!nodeId.startsWith("pk_")) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val state = state(prefs, nodeId, nowMs) ?: State(0, nowMs, 0L)
        // Копия, прилетевшая следом за открытым сообщением, — остаток в пути:
        // ключ уже доказанно рабочий, и портить им статистику нельзя.
        if (isLeftoverCopy(state.lastOpenAt, nowMs)) return
        val updated = state.copy(failCount = state.failCount + 1, lastFailAt = nowMs)
        prefs.edit().putString(PREFIX + nodeId, updated.encode()).apply()
        prune(prefs, nodeId, nowMs)
    }

    /**
     * Переписка с ним открылась (или человек сам нажал «отправить ключ ещё
     * раз»): счётчик обнуляется, а время успеха запоминается — по нему
     * понимается, что старые копии в пути больше не повод для плашки.
     */
    fun opened(context: Context, nodeId: String, nowMs: Long = System.currentTimeMillis()) {
        if (!nodeId.startsWith("pk_")) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val state = state(prefs, nodeId, nowMs)
        val updated = State(
            failCount = 0,
            lastFailAt = state?.lastFailAt ?: 0L,
            lastOpenAt = nowMs,
        )
        prefs.edit().putString(PREFIX + nodeId, updated.encode()).apply()
    }

    /**
     * Открывалось ли от него что-нибудь за последние [windowMs]. Нужно приёмной
     * стороне: так отличаются «старые копии в пути» от настоящего рассинхрона.
     */
    fun isWorkingRecently(
        context: Context,
        nodeId: String,
        nowMs: Long = System.currentTimeMillis(),
        windowMs: Long = WORKING_QUIET_MS,
    ): Boolean {
        if (!nodeId.startsWith("pk_")) return false
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val state = state(prefs, nodeId, nowMs) ?: return false
        return state.lastOpenAt > 0L && nowMs - state.lastOpenAt <= windowMs
    }

    /** Пометка «не открывается» для этого собеседника (false — нечего показывать). */
    fun isPending(context: Context, nodeId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!nodeId.startsWith("pk_")) return false
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val state = state(prefs, nodeId, nowMs) ?: return false
        return shouldShow(state.failCount, state.lastOpenAt, nowMs)
    }

    /**
     * Чистое решение «показывать ли плашку» — без хранилища, чтобы его можно
     * было проверить тестом (владелец 2026-10-07: после обмена QR переписка
     * пошла, а плашка вернулась из-за одной застрявшей в пути копии).
     *
     * Показываем, только если нерасшифрованных конвертов набралось [MIN_FAILS]
     * И ничего не открывалось дольше [WORKING_QUIET_MS]: одного конверта и
     * работающей переписки для тревоги мало.
     */
    fun shouldShow(failCount: Int, lastOpenAt: Long, nowMs: Long): Boolean {
        if (failCount < MIN_FAILS) return false
        if (lastOpenAt <= 0L) return true
        return nowMs - lastOpenAt > WORKING_QUIET_MS
    }

    /**
     * Конверт, пришедший в первые [LEFTOVER_MS] после успешно открытого, —
     * почти наверняка старая копия: её не считаем вовсе.
     */
    fun isLeftoverCopy(lastOpenAt: Long, nowMs: Long): Boolean =
        lastOpenAt > 0L && nowMs - lastOpenAt <= LEFTOVER_MS

    /** Сколько раз подряд не вскрывалось (для честной подписи), 0 — не было. */
    fun countOf(context: Context, nodeId: String, nowMs: Long = System.currentTimeMillis()): Int {
        if (!nodeId.startsWith("pk_")) return 0
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return state(prefs, nodeId, nowMs)?.failCount ?: 0
    }

    /** Переписка с ним снова открывается — подсказка больше не нужна. */
    fun clear(context: Context, nodeId: String) {
        if (!nodeId.startsWith("pk_")) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(PREFIX + nodeId)) prefs.edit().remove(PREFIX + nodeId).apply()
    }

    /** Счётчик и время: `неВскрылось | когдаНеВскрылось | когдаОткрылось`. */
    private data class State(val failCount: Int, val lastFailAt: Long, val lastOpenAt: Long) {
        fun encode(): String = "$failCount|$lastFailAt|$lastOpenAt"
    }

    /**
     * Разбор записи: три поля у новых пометок, два — у старых (до 2026-10-07),
     * где времени успеха ещё не было.
     */
    private fun state(
        prefs: android.content.SharedPreferences,
        nodeId: String,
        nowMs: Long,
    ): State? {
        val raw = prefs.getString(PREFIX + nodeId, null) ?: return null
        val parts = raw.split('|')
        if (parts.size !in 2..3) {
            prefs.edit().remove(PREFIX + nodeId).apply()
            return null
        }
        val count = parts[0].toIntOrNull() ?: return null
        val at = parts[1].toLongOrNull() ?: return null
        val openedAt = parts.getOrNull(2)?.toLongOrNull() ?: 0L
        if (nowMs - at > TTL_MS || nowMs < at) {
            // Устарело (или часы уехали назад) — молча убираем, чтобы не пугать.
            prefs.edit().remove(PREFIX + nodeId).apply()
            return null
        }
        return State(count, at, openedAt)
    }

    /**
     * Держим список маленьким: лишние — самые давние. Без этого исключённые
     * собеседники копились бы годами в настройках телефона.
     */
    private fun prune(prefs: android.content.SharedPreferences, keepNodeId: String, nowMs: Long) {
        val entries = prefs.all
            .filterKeys { it.startsWith(PREFIX) }
            .mapNotNull { (key, value) ->
                val raw = value as? String ?: return@mapNotNull null
                val parts = raw.split('|')
                val at = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
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
