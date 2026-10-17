package com.vladimir.messenger.data.story

import kotlin.math.PI
import kotlin.math.sin

/**
 * Живые движения AR-элементов. Все функции зависят только от времени в миллисекундах,
 * поэтому превью и запекание видео получают одинаковую картину в один и тот же момент.
 */
object StoryFaceMotion {
    private const val TAU = 2.0 * PI

    /** Колыхание ушей и шерсти: до ±3°. [phase] разносит левое и правое ухо. */
    fun furSwayDegrees(timeMs: Long, phase: Double = 0.0): Float =
        (3.0 * sin(TAU * timeMs / FUR_PERIOD_MS + phase)).toFloat()

    /** Колыхание усов: до ±2.5°. */
    fun whiskerSwayDegrees(timeMs: Long): Float =
        (2.5 * sin(TAU * timeMs / WHISKER_PERIOD_MS)).toFloat()

    /**
     * Масштаб носа: медленное дыхание (±2.5%) и короткий шмыг раз в [SNIFF_PERIOD_MS]:
     * нос кратко расширяется на 6% и возвращается.
     */
    fun noseScale(timeMs: Long): Float {
        val breathing = 0.025 * sin(TAU * timeMs / BREATH_PERIOD_MS)
        val phase = java.lang.Math.floorMod(timeMs, SNIFF_PERIOD_MS).toDouble() / SNIFF_PERIOD_MS
        val sniff = if (phase < SNIFF_SHARE) 0.06 * sin(phase / SNIFF_SHARE * PI) else 0.0
        return (1.0 + breathing + sniff).toFloat()
    }

    /** Смещение блика в глазах, доля диаметра глаза: блик слегка «плывёт». */
    fun glintOffset(timeMs: Long): Float = (0.05 * sin(TAU * timeMs / GLINT_PERIOD_MS)).toFloat()

    private const val FUR_PERIOD_MS = 1400.0
    private const val WHISKER_PERIOD_MS = 2000.0
    private const val BREATH_PERIOD_MS = 2600.0
    private const val GLINT_PERIOD_MS = 3100.0
    private const val SNIFF_PERIOD_MS = 3200L
    private const val SNIFF_SHARE = 0.06
}
