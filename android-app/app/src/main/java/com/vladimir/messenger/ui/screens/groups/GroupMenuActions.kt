package com.vladimir.messenger.ui.screens.groups

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Settings
import com.vladimir.messenger.R
import com.vladimir.messenger.data.group.GroupRole
import com.vladimir.messenger.data.group.GroupSummary
import com.vladimir.messenger.ui.components.BubbleMenuAction

/**
 * Пункты меню «⋮» в пузыре группы или канала.
 *
 * Набор тот же, что на главном экране: списки обязаны вести себя одинаково,
 * иначе владелец ищет привычное действие и не находит.
 *
 * Отдельный файл — шов по правилу god-файлов (`GroupsScreen.kt` на потолке
 * строк) и по образцу `chat/PersonalChatMenuActions.kt`: меню теперь
 * открывается и кнопкой, и удержанием строки, поэтому живёт рядом со строкой,
 * но не внутри экрана.
 */
internal fun groupMenuActions(
    context: Context,
    group: GroupSummary,
    onOpen: () -> Unit,
    onAdmin: () -> Unit,
    onInvite: () -> Unit,
    onMute: () -> Unit,
    onMarkRead: () -> Unit,
    onLeaveOrDelete: () -> Unit,
): List<BubbleMenuAction> = buildList {
    add(
        BubbleMenuAction(
            title = if (group.isChannel) context.getString(R.string.menu_open_channel) else context.getString(R.string.menu_open_group),
            icon = Icons.Filled.Forum,
            onClick = onOpen,
        )
    )
    add(
        BubbleMenuAction(
            title = if (group.isChannel) context.getString(R.string.menu_invite_channel) else context.getString(R.string.menu_invite_group),
            icon = Icons.Filled.PersonAdd,
            onClick = onInvite,
        )
    )
    val muted = group.mutedUntilMs > System.currentTimeMillis()
    add(
        BubbleMenuAction(
            title = if (muted) context.getString(R.string.menu_unmute) else context.getString(R.string.menu_mute),
            icon = if (muted) Icons.Filled.NotificationsActive else Icons.Filled.NotificationsOff,
            onClick = onMute,
        )
    )
    if (GroupRole.isAdminOrOwner(group.myRole)) {
        add(
            BubbleMenuAction(
                title = context.getString(R.string.menu_manage),
                icon = Icons.Filled.Settings,
                onClick = onAdmin,
            )
        )
    }
    add(
        BubbleMenuAction(
            title = context.getString(R.string.menu_mark_read),
            icon = Icons.Filled.DoneAll,
            onClick = onMarkRead,
        )
    )
    add(
        BubbleMenuAction(
            title = when {
                group.myRole == GroupRole.OWNER ->
                    if (group.isChannel) context.getString(R.string.menu_delete_channel) else context.getString(R.string.menu_delete_group)
                group.isChannel -> context.getString(R.string.menu_unsubscribe)
                else -> context.getString(R.string.action_logout)
            },
            icon = Icons.Filled.Delete,
            destructive = true,
            onClick = onLeaveOrDelete,
        )
    )
}
