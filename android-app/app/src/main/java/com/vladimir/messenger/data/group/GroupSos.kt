package com.vladimir.messenger.data.group

/**
 * Правила красной кнопки SOS в группе.
 *
 * Одно нажатие отправляет сразу несколько тревожных сообщений (от 3 до 5 -
 * настраивается в группе), поэтому повторное нажатие заблокировано не
 * меньше чем на [COOLDOWN_MS]: иначе один взволнованный человек за минуту
 * завалил бы группу десятками тревог, и на сигнал перестали бы реагировать.
 *
 * Кто вправе нажимать - тоже настройка группы: все участники
 * ([PRESS_ALL]), только владелец и администраторы ([PRESS_ADMINS]) или
 * только владелец ([PRESS_OWNER]). Ограниченный (забаненный) участник не
 * нажимает никогда.
 */
object GroupSos {
    const val PRESS_ALL = "ALL"
    const val PRESS_ADMINS = "ADMINS"
    const val PRESS_OWNER = "OWNER"

    /** Варианты «кто может нажимать» в порядке от широкого к узкому. */
    val PRESS_ROLES: List<String> = listOf(PRESS_ALL, PRESS_ADMINS, PRESS_OWNER)

    /** Тревожных сообщений за одно нажатие: минимум и максимум настройки. */
    const val MIN_COUNT = 3
    const val MAX_COUNT = 5
    const val DEFAULT_COUNT = 3

    /** Варианты числа сообщений для кнопок в настройках группы. */
    val COUNT_CHOICES: List<Int> = listOf(3, 4, 5)

    /** Защита от повторного нажатия: не меньше десяти секунд. */
    const val COOLDOWN_MS = 10_000L

    fun normalizePressRole(role: String?): String =
        if (role in PRESS_ROLES) role!! else PRESS_ALL

    fun clampCount(count: Int): Int = count.coerceIn(MIN_COUNT, MAX_COUNT)

    /** Вправе ли участник с ролью [role] нажать SOS при настройке [pressRole]. */
    fun canPress(role: String?, pressRole: String?, isBanned: Boolean): Boolean {
        if (isBanned) return false
        return when (normalizePressRole(pressRole)) {
            PRESS_OWNER -> GroupRole.isOwner(GroupRole.normalize(role))
            PRESS_ADMINS -> GroupRole.isAdminOrOwner(GroupRole.normalize(role))
            else -> true
        }
    }

    /** Сколько ещё ждать до следующего нажатия; 0 - нажимать можно. */
    fun cooldownRemainingMs(lastSentAtMs: Long, nowMs: Long): Long {
        if (lastSentAtMs <= 0L) return 0L
        // Будущая метка (перевод часов, чужая резервная копия) кнопку не запирает.
        if (lastSentAtMs > nowMs) return 0L
        return (COOLDOWN_MS - (nowMs - lastSentAtMs)).coerceAtLeast(0L)
    }
}
