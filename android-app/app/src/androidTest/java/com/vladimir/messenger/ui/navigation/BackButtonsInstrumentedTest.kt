package com.vladimir.messenger.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vladimir.messenger.R
import com.vladimir.messenger.ui.components.ApuBackButton
import com.vladimir.messenger.ui.components.ApuMainTabBar
import com.vladimir.messenger.ui.components.ApuTab
import com.vladimir.messenger.ui.components.ApuTabActions
import com.vladimir.messenger.ui.screens.devices.DevicesScreenScaffold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.pow

@RunWith(AndroidJUnit4::class)
class BackButtonsInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun backButtonIsVisibleContrastingAndClickableOnLightAndDarkWallpapers() {
        val wallpaper = mutableStateOf(Color.Black)
        val dark = mutableStateOf(false)
        var clicks = 0
        compose.setContent {
            MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else lightColorScheme()) {
                Box(Modifier.fillMaxSize().background(wallpaper.value)) {
                    Scaffold(
                        containerColor = Color.Transparent,
                        topBar = {
                            TopAppBar(
                                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                                title = { Text("Header") },
                                navigationIcon = { ApuBackButton(onClick = { clicks++ }) },
                            )
                        },
                    ) { }
                }
            }
        }
        for (background in listOf(Color.Black, Color.White)) {
            for (night in listOf(false, true)) {
                compose.runOnIdle { wallpaper.value = background; dark.value = night }
                val button = compose.onNodeWithContentDescription(text(R.string.action_back))
                button.assertIsDisplayed().assertHasClickAction()
                    .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                // Examine the central half of the actual rendered control, not
                // its transparent corners against the wallpaper.
                val pixels = button.captureToImage().toPixelMap()
                var lightest = 0.0
                var darkest = 1.0
                for (y in pixels.height / 4 until pixels.height * 3 / 4) {
                    for (x in pixels.width / 4 until pixels.width * 3 / 4) {
                        val luminance = luminance(pixels[x, y])
                        lightest = maxOf(lightest, luminance)
                        darkest = minOf(darkest, luminance)
                    }
                }
                val contrast = (lightest + 0.05) / (darkest + 0.05)
                assertTrue("Back button contrast $contrast, dark=$night, wallpaper=$background", contrast >= 4.5)
            }
        }
        compose.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun deviceHeaderStaysVisibleWhilePairingContentScrolls() {
        var back = 0
        compose.setContent {
            MaterialTheme {
                DevicesScreenScaffold(onBackClick = { back++ }) {
                    repeat(40) { row ->
                        Text("Device row $row", modifier = Modifier.fillMaxWidth().height(64.dp))
                    }
                }
            }
        }
        val button = compose.onNodeWithContentDescription(text(R.string.action_back))
        val originalTop = button.assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithText("Device row 39").performScrollTo().assertIsDisplayed()
        val afterScrollTop = button.assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        assertEquals(originalTop.toDouble(), afterScrollTop.toDouble(), 1.0)
        button.performClick()
        compose.runOnIdle { assertEquals(1, back) }
    }

    @Test fun savedScreenCanNavigateToEveryMainTabIncludingChats() {
        val current = mutableStateOf<ApuTab?>(null)
        val taps = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                ApuMainTabBar(
                    current = current.value,
                    actions = ApuTabActions(
                        onChats = { taps += "chats" },
                        onContacts = { taps += "contacts" },
                        onGroups = { taps += "groups" },
                        onProfile = { taps += "profile" },
                        onSettings = { taps += "settings" },
                    ),
                )
            }
        }
        listOf(
            R.string.nav_chats, R.string.nav_contacts, R.string.nav_communities,
            R.string.nav_profile, R.string.nav_settings,
        ).forEach { compose.onNodeWithText(text(it)).assertIsDisplayed().performClick() }
        compose.runOnIdle {
            assertEquals(listOf("chats", "contacts", "groups", "profile", "settings"), taps)
            current.value = ApuTab.Chats
        }
        compose.onNodeWithText(text(R.string.nav_chats)).performClick()
        compose.runOnIdle { assertEquals(5, taps.size) }
    }

    private fun luminance(color: Color): Double {
        fun linear(value: Float): Double =
            if (value <= 0.04045f) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }
}
