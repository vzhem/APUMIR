package com.vladimir.messenger.service

/**
 * Ритм доставки: как часто приложение забирает события ядра и досылает очередь.
 *
 * Зачем отдельный файл. Владелец 2026-10-29: «смена аватарки у друга видна
 * сразу, а сообщения идут с задержками». Смена аватарки уходила тем же
 * транспортом, что и текст, но её не ждали: она уезжала на живое соединение и
 * сразу применялась. Текст ждал трёх вещей:
 *
 *  1. **Опрос шины событий.** Kotlin забирает события ядра раз в
 *     [IDLE_POLL_MS] пять секунд, если очередь пуста. Всё, что пришло за эти
 *     пять секунд, включая входящее сообщение, ждало следующего круга.
 *  2. **Слив офлайн-очереди.** Недоставленное сообщение повторялось раз в
 *     минуту (три круга файлового насоса по двадцать секунд).
 *  3. **Блокирующие повторы в обработчике событий.** Повтор исходящих шёл
 *     прямо в обработчике, а вызов ядра ждёт до десяти секунд. Пока он ждал,
 *     входящие не разбирались вовсе.
 *
 * Здесь только арифметика решения, без Android: её проверяют обычные
 * JVM-тесты (`DeliveryCadenceTest`). Кто и когда вызывает — `CoreServerService`,
 * `ChatOutboxPump` и `MainActivity`.
 */
object DeliveryCadence {

    /** События идут пучком (файл, история): работать коротким шагом. */
    const val BUSY_POLL_MS = 100L

    /** Приложение на экране: человек ждёт ответа сразу, как от аватарки. */
    const val FOREGROUND_POLL_MS = 250L

    /** Только что была сеть или люди: ещё «горячо», но экран не смотрят. */
    const val BACKGROUND_ACTIVE_POLL_MS = 1_000L

    /** Тишина: пять секунд — это прежний шаг, батарею не трогаем. */
    const val IDLE_POLL_MS = 5_000L

    /** Сколько секунд после последней активности считаем, что «горячо». */
    const val ACTIVITY_WINDOW_MS = 20_000L

    @Volatile
    private var lastActivityAtMs = 0L

    @Volatile
    private var foreground = false

    /** Монотонные миллисекунды: смена часов телефона ритм не меняет. */
    fun now(): Long = System.nanoTime() / 1_000_000L

    /** Что-то произошло (пакет, отправка, экран, сеть) — держим шаг коротким. */
    fun touch(nowMs: Long = now()) {
        lastActivityAtMs = nowMs
    }

    /** Приложение на экране или ушло в фон. */
    fun setForeground(onScreen: Boolean) {
        foreground = onScreen
        if (onScreen) touch()
    }

    fun isForeground(): Boolean = foreground

    /** Шаг опроса шины событий. */
    fun pollDelayMs(busy: Boolean): Long = pollDelay(busy, foreground, now() - lastActivityAtMs)

    /** То же, но время и состояние переданы снаружи — для тестов. */
    internal fun pollDelay(busy: Boolean, onScreen: Boolean, idleMs: Long): Long = when {
        busy -> BUSY_POLL_MS
        onScreen -> FOREGROUND_POLL_MS
        idleMs < ACTIVITY_WINDOW_MS -> BACKGROUND_ACTIVE_POLL_MS
        else -> IDLE_POLL_MS
    }
}

/**
 * Ритм слива офлайн-очереди (`ChatOutboxPump`).
 *
 * Повтор неудачной отправки — это попытка соединения, а она ждёт до десяти
 * секунд. Бить в неё каждую секунду нельзя: телефон будет занят попытками и
 * ничего не выиграет. Поэтому лестница: пока попытки удаются — сливаем быстро,
 * после неудачи отступаем, но потолок — минута, а не прежние минуты ожидания
 * следующего круга файлового насоса.
 */
object OutboxCadence {

    /** Очередь пуста: как часто проверять, не появилось ли что. */
    const val EMPTY_CHECK_MS = 15_000L

    /** Только что получилось: следующий сразу, очередь надо разгрузить. */
    const val DRAIN_MS = 2_000L

    /** Пока экран смотрят, неудачу повторяем быстрее. */
    val FOREGROUND_LADDER_MS = longArrayOf(5_000L, 10_000L, 30_000L, 60_000L)

    /** Экран погас: попытки дороже, но и ждать минутами нельзя. */
    val BACKGROUND_LADDER_MS = longArrayOf(15_000L, 30_000L, 60_000L, 120_000L)

    /**
     * @param hasQueued есть ли что досылать
     * @param sent сколько ушло в прошлом круге
     * @param failures сколько кругов подряд ничего не ушло
     * @param onScreen приложение на экране
     */
    fun nextDelayMs(hasQueued: Boolean, sent: Int, failures: Int, onScreen: Boolean): Long {
        if (!hasQueued) return EMPTY_CHECK_MS
        if (sent > 0) return DRAIN_MS
        val ladder = if (onScreen) FOREGROUND_LADDER_MS else BACKGROUND_LADDER_MS
        return ladder[failures.coerceIn(0, ladder.lastIndex)]
    }

    /** Счётчик неудач: успех сбрасывает лестницу в начало. */
    fun nextFailures(current: Int, sent: Int): Int = if (sent > 0) 0 else (current + 1).coerceAtMost(64)
}
