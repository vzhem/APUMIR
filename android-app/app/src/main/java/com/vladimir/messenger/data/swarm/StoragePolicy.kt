package com.vladimir.messenger.data.swarm

import java.util.Locale
import kotlin.math.log10

/**
 * Сколько места телефон отдаёт под «серверную» работу: зашифрованные куски
 * файлов (свои исходящие, входящие и - дальше - чужие на хранении) и данные
 * для узлов, которых сейчас нет в сети.
 *
 * Чистый Kotlin без Android: пределы, шаги ползунка, формат и «доля» для
 * рейтинга проверяются обычным JVM-тестом. Настройка хранится в
 * [StorageSettings]; проверка при записи - в `FileTransferChunkStore`.
 */
object StoragePolicy {
    const val MIB = 1024L * 1024L
    const val GIB = 1024L * MIB

    /** Меньше 100 МБ телефон не даёт: иначе он не сервер, а только клиент. */
    const val MIN_QUOTA_BYTES = 100L * MIB

    /** Больше 100 ГБ на телефоне не нужно (решение владельца, 2026-09-11). */
    const val MAX_QUOTA_BYTES = 100L * GIB

    /** По умолчанию - 2 ГБ: заметный вклад, но не сюрприз для владельца телефона. */
    const val DEFAULT_QUOTA_BYTES = 2L * GIB

    /**
     * Неприкосновенный запас свободного места самого телефона. Даже при
     * «100 ГБ» на ползунке в него не залезаем: телефону нужно место под
     * обновления, фото и базу - иначе он начинает тормозить и сам чистит
     * кэши приложений.
     */
    const val FREE_RESERVE_BYTES = 512L * MIB

    /** Положения ползунка (логарифмическая шкала: между соседями ×2…×2,5). */
    val STEPS: List<Long> = listOf(
        100L * MIB,
        200L * MIB,
        500L * MIB,
        1L * GIB,
        2L * GIB,
        5L * GIB,
        10L * GIB,
        20L * GIB,
        50L * GIB,
        100L * GIB,
    )

    /** Любое значение (в том числе из старых настроек) - в допустимые пределы. */
    fun clamp(bytes: Long): Long = bytes.coerceIn(MIN_QUOTA_BYTES, MAX_QUOTA_BYTES)

    /** Ближайшее положение ползунка к значению. */
    fun nearestStep(bytes: Long): Int {
        val target = clamp(bytes)
        var best = 0
        var bestDistance = Long.MAX_VALUE
        for ((index, step) in STEPS.withIndex()) {
            val distance = kotlin.math.abs(step - target)
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
    }

    /** Значение положения ползунка; выход за края - крайние положения. */
    fun stepBytes(index: Int): Long = STEPS[index.coerceIn(0, STEPS.lastIndex)]

    /**
     * Доля предложенного места на шкале рейтинга: 0 у 100 МБ, 1 у 100 ГБ,
     * по логарифму между ними (1 ГБ ≈ 0,33; 10 ГБ ≈ 0,67). Так десятикратная
     * прибавка места стоит одинаково на любом уровне, а «100 ГБ против 50»
     * не решает всё.
     */
    fun storageFraction(offeredBytes: Long): Double {
        if (offeredBytes <= MIN_QUOTA_BYTES) return 0.0
        val capped = offeredBytes.coerceAtMost(MAX_QUOTA_BYTES)
        val decades = log10(capped.toDouble() / MIN_QUOTA_BYTES)
        val span = log10(MAX_QUOTA_BYTES.toDouble() / MIN_QUOTA_BYTES)
        return (decades / span).coerceIn(0.0, 1.0)
    }

    /** «100 МБ», «2 ГБ», «1,5 ГБ» - как на ползунке и в строках «занято». */
    fun format(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        return when {
            safe >= GIB -> {
                val value = safe.toDouble() / GIB
                if (value == Math.rint(value)) {
                    "${value.toLong()} ГБ"
                } else {
                    String.format(Locale.forLanguageTag("ru"), "%.1f ГБ", value)
                }
            }
            safe >= MIB -> "${safe / MIB} МБ"
            safe >= 1024L -> "${safe / 1024L} КБ"
            else -> "$safe Б"
        }
    }
}
