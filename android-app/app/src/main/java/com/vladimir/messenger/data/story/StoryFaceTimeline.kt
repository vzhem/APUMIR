package com.vladimir.messenger.data.story

import kotlin.math.abs

/**
 * Лица, найденные в записанном файле через равные промежутки времени.
 * По времени кадра выдаёт ближайший замер; так фильтр следует за лицом при наложении.
 *
 * [timesMs] строго возрастает, [samples] — лица в каждом замере (может быть пустым).
 */
internal class StoryFaceTimeline(
    val width: Int,
    val height: Int,
    private val timesMs: LongArray,
    private val samples: List<List<StoryFace>>,
) {
    init {
        require(timesMs.size == samples.size) { "Таймлайн: число времён и замеров не совпадает" }
    }

    /** true, если ни в одном замере лиц не нашлось: накладывать нечего. */
    val isEmpty: Boolean get() = samples.all { it.isEmpty() }

    /**
     * Индекс замера, ближайшего к [ptsMs], или -1, если ближайший замер дальше [maxGapMs].
     * Так лицо не «залипает» на последней позиции, когда оно уже ушло из кадра.
     */
    fun sampleIndexAt(ptsMs: Long, maxGapMs: Long = MAX_GAP_MS): Int {
        if (timesMs.isEmpty()) return -1
        // Бинарный поиск первого замера не раньше ptsMs.
        var lo = 0
        var hi = timesMs.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (timesMs[mid] < ptsMs) lo = mid + 1 else hi = mid
        }
        var best = lo
        if (lo > 0 && abs(timesMs[lo - 1] - ptsMs) <= abs(timesMs[lo] - ptsMs)) best = lo - 1
        return if (abs(timesMs[best] - ptsMs) <= maxGapMs) best else -1
    }

    fun facesAt(index: Int): List<StoryFace> = if (index in samples.indices) samples[index] else emptyList()

    companion object {
        /** Шаг анализа записи: 100 мс — около 10 замеров в секунду. */
        const val SAMPLE_STEP_MS = 100L

        /** Замер дальше этого считается устаревшим (больше половины шага и запас на дрожание). */
        const val MAX_GAP_MS = 150L

        val EMPTY = StoryFaceTimeline(0, 0, LongArray(0), emptyList())
    }
}
