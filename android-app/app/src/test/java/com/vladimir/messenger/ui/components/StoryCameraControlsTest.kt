package com.vladimir.messenger.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class StoryCameraControlsTest {
    @Test
    fun pinchZoomsWithinTheCameraRange() {
        assertEquals(2f, StoryCameraControls.zoomAfterPinch(1f, 2f, 1f, 10f), 0.001f)
        assertEquals(1f, StoryCameraControls.zoomAfterPinch(1f, 0.2f, 1f, 10f), 0.001f)
        assertEquals(8f, StoryCameraControls.zoomAfterPinch(4f, 4f, 1f, 8f), 0.001f)
        assertEquals(1.5f, StoryCameraControls.zoomAfterPinch(1.5f, 0f, 1f, 8f), 0.001f)
        assertEquals(1.5f, StoryCameraControls.zoomAfterPinch(1.5f, Float.NaN, 1f, 8f), 0.001f)
    }

    @Test
    fun flashCyclesOnlyWhenTheUnitExists() {
        assertEquals(StoryCameraControls.Flash.ON, StoryCameraControls.cycleFlash(StoryCameraControls.Flash.OFF, true))
        assertEquals(StoryCameraControls.Flash.AUTO, StoryCameraControls.cycleFlash(StoryCameraControls.Flash.ON, true))
        assertEquals(StoryCameraControls.Flash.OFF, StoryCameraControls.cycleFlash(StoryCameraControls.Flash.AUTO, true))
        assertEquals(StoryCameraControls.Flash.OFF, StoryCameraControls.cycleFlash(StoryCameraControls.Flash.ON, false))
    }

    @Test
    fun zoomLabelDropsUselessDecimals() {
        assertEquals("1×", StoryCameraControls.zoomLabel(1f))
        assertEquals("2×", StoryCameraControls.zoomLabel(2.0f))
        assertEquals("1.5×", StoryCameraControls.zoomLabel(1.5f))
        assertEquals("1×", StoryCameraControls.zoomLabel(0f))
    }

    @Test
    fun shutterKeepsClearanceWhenInsetsAreMissing() {
        assertEquals(96f, StoryCameraControls.shutterBottomPaddingPx(0f, 96f), 0.001f)
        assertEquals(120f, StoryCameraControls.shutterBottomPaddingPx(120f, 96f), 0.001f)
        assertEquals(96f, StoryCameraControls.shutterBottomPaddingPx(Float.NaN, 96f), 0.001f)
    }
}
