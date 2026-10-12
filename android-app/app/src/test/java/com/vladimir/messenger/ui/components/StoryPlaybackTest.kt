package com.vladimir.messenger.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class StoryPlaybackTest {
    @Test fun durationScalesWithReadingLengthAndIsBounded() {
        assertEquals(5_000L, StoryPlayback.durationMs(0))
        assertEquals(7_500L, StoryPlayback.durationMs(100))
        assertEquals(10_000L, StoryPlayback.durationMs(200))
        assertEquals(10_000L, StoryPlayback.durationMs(500))
    }

    @Test fun progressIsClampedAndDoesNotSkipAfterASuspendedFrame() {
        assertEquals(.01f, StoryPlayback.advance(0f, 50L, 5_000L), .0001f)
        assertEquals(.02f, StoryPlayback.advance(0f, 20_000L, 5_000L), .0001f)
        assertEquals(1f, StoryPlayback.advance(.999f, 100L, 5_000L), .0001f)
        assertEquals(.4f, StoryPlayback.advance(.4f, -10L, 5_000L), .0001f)
    }

    private fun gesture(dx: Float = 0f, dy: Float = 0f, dragged: Boolean = false,
                        cancelled: Boolean = false, elapsed: Long = 100L, x: Float = 250f) =
        StoryPlayback.gesture(dx, dy, dragged, cancelled, elapsed, 500L, x, 300, 1f)

    @Test fun shortTapsNavigateButHoldReleaseDoesNot() {
        assertEquals(StoryPlayback.Gesture.PREVIOUS, gesture(x = 20f))
        assertEquals(StoryPlayback.Gesture.NEXT, gesture())
        assertEquals(StoryPlayback.Gesture.NONE, gesture(elapsed = 700L))
        assertEquals(StoryPlayback.Gesture.NONE, gesture(cancelled = true))
    }

    @Test fun directionalSwipesAndSmallDrags() {
        assertEquals(StoryPlayback.Gesture.DISMISS, gesture(dy = 100f, dragged = true))
        assertEquals(StoryPlayback.Gesture.NEXT, gesture(dx = -100f, dragged = true))
        assertEquals(StoryPlayback.Gesture.PREVIOUS, gesture(dx = 100f, dragged = true))
        assertEquals(StoryPlayback.Gesture.NONE, gesture(dx = 20f, dragged = true))
        assertEquals(StoryPlayback.Gesture.NONE, gesture(dy = -100f, dragged = true))
    }
}
