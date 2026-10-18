package com.vladimir.messenger.data.heartgame

import com.vladimir.messenger.data.diagnostics.DiagnosticOperation

/**
 * Когда телефон рассылает запись сердечек. Чистая логика без Android — покрыта JVM-тестом.
 *
 * Правила:
 * - Синхронизация идёт ВСЕГДА, даже если игра выключена: таблица лидеров должна
 *   сходиться у всех участников, а не только у тех, кто играет.
 * - Частота редкая: общая рассылка не чаще раза в [SYNC_INTERVAL_MS] (6 часов).
 * - Новенький онлайн: точечная отправка этому контакту, но не чаще раза в
 *   [SYNC_INTERVAL_MS] на контакт и не чаще раза в [NEWCOMER_MIN_GAP_MS] в целом
 *   (защита от всплеска при переподключении многих контактов сразу).
 * - Телефон должен быть свободен: не включён режим энергосбережения и не идёт
 *   тяжёлая фоновая работа (передача файлов, историй, APK).
 */
object HeartSyncPolicy {
    const val SYNC_INTERVAL_MS = 6L * 60 * 60 * 1000
    const val NEWCOMER_MIN_GAP_MS = 2L * 60 * 1000

    /** Как часто ядро спрашивает политику. Сама рассылка всё равно не чаще [SYNC_INTERVAL_MS]. */
    const val CHECK_PERIOD_MS = 30L * 60 * 1000

    /** Операции, которые считаются «телефон занят другой работой». */
    val BUSY_OPERATIONS: Set<DiagnosticOperation> = setOf(
        DiagnosticOperation.FILE_PUMP,
        DiagnosticOperation.GROUP_FILE_PUMP,
        DiagnosticOperation.APK_PUMP,
        DiagnosticOperation.STORY_PUMP,
    )

    /** Прошёл ли интервал. Часы назад (пользователь перевёл время) считаем просроченными. */
    fun isDue(lastAtMs: Long, nowMs: Long, intervalMs: Long = SYNC_INTERVAL_MS): Boolean {
        if (lastAtMs <= 0L || nowMs < lastAtMs) return true
        return nowMs - lastAtMs >= intervalMs
    }

    /** Можно ли отправлять сейчас с точки зрения загрузки телефона. */
    fun isPhoneFree(powerSaveOn: Boolean, busyInFlight: Int): Boolean =
        !powerSaveOn && busyInFlight == 0

    /** Сколько операций из [BUSY_OPERATIONS] выполняется прямо сейчас. */
    fun busyInFlight(inFlightByOperation: Map<DiagnosticOperation, Int>): Int =
        BUSY_OPERATIONS.sumOf { inFlightByOperation[it] ?: 0 }

    /** Можно ли отправить новенькому: и его личный интервал, и общий антиспам-зазор. */
    fun newcomerAllowed(peerLastAtMs: Long, lastNewcomerAtMs: Long, nowMs: Long): Boolean =
        isDue(lastNewcomerAtMs, nowMs, NEWCOMER_MIN_GAP_MS) &&
            isDue(peerLastAtMs, nowMs, SYNC_INTERVAL_MS)
}
