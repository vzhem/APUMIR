package com.vladimir.messenger.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vladimir.messenger.R
import com.vladimir.messenger.data.call.CallStateMachine
import com.vladimir.messenger.ui.components.ApuBackButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CallAndCommunityNavigationInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun minimizedCallCanBeReopenedWithoutCoveringTheCurrentScreen() {
        val phase = mutableStateOf(CallStateMachine.Phase.ACTIVE)
        val onCallScreen = mutableStateOf(false)
        var returns = 0
        compose.setContent {
            MaterialTheme {
                CallAwareNavContainer(
                    phase = phase.value,
                    onCallScreen = onCallScreen.value,
                    onReturn = { returns++; onCallScreen.value = true },
                ) { modifier ->
                    Box(modifier.testTag("underlying-screen")) { Text("Current screen") }
                }
            }
        }
        val banner = compose.onNodeWithText(text(R.string.call_return_to_call)).assertIsDisplayed()
        val bannerBottom = banner.fetchSemanticsNode().boundsInRoot.bottom
        val contentTop = compose.onNodeWithTag("underlying-screen").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Call banner must not cover screen controls", bannerBottom <= contentTop)
        banner.performClick()
        compose.onNodeWithText(text(R.string.call_return_to_call)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, returns); onCallScreen.value = false; phase.value = CallStateMachine.Phase.ENDED }
        compose.onNodeWithText(text(R.string.call_return_to_call)).assertDoesNotExist()
        compose.runOnIdle { phase.value = CallStateMachine.Phase.INCOMING }
        compose.onNodeWithText(text(R.string.call_return_to_call)).assertIsDisplayed()
    }

    @Test fun callCanBeMinimizedAndReopenedRepeatedlyWithoutAnOutgoingPeerArgument() {
        val phase = mutableStateOf(CallStateMachine.Phase.ACTIVE)
        compose.setContent {
            MaterialTheme {
                val nav = rememberNavController()
                val stack = nav.currentBackStackEntryAsState().value
                CallAwareNavContainer(
                    phase = phase.value,
                    onCallScreen = stack?.destination?.route == Screen.Call.route,
                    onReturn = { nav.navigate(Screen.Call.incoming) { launchSingleTop = true } },
                ) { modifier ->
                    NavHost(navController = nav, startDestination = Screen.ChatList.route, modifier = modifier) {
                        composable(Screen.ChatList.route) { Text("Chats") }
                        composable(
                            Screen.Call.route,
                            arguments = listOf(
                                navArgument("peerId") { type = NavType.StringType; nullable = true; defaultValue = "" },
                                navArgument("peerName") { type = NavType.StringType; nullable = true; defaultValue = "" },
                                navArgument("video") { type = NavType.StringType; nullable = true; defaultValue = "0" },
                            ),
                        ) { entry ->
                            Column {
                                Text("Resume peer=${entry.arguments?.getString("peerId").orEmpty()}")
                                ApuBackButton(onClick = { nav.popBackStack() })
                            }
                        }
                    }
                }
            }
        }
        repeat(2) {
            compose.onNodeWithText(text(R.string.call_return_to_call)).assertIsDisplayed().performClick()
            compose.onNodeWithText("Resume peer=").assertIsDisplayed()
            compose.onNodeWithText(text(R.string.call_return_to_call)).assertDoesNotExist()
            compose.onNodeWithContentDescription(text(R.string.action_back)).performClick()
            compose.onNodeWithText("Chats").assertIsDisplayed()
            compose.runOnIdle { assertEquals(CallStateMachine.Phase.ACTIVE, phase.value) }
        }
    }

    @Test fun returnBannerCoversOnlyLiveCallPhasesAndNotTheCallScreen() {
        for (phase in CallStateMachine.Phase.entries) {
            val live = phase != CallStateMachine.Phase.IDLE && phase != CallStateMachine.Phase.ENDED
            assertEquals("phase $phase", live, canReturnToCall(phase, onCallScreen = false))
            assertFalse(canReturnToCall(phase, onCallScreen = true))
        }
    }

    @Test fun leavingCommunityReturnsToCommunitiesOrFallsBackToChats() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(navController = nav, startDestination = Screen.ChatList.route, modifier = Modifier.fillMaxSize()) {
                composable(Screen.ChatList.route) { Text("Chats") }
                composable(
                    Screen.Groups.route,
                    arguments = listOf(
                        navArgument("joinLink") { type = NavType.StringType; nullable = true; defaultValue = null },
                        navArgument("create") { type = NavType.StringType; nullable = true; defaultValue = null },
                    ),
                ) { Text("Communities") }
                composable("group-chat") { Text("Group") }
                composable("admin") { Text("Admin") }
            }
        }
        compose.runOnIdle {
            nav.navigate(Screen.Groups.plain)
            nav.navigate("group-chat")
            nav.navigate("admin")
            returnAfterLeavingCommunity(nav)
            assertEquals(Screen.Groups.route, nav.currentBackStackEntry?.destination?.route)
            nav.popBackStack(Screen.ChatList.route, inclusive = false)
            nav.navigate("group-chat")
            nav.navigate("admin")
            returnAfterLeavingCommunity(nav)
            assertEquals(Screen.ChatList.route, nav.currentBackStackEntry?.destination?.route)
        }
    }

    @Test fun leavingCommunityWithoutNormalRootDoesNotRevealTheDeletedGroup() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(navController = nav, startDestination = Screen.Onboarding.route) {
                composable(Screen.Onboarding.route) { Text("Onboarding") }
                composable("group-chat") { Text("Group") }
                composable("admin") { Text("Admin") }
            }
        }
        compose.runOnIdle {
            nav.navigate("group-chat")
            nav.navigate("admin")
            returnAfterLeavingCommunity(nav)
            assertEquals(Screen.Onboarding.route, nav.currentBackStackEntry?.destination?.route)
        }
    }

    @Test fun unknownDeepLinkStackIsReplacedInsteadOfReturningToDeletedGroup() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(navController = nav, startDestination = "external") {
                composable("external") { Text("External") }
                composable("group-chat") { Text("Group") }
                composable("admin") { Text("Admin") }
                composable(Screen.ChatList.route) { Text("Chats") }
            }
        }
        compose.runOnIdle {
            nav.navigate("group-chat")
            nav.navigate("admin")
            returnAfterLeavingCommunity(nav)
            assertEquals(Screen.ChatList.route, nav.currentBackStackEntry?.destination?.route)
            assertFalse(nav.popBackStack("group-chat", inclusive = false))
        }
    }
}
