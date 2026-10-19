package com.vladimir.messenger.data.profile

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * День рождения человека: число, месяц и, по желанию, год.
 *
 * Год остаётся на телефоне владельца и по сети не едет: друзьям, чтобы
 * поздравить, нужен только день. Видимость — отдельное поле: скрытая дата не
 * покидает телефон вообще (см. `BirthdayStore`).
 */
data class Birthday(
    val day: Int,
    val month: Int,
    /** Год рождения; [Birthday.NO_YEAR] — человек его не указал. */
    val year: Int = Birthday.NO_YEAR,
    /** Дата видна друзьям. Скрытая дата не рассылается и в баннер не попадает. */
    val visible: Boolean = true,
) {

    /**
     * Календарная арифметика дня рождения — без Compose и без Android, поэтому
     * проверяется обычным JVM-тестом (см. `BirthdayTest`).
     */
    companion object {
        /** Год не указан: в профиле показываем только число и месяц. */
        const val NO_YEAR = 0

        /** Раньше 1900 годов рождения не бывает; будущее отсекаем отдельно. */
        const val MIN_YEAR = 1900

        /**
         * Год, от которого считаем день месяца при разборе даты.
         * Високосный — значит «29 февраля» существует и разбор не падает.
         */
        private const val LEAP_REFERENCE_YEAR = 2024

        fun daysInMonth(month: Int, leap: Boolean): Int = when (month) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            2 -> if (leap) 29 else 28
            else -> 0
        }

        fun isLeap(year: Int): Boolean =
            (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

        /** Число и месяц существуют (29 февраля считаем существующим всегда). */
        fun isValid(day: Int, month: Int): Boolean = day in 1..daysInMonth(month, leap = true)

        fun isValidYear(year: Int, nowYear: Int): Boolean =
            year == NO_YEAR || year in MIN_YEAR..nowYear

        /**
         * Полная дата рождения в будущем: человек ещё не родился.
         * Без года дата будущей быть не может — она повторяется каждый год.
         * 29 февраля в невисокосный год считаем по дню, как в календаре: 28-м.
         */
        fun isFuture(day: Int, month: Int, year: Int, today: LocalDate): Boolean {
            if (year == NO_YEAR || !isValid(day, month)) return false
            val clampedDay = minOf(day, daysInMonth(month, isLeap(year)))
            return LocalDate.of(year, month, clampedDay).isAfter(today)
        }

        /** Дата рождения целиком допустима: существует, год в пределах и не в будущем. */
        fun isValidBirthDate(day: Int, month: Int, year: Int, today: LocalDate): Boolean =
            isValid(day, month) && isValidYear(year, today.year) && !isFuture(day, month, year, today)

        /**
         * День, в который поздравляют в этом году.
         *
         * 29 февраля поздравляют 28-го в невисокосный год: иначе человек, родившийся
         * 29-го, получал бы поздравление раз в четыре года.
         */
        fun celebrateDayIn(day: Int, month: Int, year: Int): Int =
            if (month == 2 && day == 29 && !isLeap(year)) 28 else day

        /** Сегодня ли поздравляют человека, родившегося [day].[month]. */
        fun isToday(day: Int, month: Int, today: LocalDate): Boolean =
            isValid(day, month) && month == today.monthValue &&
                celebrateDayIn(day, month, today.year) == today.dayOfMonth

        /** «12 июня» или «12 июня 1990» — смотря указан ли год. */
        fun label(
            day: Int,
            month: Int,
            year: Int = NO_YEAR,
            locale: Locale = Locale.getDefault(),
        ): String {
            if (!isValid(day, month)) return ""
            val monthDay = LocalDate.of(LEAP_REFERENCE_YEAR, month, day)
                .format(DateTimeFormatter.ofPattern("d MMMM", locale))
            return if (year == NO_YEAR) monthDay else "$monthDay $year"
        }

        /** Дата задана целиком (число и месяц годные). */
        fun isComplete(birthday: Birthday?): Boolean =
            birthday != null && isValid(birthday.day, birthday.month)
    }
}
