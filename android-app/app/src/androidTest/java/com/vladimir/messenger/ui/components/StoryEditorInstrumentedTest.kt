package com.vladimir.messenger.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real renderer tests; compilation alone does not verify an Android IME. */
@RunWith(AndroidJUnit4::class)
class StoryEditorInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private var text by mutableStateOf("")
    private var editing by mutableStateOf(true)
    private var compact by mutableStateOf(false)
    private var largeFont by mutableStateOf(false)

    private fun fixture(photo: Boolean = false, pixelDensity: Float? = null) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(pixelDensity ?: density.density, if (largeFont) 2f else 1f)) {
                MaterialTheme {
                    Box(Modifier.width(300.dp).height(if (compact) 260.dp else 600.dp).testTag("host"),
                        contentAlignment = Alignment.Center) {
                        StoryContentCanvas(text, if (photo) PIXEL else null,
                            Brush.linearGradient(listOf(Color.Blue, Color.Magenta)),
                            modifier = Modifier.testTag("canvas"),
                            onTextChange = if (editing) ({ text = StoryPublish.editorText(it) }) else null)
                    }
                }
            }
        }
    }

    private fun layout(tag: String): TextLayoutResult {
        val result = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        return result.single()
    }

    private fun assertSameLines(first: TextLayoutResult, second: TextLayoutResult) {
        assertEquals(first.lineCount, second.lineCount)
        for (line in 0 until first.lineCount) assertEquals(first.getLineEnd(line), second.getLineEnd(line))
        assertEquals(first.layoutInput.style.fontSize, second.layoutInput.style.fontSize)
        assertEquals(first.layoutInput.style.lineHeight, second.layoutInput.style.lineHeight)
        assertFalse(first.hasVisualOverflow)
        assertFalse(second.hasVisualOverflow)
    }

    @Test fun inputKeepsEnterBlankLinesAndWrapsInsteadOfScrollingHorizontally() {
        fixture()
        val input = "Первая строка\n\nВторая строка: длинный текст переносится по ширине истории, а не уезжает за край."
        compose.onNodeWithTag("story-editor").performTextInput(input)
        compose.onNodeWithTag("story-editor").assertTextEquals(input)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Default))
        assertTrue(layout("story-editor").lineCount > 3)
        compose.runOnIdle { assertEquals(input, text) }
    }

    @Test fun keyboardSizedPreviewAndViewerHaveTheSameLineBreaks() {
        fixture()
        compose.onNodeWithTag("story-editor").performTextInput("Текст сразу виден целиком.\nА эта строка задана вручную.\n\nПоследняя строка.")
        val before = layout("story-editor")
        compose.runOnIdle { compact = true }
        assertSameLines(before, layout("story-editor"))
        compose.runOnIdle { editing = false; compact = false; largeFont = true }
        assertSameLines(before, layout("story-preview"))
        val canvas = compose.onNodeWithTag("canvas").fetchSemanticsNode().boundsInRoot
        assertEquals(9f / 16f, canvas.width / canvas.height, .01f)
    }

    @Test fun photoCaptionUsesTheSameTypographyAndPlacementInBothModes() {
        fixture(photo = true)
        compose.onNodeWithTag("story-editor").performTextInput("Подпись к фото\nсо второй строкой")
        val before = layout("story-editor")
        compose.runOnIdle { editing = false; compact = true }
        assertSameLines(before, layout("story-preview"))
        val canvas = compose.onNodeWithTag("canvas").fetchSemanticsNode().boundsInRoot
        val caption = compose.onNodeWithTag("story-preview").fetchSemanticsNode().boundsInRoot
        assertTrue(caption.left >= canvas.left && caption.right <= canvas.right + 1f)
        assertTrue(caption.top >= canvas.top && caption.bottom <= canvas.bottom + 1f)
    }

    @Test fun longTextAndManyNewlinesFitWithoutClipping() {
        fixture()
        compose.onNodeWithTag("story-editor").performTextInput(("а\n").repeat(100))
        val before = layout("story-editor")
        compose.runOnIdle { editing = false }
        assertSameLines(before, layout("story-preview"))
        assertEquals(101, before.lineCount)
    }

    @Test fun fractionalDensityCaptionStillFitsAndMatches() {
        fixture(photo = true, pixelDensity = 2.625f)
        compose.onNodeWithTag("story-editor").performTextInput(("Строка\n").repeat(28))
        val before = layout("story-editor")
        compose.runOnIdle { editing = false; compact = true }
        assertSameLines(before, layout("story-preview"))
    }

    companion object {
        private const val PIXEL = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII="
    }
}
