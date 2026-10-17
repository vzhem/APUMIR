package com.vladimir.messenger.data.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryFaceTimelineTest {
    private val face = StoryFace(StoryFaceBox(0f, 0f, 100f, 100f), null, null, null, 0f)

    // Замеры через 100 мс: 0, 100, 200. Во втором замере лица нет.
    private val timeline = StoryFaceTimeline(
        width = 640,
        height = 480,
        timesMs = longArrayOf(0L, 100L, 200L),
        samples = listOf(listOf(face), emptyList(), listOf(face)),
    )

    @Test
    fun picksTheNearestSampleByFrameTime() {
        assertEquals(0, timeline.sampleIndexAt(10L))
        assertEquals(1, timeline.sampleIndexAt(70L))
        assertEquals(2, timeline.sampleIndexAt(190L))
        assertEquals(2, timeline.sampleIndexAt(200L))
    }

    @Test
    fun tieGoesToTheEarlierSample() {
        assertEquals(0, timeline.sampleIndexAt(50L))
    }

    @Test
    fun framesFarFromAnySampleGetNoFilter() {
        // Замеры заканчиваются на 200 мс: кадр на 600 мс уже без лица.
        assertEquals(-1, timeline.sampleIndexAt(600L))
        assertEquals(-1, timeline.sampleIndexAt(-500L))
    }

    @Test
    fun facesAtOutOfRangeIsEmpty() {
        assertTrue(timeline.facesAt(-1).isEmpty())
        assertTrue(timeline.facesAt(99).isEmpty())
        assertEquals(1, timeline.facesAt(0).size)
        assertTrue(timeline.facesAt(1).isEmpty())
    }

    @Test
    fun emptyTimelineReportsNothingToApply() {
        assertTrue(StoryFaceTimeline.EMPTY.isEmpty)
        assertEquals(-1, StoryFaceTimeline.EMPTY.sampleIndexAt(0L))
        assertFalse(timeline.isEmpty)
    }
}
