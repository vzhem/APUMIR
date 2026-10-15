package com.vladimir.messenger.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Много историй складываются поплотнее, по нажатию раздвигаются» (просьба
 * владельца). Правило целиком живёт в арифметике [StoryStripLayout], поэтому
 * проверяется здесь без телефона.
 */
class StoryStripLayoutTest {

    @Test fun fewStoriesStandFreelyWithLabels() {
        for (count in 0..StoryStripLayout.COMFORTABLE_AUTHORS) {
            assertFalse("авторов $count", StoryStripLayout.compact(count, expanded = false))
            assertEquals(StoryStripLayout.PEER_SLOT_DP, StoryStripLayout.peerSlotDp(count, expanded = false))
            assertEquals(StoryStripLayout.ITEM_GAP_DP, StoryStripLayout.gapDp(count, expanded = false))
            assertEquals(0, StoryStripLayout.overlapDp(count, expanded = false))
            assertTrue(StoryStripLayout.labelsVisible(count, expanded = false))
        }
    }

    @Test fun manyStoriesStackTighterAndTighter() {
        assertTrue(StoryStripLayout.compact(4, expanded = false))
        val steps = listOf(4, 7, 12).map { StoryStripLayout.peerSlotDp(it, expanded = false) }
        assertEquals(steps.sortedDescending(), steps)
        for (count in 4..40) {
            assertTrue("авторов $count", StoryStripLayout.compact(count, expanded = false))
            assertTrue("авторов $count", StoryStripLayout.overlapDp(count, expanded = false) > 0)
            assertFalse(StoryStripLayout.labelsVisible(count, expanded = false))
        }
    }

    @Test fun expandedStripGivesEveryCircleRoomAndItsLabel() {
        for (count in 0..40) {
            assertFalse(StoryStripLayout.compact(count, expanded = true))
            assertEquals(StoryStripLayout.PEER_SLOT_DP, StoryStripLayout.peerSlotDp(count, expanded = true))
            assertEquals(StoryStripLayout.ITEM_GAP_DP, StoryStripLayout.gapDp(count, expanded = true))
            assertEquals(0, StoryStripLayout.overlapDp(count, expanded = true))
            assertTrue(StoryStripLayout.labelsVisible(count, expanded = true))
        }
    }

    @Test fun stackedCircleStillLeavesAFingerTarget() {
        // Даже в самой плотной стопке видно больше половины кружка: иначе по
        // нему нельзя попасть пальцем и нет смысла в раздвигании.
        val tightest = StoryStripLayout.peerSlotDp(40, expanded = false)
        assertTrue(tightest >= StoryStripLayout.PEER_DIAMETER_DP / 2)
        assertTrue(StoryStripLayout.overlapDp(40, expanded = false) < StoryStripLayout.PEER_DIAMETER_DP)
    }

    @Test fun stackNeverAsksForNegativeSpace() {
        // Полоса — обычный LazyRow: отрицательных отступов ей не задаём, наезд
        // получается сужением слота, а не отрицательным просветом.
        for (count in 0..40) {
            assertTrue(StoryStripLayout.gapDp(count, expanded = false) >= 0)
            assertTrue(StoryStripLayout.gapDp(count, expanded = true) >= 0)
            assertTrue(StoryStripLayout.peerSlotDp(count, expanded = false) > 0)
        }
    }

    @Test fun badgeDigitFitsTheCircle() {
        assertEquals("0", StoryStripLayout.totalLabel(0))
        assertEquals("7", StoryStripLayout.totalLabel(7))
        assertEquals("99", StoryStripLayout.totalLabel(99))
        assertEquals("99+", StoryStripLayout.totalLabel(100))
        assertEquals("99+", StoryStripLayout.totalLabel(5_000))
        assertEquals("0", StoryStripLayout.totalLabel(-3))
    }
}
