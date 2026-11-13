package com.vladimir.messenger.ui.navigation

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vladimir.messenger.R
import com.vladimir.messenger.data.folder.ChatFolder
import com.vladimir.messenger.ui.screens.chat.ChatFoldersContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FolderReturnInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun createAndEditReturnToFolderListWithoutSavingOrLeavingIt() {
        var left = 0
        var created = 0
        var updated = 0
        var dispatcher: OnBackPressedDispatcher? = null
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MaterialTheme {
                ChatFoldersContent(
                    folders = listOf(ChatFolder("one", "Existing folder")),
                    options = emptyList(),
                    onBackClick = { left++ },
                    onAddRecommended = { _, _ -> },
                    onCreate = { _, _ -> created++ },
                    onUpdate = { _, _, _ -> updated++ },
                    onDelete = {},
                    onMove = { _, _ -> },
                )
            }
        }
        fun openCreate() {
            compose.onNodeWithTag("folder-list").performScrollToNode(hasText(text(R.string.folder_create)))
            compose.onNodeWithText(text(R.string.folder_create)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.action_save)).assertIsDisplayed()
        }
        fun verifyList() {
            compose.onNodeWithTag("folder-list").assertIsDisplayed()
            compose.runOnIdle { assertEquals(0, left); assertEquals(0, created); assertEquals(0, updated) }
        }

        openCreate()
        compose.onNodeWithContentDescription(text(R.string.action_back)).assertIsDisplayed().performClick()
        verifyList()

        openCreate()
        compose.runOnIdle { checkNotNull(dispatcher).onBackPressed() }
        verifyList()

        openCreate()
        compose.onRoot().performTouchInput { swipeRight() }
        verifyList()

        // The same rule applies to an existing folder's editor, even after a
        // draft was typed: Back is not Save and does not pop the whole route.
        compose.onNodeWithTag("folder-list").performScrollToNode(hasText("Existing folder"))
        compose.onNodeWithContentDescription(text(R.string.chat_more)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.folder_edit)).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Unsaved draft")
        compose.runOnIdle { checkNotNull(dispatcher).onBackPressed() }
        verifyList()

        compose.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        compose.runOnIdle { assertEquals(1, left); assertEquals(0, created); assertEquals(0, updated) }
    }

    @Test fun savingStillCreatesTheFolderAndReturnsToTheList() {
        var createdName: String? = null
        compose.setContent {
            MaterialTheme {
                ChatFoldersContent(
                    folders = emptyList(), options = emptyList(), onBackClick = {},
                    onAddRecommended = { _, _ -> },
                    onCreate = { name, _ -> createdName = name },
                    onUpdate = { _, _, _ -> }, onDelete = {}, onMove = { _, _ -> },
                )
            }
        }
        compose.onNodeWithTag("folder-list").performScrollToNode(hasText(text(R.string.folder_create)))
        compose.onNodeWithText(text(R.string.folder_create)).performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Work")
        compose.onNodeWithText(text(R.string.action_save)).performClick()
        compose.onNodeWithTag("folder-list").assertIsDisplayed()
        compose.runOnIdle { assertEquals("Work", createdName) }
    }
}
