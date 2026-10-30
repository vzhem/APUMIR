package com.vladimir.messenger.service

/**
 * Когда приложение просыпается само, чтобы доставить уведомление.
 *
 * Зачем отдельный файл с чистой арифметикой. Механизм пробуждения — это
 * `AlarmManager`, `WorkManager` и разрешения системы, и проверить их здесь
 * нечем (телефона нет). Но самое хрупкое в нём — не вызовы Android, а
 * **решение, когда будить и с каким интервалом**: именно там раньше
 * получилось «288 пробуждений в сутки» на одном полюсе и «письмо ждёт час»
 * на другом. Это решение вынесено сюда и покрыто JVM-тестом
 * (`DeliveryWakePolicyTest`), а Android-обвязка только исполняет план.
 *
 * Как это устроено «как в Telegram». У Telegram письма будит сервер через
 * системный push. Здесь сервера нет, поэтому роль «системного толчка» играет
 * наше собственное расписание, и оно трёхуровневое:
 *
 *  1. **Сервис жив** — будильник-сторож раз в [WATCHDOG_MS]: если процесс
 *     тихо убьют без `onDestroy`, служба всё равно вернётся в пределах
 *     пятнадцати минут. Пока процесс жив, сторож ничего не делает (проверка
 *     в [shouldWakeNow]) — это дешёвая страховка, а не «тик».
 *  2. **Процесс убили или человек смахнул приложение** (`onDestroy`,
 *     `onTaskRemoved`) — приложение ЗНАЕТ, что уходит, и назначает быстрый
 *     подъём через [FAST_WAKE_MS]. Это и есть поведение «понимает и
 *     просыпается»: не через час, а через минуту-две.
 *  3. **Подняться не удалось** (запрет старта из фона, отказ foreground) —
 *     лестница [RETRY_LADDER_MS]: 1 → 5 → 15 → 30 минут. Дальше не растёт:
 *     вечно будить телефон раз в полчаса — это уже не доставка, а разряд.
 *
 * Точный будильник (`setExactAndAllowWhileIdle`) дорог по батарее, поэтому
 * он используется только когда он действительно нужен и разрешён:
 *  - пользователь не выключил «просыпаться ради доставки»;
 *  - телефон не держит приложение в оптимизации батареи (иначе система всё
 *    равно отложит пробуждение, и точность обманывает сама себя);
 *  - система разрешает точные будильники (`canScheduleExactAlarms`).
 * Если хоть что-то не так — план честно говорит `exact = false`, и интерфейс
 * показывает, какого разрешения не хватает (см. экран «Уведомления приходят
 * вовремя»).
 */
object DeliveryWakePolicy {

    /** Сторож, пока служба жива: тихая проверка раз в пятнадцать минут. */
    const val WATCHDOG_MS = 15 * 60_000L

    /**
     * Быстрый подъём после того, как процесс ушёл (смахнули приложение или
     * система убила службу): этим и отличается «как в Telegram» от «раз в час».
     */
    const val FAST_WAKE_MS = 2 * 60_000L

    /** Совсем без службы: редкая страховка, если лестница почему-то не сработала. */
    const val SAFETY_NET_MS = 60 * 60_000L

    /** Лестница, когда подняться не удалось. Дальше 30 минут не растёт. */
    val RETRY_LADDER_MS = longArrayOf(1 * 60_000L, 5 * 60_000L, 15 * 60_000L, 30 * 60_000L)

    /**
     * Через сколько сторож обязан считать службу мёртвой. Втрое больше
     * интервала сторожа: одно пропущенное пробуждение (Doze, занятый телефон)
     * не должно выглядеть как «приложение убито» и поднимать второй экземпляр.
     */
    const val STALE_AFTER_MS = 3 * WATCHDOG_MS

    /** Что известно про разрешения и настройку человека. */
    data class Conditions(
        /** Человек не выключил «просыпаться, чтобы доставлять вовремя». */
        val enabled: Boolean,
        /** Приложение выведено из оптимизации батареи. */
        val batteryUnrestricted: Boolean,
        /** Система разрешает точные будильники. */
        val exactAlarmsAllowed: Boolean,
        /** Уведомления разрешены (без них будить бессмысленно). */
        val notificationsAllowed: Boolean = true,
    )

    /** Почему назначено пробуждение — для журнала и диагностики. */
    enum class Reason { DISABLED, NO_NOTIFICATIONS, SERVICE_ALIVE, SERVICE_GONE, TASK_REMOVED, REFUSED }

    /**
     * План одного пробуждения.
     *
     * @param delayMs через сколько будить; null — не будить вовсе.
     * @param exact нужен ли точный будильник (только если система и человек
     *        это позволяют, см. [canBeExact]).
     */
    data class Plan(val delayMs: Long?, val exact: Boolean, val reason: Reason)

    /**
     * Точный будильник оправдан только при выключенной оптимизации батареи.
     *
     * Почему так, а не «лишь бы разрешение было»: в режиме экономии система
     * всё равно откладывает и точные будильники (`setExactAndAllowWhileIdle`
     * даёт одно пробуждение в окне, остальные копятся). Точность без
     * исключения из оптимизации создаёт ложное чувство доставки и жжёт
     * батарею, поэтому честнее уйти в неточный режим и сказать об этом в
     * настройках.
     */
    fun canBeExact(conditions: Conditions): Boolean =
        conditions.enabled && conditions.batteryUnrestricted && conditions.exactAlarmsAllowed

    /** План для живого сервиса: тихий сторож. */
    fun whileAlive(conditions: Conditions): Plan =
        base(conditions, Reason.SERVICE_ALIVE, WATCHDOG_MS)

    /** План на случай, когда служба уходит (onDestroy): быстрый подъём. */
    fun serviceGone(conditions: Conditions): Plan =
        base(conditions, Reason.SERVICE_GONE, FAST_WAKE_MS)

    /** Человек смахнул приложение из списка задач: поднимаемся так же быстро. */
    fun taskRemoved(conditions: Conditions): Plan =
        base(conditions, Reason.TASK_REMOVED, FAST_WAKE_MS)

    /**
     * Служба остановилась сама и знает причину (нет личности, ядро не
     * поднялось, перезапуск зеркала). Быстрый подъём здесь был бы вреден:
     * причина не исчезнет за две минуты, и телефон будил бы себя по кругу.
     * Поэтому — редкая неточная страховка раз в час.
     */
    fun afterPlannedStop(conditions: Conditions): Plan {
        val plan = base(conditions, Reason.SERVICE_GONE, SAFETY_NET_MS)
        return plan.copy(exact = false)
    }

    /** Старт не удался: ступень лестницы по числу подряд идущих отказов. */
    fun afterRefusal(conditions: Conditions, refusals: Int): Plan {
        val step = (refusals.coerceAtLeast(1) - 1).coerceIn(0, RETRY_LADDER_MS.lastIndex)
        return base(conditions, Reason.REFUSED, RETRY_LADDER_MS[step])
    }

    /**
     * Нужно ли сторожевому работнику поднимать службу прямо сейчас.
     *
     * Проверка нужна, чтобы сторож не превратился в «второй сервис»: пока
     * служба жива (метка свежая), он молчит и только переносит следующий тик.
     */
    fun shouldWakeNow(
        conditions: Conditions,
        lastAliveMs: Long,
        nowMs: Long,
    ): Boolean {
        if (!conditions.enabled || !conditions.notificationsAllowed) return false
        if (lastAliveMs <= 0L) return true
        // Часы могли перевести назад: отрицательная разница — тоже «метка
        // старая», иначе телефон с отставшими часами не будили бы никогда.
        val age = nowMs - lastAliveMs
        return age < 0L || age >= STALE_AFTER_MS
    }

    private fun base(conditions: Conditions, reason: Reason, delayMs: Long): Plan {
        if (!conditions.enabled) return Plan(null, exact = false, reason = Reason.DISABLED)
        if (!conditions.notificationsAllowed) {
            return Plan(null, exact = false, reason = Reason.NO_NOTIFICATIONS)
        }
        return Plan(delayMs, exact = canBeExact(conditions), reason = reason)
    }
}
