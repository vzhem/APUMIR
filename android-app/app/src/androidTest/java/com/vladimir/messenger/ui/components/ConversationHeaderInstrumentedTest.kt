package com.vladimir.messenger.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationHeaderInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longNicknameWrapsOnNarrowPhoneAtLargeFont() {
        val large = mutableStateOf(false)
        val name = "Пользователь_с_длинным_ником"
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large.value) 2f else 1f)) {
                MaterialTheme {
                    ConversationHeader(name, "не в сети", false, {}, {}, emptyList(),
                        modifier = Modifier.width(320.dp), avatar = { Box(Modifier.size(30.dp)) }, vip = true)
                }
            }
        }
        fun assertFullName() {
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("conversation-header-name", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            val layout = results.single()
            assertEquals(name, layout.layoutInput.text.text)
            assertFalse(layout.hasVisualOverflow)
            for (line in 0 until layout.lineCount) assertFalse(layout.isLineEllipsized(line))
        }
        assertFullName()
        compose.runOnIdle { large.value = true }
        assertFullName()
    }

    @Test fun secondaryActionsAppearOnlyOnTapAndCloseAfterSelection() {
        var selected = 0
        compose.setContent {
            MaterialTheme {
                ConversationHeader("Name", "Offline", false, {}, {}, listOf(
                    ApuAction("Search", Icons.Default.Search) { selected++ },
                    ApuAction("Call", Icons.Default.Call, enabled = false) { selected += 100 },
                ))
            }
        }
        compose.onNodeWithText("Search").assertDoesNotExist()
        compose.onNodeWithTag("conversation-header-more").performClick()
        compose.onNodeWithText("Search").assertIsDisplayed()
        compose.onNodeWithText("Call").performClick()
        compose.runOnIdle { assertEquals(0, selected) }
        compose.onNodeWithText("Search").performClick()
        compose.onNodeWithText("Search").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, selected) }
    }
}
