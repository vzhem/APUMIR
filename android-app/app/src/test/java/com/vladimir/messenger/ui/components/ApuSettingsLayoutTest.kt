package com.vladimir.messenger.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApuSettingsLayoutTest {
    @Test
    fun normalPhoneHasFourCompactProfileActions() {
        assertEquals(4, ApuSettingsLayout.profileActionColumns(296f, 1f))
    }

    @Test
    fun largeFontFallsBackToTwoOrOneColumnWithoutTruncatingLabels() {
        assertEquals(2, ApuSettingsLayout.profileActionColumns(296f, 1.6f))
        assertEquals(1, ApuSettingsLayout.profileActionColumns(128f, 1f))
        assertEquals(1, ApuSettingsLayout.profileActionColumns(200f, 2f))
    }

    @Test
    fun smallerFontDoesNotReduceTheMinimumTouchTargets() {
        assertEquals(4, ApuSettingsLayout.profileActionColumns(296f, 0.7f))
        assertEquals(2, ApuSettingsLayout.profileActionColumns(240f, 0.7f))
    }

    @Test
    fun phoneThemeChoicesFitSideBySideButLargeTypeStacksThem() {
        assertTrue(ApuSettingsLayout.horizontalThemeChoices(296f, 1f))
        assertFalse(ApuSettingsLayout.horizontalThemeChoices(296f, 1.5f))
        assertFalse(ApuSettingsLayout.horizontalThemeChoices(200f, 1f))
    }

    @Test
    fun exactThresholdsArePredictable() {
        assertEquals(4, ApuSettingsLayout.profileActionColumns(280f, 1f))
        assertEquals(2, ApuSettingsLayout.profileActionColumns(279f, 1f))
        assertTrue(ApuSettingsLayout.horizontalThemeChoices(256f, 1f))
        assertFalse(ApuSettingsLayout.horizontalThemeChoices(255f, 1f))
    }

    @Test
    fun tinyOrUnknownWidthAlwaysHasAUsableFallback() {
        assertEquals(1, ApuSettingsLayout.profileActionColumns(0f, 1f))
        assertFalse(ApuSettingsLayout.horizontalThemeChoices(0f, 1f))
        assertFalse(ApuSettingsLayout.horizontalCommunityTypeChoices(0f, 1f))
    }

    @Test
    fun communityTypeChoicesFitSideBySideOnPhoneAndStackAtLargeType() {
        assertTrue(ApuSettingsLayout.horizontalCommunityTypeChoices(280f, 1f))
        assertFalse(ApuSettingsLayout.horizontalCommunityTypeChoices(280f, 1.5f))
        assertTrue(ApuSettingsLayout.horizontalCommunityTypeChoices(224f, 1f))
        assertFalse(ApuSettingsLayout.horizontalCommunityTypeChoices(223f, 1f))
    }
}
