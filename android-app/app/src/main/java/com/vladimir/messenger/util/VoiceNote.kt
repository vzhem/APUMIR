package com.vladimir.messenger.util

/**
 * Голосовое сообщение уходит обычным файлом (тот же путь, что у документов),
 * а волна едет в его имени: `voice_<32 цифры уровней 0-9>_<секунды>.m4a`.
 * Так получатель видит волну сразу, без декодирования звука, и старые версии
 * просто показывают файл с понятным именем.
 */
object VoiceNote {
    const val BARS = 32
    private val NAME = Regex("""^voice_([0-9]{$BARS})_([0-9]{1,4})\.m4a$""")

    data class Info(val levels: List<Int>, val durationSec: Int)

    /** Имя файла для голосового; уровни приводятся к 32 столбикам 0-9. */
    fun fileName(levels: List<Int>, durationSec: Int): String {
        val bars = resample(levels)
        val digits = bars.joinToString("") { it.coerceIn(0, 9).toString() }
        return "voice_${digits}_${durationSec.coerceIn(0, 9999)}.m4a"
    }

    /** Разбор имени: null, если это не голосовое сообщение. */
    fun parse(displayName: String): Info? {
        val m = NAME.matchEntire(displayName) ?: return null
        val levels = m.groupValues[1].map { it.digitToInt() }
        return Info(levels = levels, durationSec = m.groupValues[2].toIntOrNull() ?: 0)
    }

    /** Усреднение любого числа уровней до [BARS] столбиков. */
    fun resample(levels: List<Int>): List<Int> {
        if (levels.isEmpty()) return List(BARS) { 0 }
        return List(BARS) { i ->
            val from = i * levels.size / BARS
            val to = ((i + 1) * levels.size / BARS).coerceAtLeast(from + 1).coerceAtMost(levels.size)
            val slice = levels.subList(from, to)
            (slice.sum().toFloat() / slice.size).toInt().coerceIn(0, 9)
        }
    }
}
