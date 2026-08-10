package com.vladimir.messenger.ui.navigation

// =============================================================================
// NAVGRAPH.KT вЂ” РќР°РІРёРіР°С†РёРѕРЅРЅС‹Р№ РіСЂР°С„ РїСЂРёР»РѕР¶РµРЅРёСЏ
// =============================================================================
// РђСЂС…РёС‚РµРєС‚СѓСЂР° РЅР°РІРёРіР°С†РёРё:
//   - РСЃРїРѕР»СЊР·СѓРµРј Jetpack Navigation Compose
//   - Р•РґРёРЅС‹Р№ NavHost РЅР° СѓСЂРѕРІРЅРµ MainActivity
//   - РњР°СЂС€СЂСѓС‚С‹ РѕРїСЂРµРґРµР»РµРЅС‹ С‡РµСЂРµР· sealed class (С‚РёРїРѕР±РµР·РѕРїР°СЃРЅРѕСЃС‚СЊ)
//   - Deep Links РѕР±СЂР°Р±Р°С‚С‹РІР°СЋС‚СЃСЏ Р·РґРµСЃСЊ Р¶Рµ
//
// РњР°СЂС€СЂСѓС‚С‹:
//   Onboarding в†’ ChatList (РµСЃР»Рё РїСЂРѕС„РёР»СЊ СЃРѕР·РґР°РЅ)
//   ChatList в†’ ChatDetail
//   ChatList в†’ AddContact
//   ChatList в†’ Settings
// =============================================================================

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.vladimir.messenger.ui.screens.onboarding.OnboardingScreen
import com.vladimir.messenger.ui.screens.chat.ChatListScreen
import com.vladimir.messenger.ui.screens.chat.ChatDetailScreen
import com.vladimir.messenger.ui.screens.contacts.AddContactScreen
import com.vladimir.messenger.ui.screens.contacts.RenameContactScreen
import com.vladimir.messenger.ui.screens.settings.SettingsScreen
import com.vladimir.messenger.ui.screens.mtproxy.MtProxyListScreen
import com.vladimir.messenger.ui.screens.share.ShareProfileScreen
import com.vladimir.messenger.ui.screens.qr.QrScannerScreen

// =============================================================================
// РњРђР РЁР РЈРўР«
// =============================================================================

// Sealed class РґР»СЏ С‚РёРїРѕР±РµР·РѕРїР°СЃРЅРѕР№ РЅР°РІРёРіР°С†РёРё
// РљР°Р¶РґС‹Р№ РѕР±СЉРµРєС‚ = РѕРґРёРЅ СЌРєСЂР°РЅ
sealed class Screen(val route: String) {

    // РћРЅР±РѕСЂРґРёРЅРі (РїРµСЂРІС‹Р№ Р·Р°РїСѓСЃРє)
    data object Onboarding : Screen("onboarding")

    // РЎРїРёСЃРѕРє С‡Р°С‚РѕРІ (РіР»Р°РІРЅС‹Р№ СЌРєСЂР°РЅ)
    data object ChatList : Screen("chat_list")

    // Р”РµС‚Р°Р»СЊРЅС‹Р№ СЌРєСЂР°РЅ С‡Р°С‚Р°
    // {chatId} Рё {contactName} вЂ” РїР°СЂР°РјРµС‚СЂС‹ РјР°СЂС€СЂСѓС‚Р°
    data object ChatDetail : Screen("chat/{chatId}?contactName={contactName}") {
        fun createRoute(chatId: String, contactName: String) =
            "chat/$chatId?contactName=${contactName}"
    }

    // Р”РѕР±Р°РІР»РµРЅРёРµ РєРѕРЅС‚Р°РєС‚Р°
    data object AddContact : Screen("add_contact?inviteLink={inviteLink}") {
        // inviteLink РѕРїС†РёРѕРЅР°Р»РµРЅ (РјРѕР¶РµС‚ РїСЂРёР№С‚Рё РёР· Deep Link)
        fun createRoute(inviteLink: String? = null) =
            if (inviteLink != null) "add_contact?inviteLink=$inviteLink"
            else "add_contact"
    }

    // РќР°СЃС‚СЂРѕР№РєРё
    data object Settings : Screen("settings")

    // MTProto прокси
    data object MtProxy : Screen("mtproxy")

    // Поделиться профилем
    data object ShareProfile : Screen("share_profile")

    // Переименование контакта
    data object RenameContact : Screen("rename_contact/{contactId}/{currentName}") {
        fun createRoute(contactId: String, currentName: String): String {
            val encodedId = java.net.URLEncoder.encode(contactId, "UTF-8")
            val encodedName = java.net.URLEncoder.encode(currentName, "UTF-8")
            return "rename_contact/$encodedId/$encodedName"
        }
    }

    // QR-сканер для добавления контакта
    data object QrScanner : Screen("qr_scanner")
}

// =============================================================================
// Р“Р›РђР’РќР«Р™ NAV HOST
// =============================================================================

@Composable
fun MessengerNavGraph(
    navController: NavHostController = rememberNavController(),
    startDestination: String,  // РћРїСЂРµРґРµР»СЏРµС‚СЃСЏ РІ MainActivity (onboarding РёР»Рё chat_list)
) {
    // Р”Р»РёС‚РµР»СЊРЅРѕСЃС‚СЊ Р°РЅРёРјР°С†РёРё РїРµСЂРµС…РѕРґРѕРІ (РјСЃ)
    val transitionDuration = 300

    NavHost(
        navController    = navController,
        startDestination = startDestination,
        // РЎС‚Р°РЅРґР°СЂС‚РЅС‹Рµ Р°РЅРёРјР°С†РёРё: РІС…РѕРґ/РІС‹С…РѕРґ С‡РµСЂРµР· slide + fade
        enterTransition = {
            slideIntoContainer(
                towards   = AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(transitionDuration)
            ) + fadeIn(tween(transitionDuration))
        },
        exitTransition = {
            slideOutOfContainer(
                towards   = AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(transitionDuration)
            ) + fadeOut(tween(transitionDuration))
        },
        popEnterTransition = {
            slideIntoContainer(
                towards   = AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(transitionDuration)
            ) + fadeIn(tween(transitionDuration))
        },
        popExitTransition = {
            slideOutOfContainer(
                towards   = AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(transitionDuration)
            ) + fadeOut(tween(transitionDuration))
        }
    ) {

        // ------------------------------------------------------------------
        // РћРќР‘РћР Р”РРќР“
        // ------------------------------------------------------------------
        composable(route = Screen.Onboarding.route) {
            OnboardingScreen(
                onProfileCreated = {
                    // РџРѕСЃР»Рµ СЃРѕР·РґР°РЅРёСЏ РїСЂРѕС„РёР»СЏ в†’ РіР»Р°РІРЅС‹Р№ СЌРєСЂР°РЅ
                    // clearBackStack: РЅРµР»СЊР·СЏ РІРµСЂРЅСѓС‚СЊСЃСЏ РЅР°Р·Р°Рґ Рє РѕРЅР±РѕСЂРґРёРЅРіСѓ
                    // Restart CoreServerService with new displayName
                    val ctx = navController.context
                    val svcIntent = android.content.Intent(ctx, com.vladimir.messenger.service.CoreServerService::class.java)
                    ctx.stopService(svcIntent)
                    androidx.core.content.ContextCompat.startForegroundService(ctx, svcIntent)
                    navController.navigate(Screen.ChatList.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }

        // ------------------------------------------------------------------
        // РЎРџРРЎРћРљ Р§РђРўРћР’ (РіР»Р°РІРЅС‹Р№ СЌРєСЂР°РЅ)
        // ------------------------------------------------------------------
        composable(route = Screen.ChatList.route) {
            ChatListScreen(
                onChatClick = { chatId, contactName ->
                    navController.navigate(
                        Screen.ChatDetail.createRoute(chatId, contactName)
                    )
                },
                onAddContactClick = {
                    navController.navigate(Screen.AddContact.createRoute())
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                },
                onScanQrClick = {
                    navController.navigate(Screen.QrScanner.route)
                },
                onShowMyQrClick = {
                    navController.navigate(Screen.ShareProfile.route)
                }
            )
        }

        // ------------------------------------------------------------------
        // Р§РђРў (РґРµС‚Р°Р»СЊРЅС‹Р№ СЌРєСЂР°РЅ)
        // ------------------------------------------------------------------
        composable(
            route = Screen.ChatDetail.route,
            arguments = listOf(
                navArgument("chatId") {
                    type = NavType.StringType
                },
                navArgument("contactName") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val chatId = backStackEntry.arguments?.getString("chatId") ?: return@composable
            val contactName = backStackEntry.arguments?.getString("contactName") ?: "Unknown"

            ChatDetailScreen(
                chatId      = chatId,
                contactId   = chatId,
                contactName = contactName,
                onBackClick = { navController.popBackStack() },
                onRenameClick = { cId, cName ->
                    navController.navigate(Screen.RenameContact.createRoute(cId, cName))
                }
            )
        }

        // ------------------------------------------------------------------
        // Р”РћР‘РђР’Р›Р•РќРР• РљРћРќРўРђРљРўРђ
        // ------------------------------------------------------------------
        composable(
            route = Screen.AddContact.route,
            arguments = listOf(
                navArgument("inviteLink") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            ),
            // Deep Link: p2p://invite/BASE64...
            deepLinks = listOf(
                navDeepLink {
                    uriPattern = "p2p://invite/{inviteLink}"
                }
            )
        ) { backStackEntry ->
            val inviteLink = backStackEntry.arguments?.getString("inviteLink")

            AddContactScreen(
                initialInviteLink = inviteLink,
                onContactAdded    = {
                    navController.popBackStack()
                },
                onBackClick       = { navController.popBackStack() }
            )
        }

        // ------------------------------------------------------------------
        // РќРђРЎРўР РћР™РљР
        // ------------------------------------------------------------------
        composable(route = Screen.Settings.route) {
            SettingsScreen(
                onBackClick = { navController.popBackStack() },
                onShareProfileClick = { navController.navigate(Screen.ShareProfile.route) },
                onMtProxyClick = { navController.navigate(Screen.MtProxy.route) }
            )
        }

        // ------------------------------------------------------------------
        // MTPROTO ПРОКСИ
        // ------------------------------------------------------------------
        composable(route = Screen.MtProxy.route) {
            MtProxyListScreen(
                onBackClick = { navController.popBackStack() }
            )
        }


        // ------------------------------------------------------------------
        // ПОДЕЛИТЬСЯ ПРОФИЛЕМ
        // ------------------------------------------------------------------
        composable(route = Screen.ShareProfile.route) {
            ShareProfileScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        // ------------------------------------------------------------------
        // QR-СКАНЕР
        // ------------------------------------------------------------------
        composable(
            route = Screen.RenameContact.route,
            arguments = listOf(
                androidx.navigation.navArgument("contactId") { type = androidx.navigation.NavType.StringType },
                androidx.navigation.navArgument("currentName") { type = androidx.navigation.NavType.StringType },
            ),
        ) {
            RenameContactScreen(
                onRenamed = { navController.popBackStack() },
                onBackClick = { navController.popBackStack() },
            )
        }

        composable(route = Screen.QrScanner.route) {
            QrScannerScreen(
                onBackClick = { navController.popBackStack() },
                onQrScanned = { qrContent ->
                    // Извлечь invite link из QR и перейти к AddContact
                    val inviteLink = qrContent.removePrefix("p2p://invite/")
                    navController.navigate(Screen.AddContact.createRoute(inviteLink)) {
                        popUpTo(Screen.QrScanner.route) { inclusive = true }
                    }
                }
            )
        }
    }
}
