package com.vladimir.messenger.ui.components

import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Geometry/click tests on an Android renderer, not screenshots or source substitutes. */
@RunWith(AndroidJUnit4::class)
class ApuActionButtonInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private fun fixture(
        compact: Boolean = false,
        leading: Boolean = false,
        progress: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
        width: Int = 220,
        enabled: Boolean = true,
        onClick: () -> Unit = {},
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                MaterialTheme {
                    ApuDiagnosticsActionButton(
                        label = "Скопировать",
                        onClick = onClick,
                        modifier = Modifier.width(width.dp).testTag("action"),
                        icon = if (leading) DiagnosticsActionIcons.Copy else null,
                        compact = compact,
                        progress = progress,
                        enabled = enabled,
                    )
                }
            }
        }
    }

    private fun assertCentred(tag: String = "action", label: String = "Скопировать") {
        val button = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val text = compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("horizontal centre", button.center.x, text.center.x, 1f)
        assertEquals("vertical centre", button.center.y, text.center.y, 1f)
        assertTrue("label fits vertically", text.top >= button.top && text.bottom <= button.bottom)
        compose.onNodeWithTag(tag).assertHeightIsAtLeast(52.dp)
    }

    @Test fun textOnlyLabelIsCentred() {
        fixture()
        assertCentred()
    }

    @Test fun leadingIconDoesNotShiftLabel() {
        fixture(leading = true)
        assertCentred()
    }

    @Test fun loadingIndicatorDoesNotShiftLabel() {
        fixture(progress = true)
        assertCentred()
    }

    @Test fun narrowCompactButtonDoesNotStackIconAboveLabel() {
        fixture(compact = true, leading = true, width = 132)
        assertCentred()
    }

    @Test fun largeFontNarrowButtonGrowsWithoutLosingCentre() {
        fixture(compact = true, leading = true, fontScale = 2f, width = 132)
        assertCentred()
    }

    @Test fun rtlIconDoesNotShiftLabel() {
        fixture(leading = true, rtl = true)
        assertCentred()
    }

    @Test fun pairedButtonsHaveEqualHeightAndCentredLabels() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                MaterialTheme {
                    Row(Modifier.width(280.dp).height(IntrinsicSize.Min)) {
                        ApuDiagnosticsActionButton(
                            "Обновить отчёт", {}, compact = true,
                            modifier = Modifier.weight(1f).fillMaxHeight().testTag("refresh"),
                        )
                        ApuDiagnosticsActionButton(
                            "Закрыть", {}, compact = true,
                            modifier = Modifier.weight(1f).fillMaxHeight().testTag("close"),
                        )
                    }
                }
            }
        }
        assertCentred("refresh", "Обновить отчёт")
        assertCentred("close", "Закрыть")
        val first = compose.onNodeWithTag("refresh").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("close").fetchSemanticsNode().boundsInRoot
        assertEquals(first.height, second.height, 1f)
        assertEquals(first.width, second.width, 1f)
    }

    @Test fun clickCallbackIsPreserved() {
        var clicks = 0
        fixture(onClick = { clicks++ })
        compose.onNodeWithTag("action").performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun disabledButtonDoesNotDispatchTouch() {
        var clicks = 0
        fixture(enabled = false, onClick = { clicks++ })
        compose.onNodeWithTag("action").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, clicks) }
    }
}
