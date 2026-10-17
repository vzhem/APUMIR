package com.vladimir.messenger.data.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryFaceMotionTest {
    @Test
    fun furAndWhiskersSwayWithinSmallAngles() {
        for (t in 0L..10_000L step 37L) {
            assertTrue(kotlin.math.abs(StoryFaceMotion.furSwayDegrees(t, 0.0)) <= 3.0001f)
            assertTrue(kotlin.math.abs(StoryFaceMotion.furSwayDegrees(t, 1.3)) <= 3.0001f)
            assertTrue(kotlin.math.abs(StoryFaceMotion.whiskerSwayDegrees(t)) <= 2.5001f)
        }
    }

    @Test
    fun leftAndRightEarsSwayOutOfPhase() {
        // Соседние по времени значения двух ушей не совпадают: иначе уши качались бы синхронно.
        assertTrue(kotlin.math.abs(StoryFaceMotion.furSwayDegrees(350L, 0.0) - StoryFaceMotion.furSwayDegrees(350L, 1.3)) > 0.01f)
    }

    @Test
    fun noseSniffsBriefly() {
        // Сразу после начала цикла шмыг: нос заметно больше обычного.
        assertTrue(StoryFaceMotion.noseScale(96L) > 1.05f)
        // В середине цикла шмыга нет, остаётся лишь дыхание в пределах ±2.5%.
        val calm = StoryFaceMotion.noseScale(1_600L)
        assertTrue(calm in 0.97f..1.03f)
    }

    @Test
    fun glintMovesSlightly() {
        for (t in 0L..10_000L step 53L) {
            assertTrue(kotlin.math.abs(StoryFaceMotion.glintOffset(t)) <= 0.05001f)
        }
    }

    @Test
    fun motionRepeatsItsPeriod() {
        assertEquals(StoryFaceMotion.furSwayDegrees(123L), StoryFaceMotion.furSwayDegrees(123L + 1400L), 0.001f)
        assertEquals(StoryFaceMotion.noseScale(500L), StoryFaceMotion.noseScale(500L + 3200L * 5), 0.001f)
    }
}
