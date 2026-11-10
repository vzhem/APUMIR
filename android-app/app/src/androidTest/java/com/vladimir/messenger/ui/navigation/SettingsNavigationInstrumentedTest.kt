package com.vladimir.messenger.ui.navigation

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vladimir.messenger.R
import com.vladimir.messenger.ui.components.ApuBackButton
import com.vladimir.messenger.ui.components.swipeBack
import com.vladimir.messenger.ui.screens.settings.SettingsScrollMemory
import com.vladimir.messenger.ui.screens.settings.rememberSettingsScrollState
import com.vladimir.messenger.ui.screens.settings.settingsBackAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsNavigationInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun everySectionReturnsToRootByButtonAndSystemBack() {
        val section = mutableStateOf<String?>(null)
        val profile = mutableStateOf(false)
        var leaves = 0
        var dispatcher: OnBackPressedDispatcher? = null
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            val back = settingsBackAction(
                sectionOpen = section.value != null && !profile.value,
                onCloseSection = { section.value = null },
                onLeave = { leaves++ },
            )
            Box(Modifier.fillMaxSize().swipeBack(onBack = back).testTag("settings-shell")) {
                ApuBackButton(onClick = back)
            }
        }
        val sections = listOf("Chats", "Notifications", "Appearance", "Security", "Network", "Files", "Hearts", "Updates", "Help", "Features")
        for (name in sections) {
            compose.runOnIdle { section.value = name }
            compose.onNodeWithContentDescription(text(R.string.action_back)).assertIsDisplayed().performClick()
            compose.runOnIdle { assertNull(section.value); assertEquals(0, leaves); section.value = name }
            compose.waitForIdle()
            compose.runOnIdle { checkNotNull(dispatcher).onBackPressed() }
            compose.runOnIdle { assertNull(section.value); assertEquals(0, leaves) }
        }
        compose.runOnIdle { section.value = "Network" }
        compose.onNodeWithTag("settings-shell").performTouchInput { swipeRight() }
        compose.runOnIdle { assertNull(section.value); assertEquals(0, leaves) }
        compose.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        compose.runOnIdle { assertEquals(1, leaves); section.value = "Network"; profile.value = true }
        // Profile is its own route even if an old section value exists.
        compose.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        compose.runOnIdle { assertEquals(2, leaves); assertEquals("Network", section.value) }
    }

    @Test fun deepRouteReturnsToSectionThenRestoresRootScrollPosition() {
        var leaves = 0
        compose.setContent {
            MaterialTheme {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "settings") {
                    composable("settings") {
                        var section by rememberSaveable { mutableStateOf<String?>(null) }
                        val back = settingsBackAction(section != null, { section = null }, { leaves++ })
                        val scroll = rememberSettingsScrollState(section ?: SettingsScrollMemory.ROOT)
                        Column(Modifier.fillMaxSize()) {
                            ApuBackButton(onClick = back)
                            Text(section ?: "Root")
                            if (section == null) {
                                Button(onClick = { section = "Network" }) { Text("Open network") }
                            } else {
                                Button(onClick = { nav.navigate("child") }) { Text("Open nested screen") }
                            }
                            LazyColumn(state = scroll, modifier = Modifier.weight(1f).testTag("settings-list")) {
                                items(60) { index ->
                                    Text("${section ?: "root"}-$index", Modifier.fillMaxWidth().height(64.dp))
                                }
                            }
                        }
                    }
                    composable("child") {
                        Column {
                            Text("Nested screen")
                            ApuBackButton(onClick = { nav.popBackStack() })
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("settings-list").performScrollToIndex(18)
        compose.onNodeWithText("root-18").assertIsDisplayed()
        compose.onNodeWithText("Open network").performClick()
        compose.onNodeWithText("Network-0").assertIsDisplayed()
        compose.onNodeWithTag("settings-list").performScrollToIndex(12)
        compose.onNodeWithText("Network-12").assertIsDisplayed()
        compose.onNodeWithText("Open nested screen").performClick()
        compose.onNodeWithText("Nested screen").assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        compose.onNodeWithText("Network-0").assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        compose.onNodeWithText("root-18").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, leaves) }
        // A newly opened section still starts at the top.
        compose.onNodeWithText("Open network").performClick()
        compose.onNodeWithText("Network-0").assertIsDisplayed()
    }
}
