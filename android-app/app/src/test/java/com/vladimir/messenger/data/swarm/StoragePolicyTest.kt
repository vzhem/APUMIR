package com.vladimir.messenger.data.swarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ползунок «Место под пересылку»: пределы, шаги, формат и доля для рейтинга.
 * Чистая логика без Android - обычный JVM-тест.
 */
class StoragePolicyTest {

    @Test
    fun boundsAreHundredMegabytesToHundredGigabytes() {
        assertEquals(100L * 1024 * 1024, StoragePolicy.MIN_QUOTA_BYTES)
        assertEquals(100L * 1024 * 1024 * 1024, StoragePolicy.MAX_QUOTA_BYTES)
        assertEquals(StoragePolicy.MIN_QUOTA_BYTES, StoragePolicy.clamp(0L))
        assertEquals(StoragePolicy.MIN_QUOTA_BYTES, StoragePolicy.clamp(-5L))
        assertEquals(StoragePolicy.MAX_QUOTA_BYTES, StoragePolicy.clamp(Long.MAX_VALUE))
        assertEquals(StoragePolicy.DEFAULT_QUOTA_BYTES, StoragePolicy.clamp(StoragePolicy.DEFAULT_QUOTA_BYTES))
    }

    @Test
    fun stepsAreOrderedAndSpanTheWholeRange() {
        val steps = StoragePolicy.STEPS
        assertEquals(StoragePolicy.MIN_QUOTA_BYTES, steps.first())
        assertEquals(StoragePolicy.MAX_QUOTA_BYTES, steps.last())
        for (i in 1 until steps.size) assertTrue(steps[i] > steps[i - 1])
        assertTrue(StoragePolicy.DEFAULT_QUOTA_BYTES in steps)
    }

    @Test
    fun nearestStepPicksClosestPosition() {
        assertEquals(0, StoragePolicy.nearestStep(0L))
        assertEquals(StoragePolicy.STEPS.lastIndex, StoragePolicy.nearestStep(Long.MAX_VALUE))
        val twoGb = StoragePolicy.STEPS.indexOf(2L * StoragePolicy.GIB)
        assertEquals(twoGb, StoragePolicy.nearestStep(2L * StoragePolicy.GIB))
        // 1,9 ГБ ближе к 2 ГБ, чем к 1 ГБ.
        assertEquals(twoGb, StoragePolicy.nearestStep((1.9 * StoragePolicy.GIB).toLong()))
        // Индекс вне шкалы - крайнее положение, без исключения.
        assertEquals(StoragePolicy.MIN_QUOTA_BYTES, StoragePolicy.stepBytes(-3))
        assertEquals(StoragePolicy.MAX_QUOTA_BYTES, StoragePolicy.stepBytes(99))
    }

    @Test
    fun storageFractionIsLogarithmicAndBounded() {
        assertEquals(0.0, StoragePolicy.storageFraction(0L), 0.0)
        assertEquals(0.0, StoragePolicy.storageFraction(StoragePolicy.MIN_QUOTA_BYTES), 0.0)
        assertEquals(1.0, StoragePolicy.storageFraction(StoragePolicy.MAX_QUOTA_BYTES), 0.0001)
        // Больше потолка ползунка не ценится: «миллион терабайт» = 100 ГБ.
        assertEquals(1.0, StoragePolicy.storageFraction(Long.MAX_VALUE), 0.0001)
        // Десятикратные шаги стоят одинаково: 1 ГБ ≈ 1/3, 10 ГБ ≈ 2/3.
        assertEquals(1.0 / 3.0, StoragePolicy.storageFraction(StoragePolicy.GIB), 0.02)
        assertEquals(2.0 / 3.0, StoragePolicy.storageFraction(10L * StoragePolicy.GIB), 0.02)
        val small = StoragePolicy.storageFraction(500L * StoragePolicy.MIB)
        val big = StoragePolicy.storageFraction(50L * StoragePolicy.GIB)
        assertTrue(small in 0.0..1.0 && big in 0.0..1.0 && big > small)
    }

    @Test
    fun formatReadsLikeTheSlider() {
        assertEquals("100 МБ", StoragePolicy.format(StoragePolicy.MIN_QUOTA_BYTES))
        assertEquals("2 ГБ", StoragePolicy.format(2L * StoragePolicy.GIB))
        assertEquals("100 ГБ", StoragePolicy.format(StoragePolicy.MAX_QUOTA_BYTES))
        assertEquals("1,5 ГБ", StoragePolicy.format(StoragePolicy.GIB + StoragePolicy.GIB / 2))
        assertEquals("512 КБ", StoragePolicy.format(512L * 1024))
        assertEquals("0 Б", StoragePolicy.format(-1L))
    }
}
