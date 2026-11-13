package com.vladimir.messenger.ui.navigation

import androidx.navigation.NavHostController

/** After leaving/deleting, don't strand the user on a deleted community. */
internal fun returnAfterLeavingCommunity(navController: NavHostController) {
    if (navController.popBackStack(Screen.Groups.route, inclusive = false) ||
        navController.popBackStack(Screen.ChatList.route, inclusive = false) ||
        navController.popBackStack(Screen.Onboarding.route, inclusive = false)
    ) return

    // A deep-link stack can lack all ordinary parents. A single pop could
    // expose the group that was just deleted; replace the stack with chats.
    navController.navigate(Screen.ChatList.route) {
        popUpTo(navController.graph.id) { inclusive = false }
        launchSingleTop = true
    }
}
