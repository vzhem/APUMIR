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
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.vladimir.messenger.ui.screens.saved.SavedScreen
import com.vladimir.messenger.ui.screens.onboarding.OnboardingScreen
import com.vladimir.messenger.ui.screens.chat.ChatListScreen
import com.vladimir.messenger.ui.screens.chat.ChatDetailScreen
import com.vladimir.messenger.ui.screens.contacts.AddContactScreen
import com.vladimir.messenger.ui.components.ApuMainTabBar
import com.vladimir.messenger.ui.components.ApuTab
import com.vladimir.messenger.ui.components.ApuTabActions
import com.vladimir.messenger.ui.screens.contacts.ContactsScreen
import com.vladimir.messenger.ui.screens.contacts.RenameContactScreen
import com.vladimir.messenger.ui.screens.settings.SettingsScreen
import com.vladimir.messenger.ui.screens.settings.RankBenefitsScreen
import com.vladimir.messenger.ui.screens.mtproxy.MtProxyListScreen
import com.vladimir.messenger.ui.screens.share.ShareProfileScreen
import com.vladimir.messenger.ui.screens.qr.QrScannerScreen
import com.vladimir.messenger.ui.screens.groups.GroupsScreen
import com.vladimir.messenger.ui.screens.groups.GroupChatScreen
import com.vladimir.messenger.ui.screens.groups.GroupAdminScreen
import com.vladimir.messenger.ui.screens.channels.ChannelScreen
import com.vladimir.messenger.data.group.GroupInviteLinks
import com.vladimir.messenger.util.InviteLinkParser
import com.vladimir.messenger.data.call.CallManager
import com.vladimir.messenger.data.call.CallStateMachine
import com.vladimir.messenger.ui.screens.call.CallScreen
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
    data object ChatDetail : Screen("chat/{chatId}?contactName={contactName}&contactId={contactId}") {
        fun createRoute(chatId: String, contactName: String, contactId: String) =
            "chat/$chatId?contactName=${java.net.URLEncoder.encode(contactName, "UTF-8")}&contactId=$contactId"
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

    data object RankBenefits : Screen("rank_benefits")

    // Избранное - личное хранилище абонента
    data object Saved : Screen("saved")

    // Поделиться профилем
    data object ShareProfile : Screen("share_profile")

    // Список контактов
    data object Contacts : Screen("contacts")

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

    // Раздел «Группы»
    data object Groups : Screen("groups?joinLink={joinLink}&create={create}") {
        /**
         * Обычный вход в раздел, без ссылки.
         *
         * Ходить по Screen.Groups.route нельзя: в нём стоит шаблон
         * "{joinLink}", и навигация подставит его как ЕСТЬ - экран получит
         * строку "{joinLink}" и покажет «это не ссылка-приглашение».
         */
        const val plain: String = "groups"

        fun createJoinRoute(link: String): String =
            "groups?joinLink=" + java.net.URLEncoder.encode(link, "UTF-8")

        /** Из меню кнопки-карандаша: сразу открыть диалог создания группы/канала. */
        fun createCreateRoute(create: String): String =
            "groups?create=" + java.net.URLEncoder.encode(create, "UTF-8")
    }

    data object GroupChat : Screen("group_chat/{groupId}?topicId={topicId}") {
        fun createRoute(groupId: String): String =
            "group_chat/" + java.net.URLEncoder.encode(groupId, "UTF-8")

        /**
         * Вход сразу в нужную тему: так канал открывает комментарии
         * конкретного поста, а не первую тему подряд.
         */
        fun createTopicRoute(groupId: String, topicId: String): String =
            "group_chat/" + java.net.URLEncoder.encode(groupId, "UTF-8") +
                "?topicId=" + java.net.URLEncoder.encode(topicId, "UTF-8")
    }

    // Лента канала: посты и комментарии под ними
    data object Channel : Screen("channel/{channelId}") {
        fun createRoute(channelId: String): String =
            "channel/" + java.net.URLEncoder.encode(channelId, "UTF-8")
    }

    data object GroupAdmin : Screen("group_admin/{groupId}") {
        fun createRoute(groupId: String): String =
            "group_admin/" + java.net.URLEncoder.encode(groupId, "UTF-8")
    }

    // Экран звонка: peerId заполнен = исходящий, пустой = нас позвали на входящий.
    data object Call : Screen("call?peerId={peerId}&peerName={peerName}") {
        const val incoming: String = "call"

        fun createOutgoing(peerId: String, peerName: String): String =
            "call?peerId=" + java.net.URLEncoder.encode(peerId, "UTF-8") +
                "&peerName=" + java.net.URLEncoder.encode(peerName, "UTF-8")
    }
}

/** Доступ к синглтону звонков из навигации (входящий звонок → экран звонка сам). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface NavCallEntryPoint {
    fun callManager(): CallManager
}

// =============================================================================
// Р“Р›РђР’РќР«Р™ NAV HOST
// =============================================================================

@Composable
fun MessengerNavGraph(
    navController: NavHostController = rememberNavController(),
    // Определяется в MainActivity: onboarding или chat_list
    startDestination: String,
    /**
     * Ссылка-приглашение, с которой приложение открыли извне (тап по ссылке,
     * QR, Telegram). Не null — сразу ведём в раздел «Группы» и пробуем войти.
     */
    initialGroupInvite: String? = null,
) {
    // Р”Р»РёС‚РµР»СЊРЅРѕСЃС‚СЊ Р°РЅРёРјР°С†РёРё РїРµСЂРµС…РѕРґРѕРІ (РјСЃ)
    val transitionDuration = 300

    // Приложение открыли по ссылке-приглашению в группу — ведём в «Группы».
    LaunchedEffect(initialGroupInvite) {
        val link = initialGroupInvite
        if (!link.isNullOrBlank()) {
            navController.navigate(Screen.Groups.createJoinRoute(link))
        }
    }

    // Входящий звонок: экран звонка показывается сам, где бы ни был пользователь.
    // Звонок глобальный (CallManager-синглтон), уведомление из фона возвращает
    // в MainActivity — этот эффект доводит до экрана.
    val navAppContext = LocalContext.current.applicationContext
    val callManager = remember {
        EntryPointAccessors.fromApplication(navAppContext, NavCallEntryPoint::class.java)
            .callManager()
    }
    val callUiState by callManager.uiState.collectAsStateWithLifecycle()
    val currentBackStack by navController.currentBackStackEntryAsState()
    LaunchedEffect(callUiState.phase, callUiState.callId) {
        if (callUiState.phase == CallStateMachine.Phase.INCOMING &&
            currentBackStack?.destination?.route != Screen.Call.route
        ) {
            navController.navigate(Screen.Call.incoming) { launchSingleTop = true }
        }
    }

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
                onChatClick = { chatId, contactName, contactId ->
                    navController.navigate(
                        Screen.ChatDetail.createRoute(chatId, contactName, contactId)
                    )
                },
                onAddContactClick = {
                    navController.navigate(Screen.AddContact.createRoute())
                },
                onCreateGroupClick = {
                    navController.navigate(Screen.Groups.createCreateRoute("group"))
                },
                onCreateChannelClick = {
                    navController.navigate(Screen.Groups.createCreateRoute("channel"))
                },
                onRankClick = {
                    navController.navigate(Screen.RankBenefits.route)
                },
                onSavedClick = {
                    navController.navigate(Screen.Saved.route)
                },
                onContactsClick = {
                    navController.navigate(Screen.Contacts.route)
                },
                onGroupsClick = {
                    navController.navigate(Screen.Groups.plain)
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                },
                onScanQrClick = {
                    navController.navigate(Screen.QrScanner.route)
                },
                onShowMyQrClick = {
                    navController.navigate(Screen.ShareProfile.route)
                },
                onGroupClick = { groupId ->
                    navController.navigate(Screen.GroupChat.createRoute(groupId))
                },
                onGroupAdminClick = { groupId ->
                    navController.navigate(Screen.GroupAdmin.createRoute(groupId))
                },
                onChannelClick = { channelId ->
                    navController.navigate(Screen.Channel.createRoute(channelId))
                },
                onCallClick = { cId, cName ->
                    navController.navigate(Screen.Call.createOutgoing(cId, cName))
                },
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
                },
                navArgument("contactId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            val chatId = backStackEntry.arguments?.getString("chatId") ?: return@composable
            val contactName = backStackEntry.arguments?.getString("contactName")?.let {
                try { java.net.URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
            } ?: "Unknown"
            val contactId = backStackEntry.arguments?.getString("contactId") ?: ""

            ChatDetailScreen(
                chatId      = chatId,
                contactId   = contactId,
                contactName = contactName,
                onBackClick = { navController.popBackStack() },
                onRenameClick = { cId, cName ->
                    navController.navigate(Screen.RenameContact.createRoute(cId, cName))
                },
                onCallClick = { cId, cName ->
                    navController.navigate(Screen.Call.createOutgoing(cId, cName))
                }
            )
        }

        // ------------------------------------------------------------------
        // ЗВОНОК (исходящий и входящий — один экран)
        // ------------------------------------------------------------------
        composable(
            route = Screen.Call.route,
            arguments = listOf(
                navArgument("peerId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = ""
                },
                navArgument("peerName") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = ""
                },
            ),
        ) {
            CallScreen(
                onLeaveCall = { navController.popBackStack() },
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
                onBackClick       = { navController.popBackStack() },
                onScanQrClick     = {
                    navController.navigate(Screen.QrScanner.route)
                },
            )
        }

        // ------------------------------------------------------------------
        // РќРђРЎРўР РћР™РљР
        // ------------------------------------------------------------------
        composable(route = Screen.Settings.route) {
            SettingsScreen(
                bottomBar = {
                    ApuMainTabBar(ApuTab.Settings, mainTabActions(navController))
                },
                onBackClick = { navController.popBackStack() },
                onShareProfileClick = { navController.navigate(Screen.ShareProfile.route) },
                onMtProxyClick = { navController.navigate(Screen.MtProxy.route) },
                onRankBenefitsClick = { navController.navigate(Screen.RankBenefits.route) }
            )
        }

        composable(route = Screen.RankBenefits.route) {
            RankBenefitsScreen(onBackClick = { navController.popBackStack() })
        }

        // ------------------------------------------------------------------
        // MTPROTO ПРОКСИ
        // ------------------------------------------------------------------
        // ------------------------------------------------------------------
        // ИЗБРАННОЕ
        // ------------------------------------------------------------------
        composable(route = Screen.Saved.route) {
            SavedScreen(
                bottomBar = {
                    ApuMainTabBar(ApuTab.Saved, mainTabActions(navController))
                },
                onBackClick = { navController.popBackStack() },
                // Возврат к оригиналу: личный чат, тема группы или лента канала.
                onOpenOrigin = { item ->
                    val route = when (item.originKind) {
                        "CHAT" -> Screen.ChatDetail.createRoute(
                            item.originId,
                            item.originName,
                            item.originContactId,
                        )
                        "GROUP" -> if (item.originTopicId.isNotBlank()) {
                            Screen.GroupChat.createTopicRoute(item.originId, item.originTopicId)
                        } else {
                            Screen.GroupChat.createRoute(item.originId)
                        }
                        "CHANNEL" -> Screen.Channel.createRoute(item.originId)
                        else -> null
                    }
                    if (route != null) navController.navigate(route)
                },
            )
        }

        composable(route = Screen.MtProxy.route) {
            MtProxyListScreen(
                onBackClick = { navController.popBackStack() }
            )
        }


        // ------------------------------------------------------------------
        // ПОДЕЛИТЬСЯ ПРОФИЛЕМ
        // ------------------------------------------------------------------
        composable(route = Screen.Contacts.route) {
            ContactsScreen(
                bottomBar = {
                    ApuMainTabBar(ApuTab.Contacts, mainTabActions(navController))
                },
                onNavigateBack = { navController.popBackStack() },
                onAddContactClick = { navController.navigate(Screen.AddContact.createRoute()) },
                onContactClick = { contact ->
                    navController.navigate(
                        Screen.ChatDetail.createRoute(
                            chatId = contact.id,
                            contactName = contact.displayName,
                            contactId = contact.id,
                        )
                    )
                },
                onRenameContactClick = { cId, cName ->
                    navController.navigate(Screen.RenameContact.createRoute(cId, cName))
                },
                onCallContactClick = { cId, cName ->
                    navController.navigate(Screen.Call.createOutgoing(cId, cName))
                },
            )
        }
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

        // ------------------------------------------------------------------
        // Группы: список, чат с темами, административный кабинет
        // ------------------------------------------------------------------
        composable(
            route = Screen.Groups.route,
            arguments = listOf(
                navArgument("joinLink") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("create") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            GroupsScreen(
                bottomBar = {
                    ApuMainTabBar(ApuTab.Groups, mainTabActions(navController))
                },
                onGroupClick = { groupId ->
                    navController.navigate(Screen.GroupChat.createRoute(groupId))
                },
                onChannelClick = { channelId ->
                    navController.navigate(Screen.Channel.createRoute(channelId))
                },
                onBackClick = { navController.popBackStack() },
                joinLink = entry.arguments?.getString("joinLink"),
                create = entry.arguments?.getString("create"),
            )
        }

        composable(
            route = Screen.GroupChat.route,
            arguments = listOf(
                navArgument("groupId") { type = NavType.StringType },
                navArgument("topicId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            GroupChatScreen(
                onOpenAdmin = { groupId ->
                    navController.navigate(Screen.GroupAdmin.createRoute(groupId))
                },
                onBackClick = { navController.popBackStack() },
                // Значок другой группы в левой колонке: подменяем текущий
                // экран чата, чтобы «Назад» не вёл по цепочке старых групп.
                onSwitchGroup = { groupId ->
                    navController.navigate(Screen.GroupChat.createRoute(groupId)) {
                        popUpTo(Screen.GroupChat.route) { inclusive = true }
                    }
                },
                onSwitchChannel = { channelId ->
                    navController.navigate(Screen.Channel.createRoute(channelId)) {
                        popUpTo(Screen.GroupChat.route) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Screen.Channel.route,
            arguments = listOf(
                navArgument("channelId") { type = NavType.StringType },
            ),
        ) {
            ChannelScreen(
                onOpenComments = { channelId, topicId ->
                    navController.navigate(Screen.GroupChat.createTopicRoute(channelId, topicId))
                },
                onOpenAdmin = { channelId ->
                    navController.navigate(Screen.GroupAdmin.createRoute(channelId))
                },
                onBackClick = { navController.popBackStack() },
            )
        }

        composable(
            route = Screen.GroupAdmin.route,
            arguments = listOf(
                navArgument("groupId") { type = NavType.StringType },
            ),
        ) {
            GroupAdminScreen(
                onBackClick = { navController.popBackStack() },
                onLeftGroup = {
                    navController.popBackStack(Screen.Groups.route, inclusive = false)
                },
            )
        }

        composable(route = Screen.QrScanner.route) {
            val scanContext = LocalContext.current
            QrScannerScreen(
                onBackClick = { navController.popBackStack() },
                onQrScanned = { qrContent ->
                    // Сканер отдаёт весь прочитанный текст, разбор здесь.
                    // InviteLinkParser понимает и p2p://invite/pk_... — именно такой
                    // QR приложение показывает в профиле, раньше он уходил в тост
                    // «Это не ссылка APU». В навигацию отдаём ВЕСЬ текст: в ссылке
                    // есть имя контакта, без него карточка контакта беднее.
                    val isGroupInvite = GroupInviteLinks.parseTarget(qrContent) != null
                    when {
                        isGroupInvite ->
                            navController.navigate(Screen.Groups.createJoinRoute(qrContent)) {
                                popUpTo(Screen.QrScanner.route) { inclusive = true }
                            }

                        InviteLinkParser.parse(qrContent) != null ->
                            navController.navigate(Screen.AddContact.createRoute(qrContent)) {
                                popUpTo(Screen.QrScanner.route) { inclusive = true }
                            }

                        else -> {
                            Toast.makeText(
                                scanContext,
                                "Это не ссылка APU: " + qrContent.take(64),
                                Toast.LENGTH_LONG,
                            ).show()
                            navController.popBackStack()
                        }
                    }
                }
            )
        }
    }
}

/**
 * Куда ведут кнопки нижней панели разделов.
 *
 * Собрано в одном месте: панель обязана вести себя одинаково на всех экранах,
 * а маршруты знает только навигация. `launchSingleTop` не даёт накапливать
 * копии одного раздела при перескоках туда-обратно; чаты - корень, поэтому на
 * них возвращаемся, а не кладём сверху ещё один экран.
 */
private fun mainTabActions(navController: NavHostController) = ApuTabActions(
    onChats = {
        navController.popBackStack(Screen.ChatList.route, inclusive = false)
    },
    onContacts = {
        navController.navigate(Screen.Contacts.route) { launchSingleTop = true }
    },
    onGroups = {
        navController.navigate(Screen.Groups.plain) { launchSingleTop = true }
    },
    onSaved = {
        navController.navigate(Screen.Saved.route) { launchSingleTop = true }
    },
    onSettings = {
        navController.navigate(Screen.Settings.route) { launchSingleTop = true }
    },
)
