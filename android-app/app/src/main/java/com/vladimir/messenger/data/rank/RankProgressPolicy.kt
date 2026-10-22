package com.vladimir.messenger.data.rank

/**
 * Единая шкала прогресса ранга.
 *
 * Игровое сердечко — это не обычное сердечко профиля. Здесь считаются только
 * пойманные сердечки из игры: каждые 1 000 таких очков дают один эквивалент
 * ранга. Подтверждённое приглашение даёт тот же эквивалент и поэтому добавляет
 * в игровой счёт ровно 1 000 сердечек.
 *
 * Сама политика не знает Android и хранение: это позволяет проверить границы
 * отдельно и не допустить расхождения правил между игрой, экраном ранга и
 * ограничителями возможностей.
 */
object RankProgressPolicy {
    const val HEARTS_PER_RANK = 1_000L
    const val MAX_RANK_UNITS = 1_000

    /** Сколько целых эквивалентов ранга есть в игровом счёте и промо-бонусе. */
    fun rankUnits(heartScore: Long, promoBonus: Int = 0): Int {
        val hearts = heartScore.coerceAtLeast(0L) / HEARTS_PER_RANK
        return (hearts + promoBonus.coerceAtLeast(0))
            .coerceIn(0L, MAX_RANK_UNITS.toLong())
            .toInt()
    }

    /** Сердечки, оставшиеся после последнего полного эквивалента ранга. */
    fun heartsIntoCurrentRank(heartScore: Long): Int =
        (heartScore.coerceAtLeast(0L) % HEARTS_PER_RANK).toInt()

    /** Сколько игровых сердечек нужно до следующего +1 от игры. */
    fun heartsToNextRank(heartScore: Long): Int {
        val remainder = heartsIntoCurrentRank(heartScore)
        return if (remainder == 0) HEARTS_PER_RANK.toInt()
        else HEARTS_PER_RANK.toInt() - remainder
    }

    /** Бонус игрового счёта за указанное число подтверждённых приглашений. */
    fun referralBonusHearts(qualifiedInvites: Int): Long =
        qualifiedInvites.coerceAtLeast(0).toLong() * HEARTS_PER_RANK
}
