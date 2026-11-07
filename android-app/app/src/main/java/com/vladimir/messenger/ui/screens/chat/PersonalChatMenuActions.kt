package com.vladimir.messenger.ui.screens.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.R
import com.vladimir.messenger.domain.model.Chat
import com.vladimir.messenger.ui.components.BubbleMenuAction

/**
 * Действия меню «⋮» личного чата главного списка.
 *
 * Отдельный файл — список экранов большой; шов держит отдельно формирование
 * подписей/иконок и передачу действий, не меняя поведение самих пунктов.
 */
@Composable
internal fun personalChatMenuActions(
    chat: Chat,
    onOpenChat: (chatId: String, contactName: String, contactId: String) -> Unit,
    onCall: (contactId: String, contactName: String) -> Unit,
    onVideoCall: (contactId: String, contactName: String) -> Unit,
    onShareContact: (Chat) -> Unit,
    onTogglePin: (Chat) -> Unit,
    onToggleArchive: (Chat) -> Unit,
    onToggleMute: (Chat) -> Unit,
    onMarkRead: (chatId: String) -> Unit,
    onClear: (Chat) -> Unit,
    onDelete: (Chat) -> Unit,
): List<BubbleMenuAction> = listOf(
    BubbleMenuAction(
        title = if (chat.isPinned) stringResource(R.string.menu_unpin) else stringResource(R.string.menu_pin),
        icon = Icons.Filled.PushPin,
        onClick = { onTogglePin(chat) },
    ),
    BubbleMenuAction(
        title = stringResource(R.string.menu_open_chat),
        icon = Icons.Default.Forum,
        onClick = { onOpenChat(chat.id, chat.contactName, chat.contactId) },
    ),
    BubbleMenuAction(
        title = stringResource(R.string.menu_call),
        icon = Icons.Default.Call,
        onClick = { onCall(chat.contactId, chat.contactName) },
    ),
    BubbleMenuAction(
        title = stringResource(R.string.menu_video_call),
        icon = Icons.Default.Videocam,
        onClick = { onVideoCall(chat.contactId, chat.contactName) },
    ),
    BubbleMenuAction(
        title = stringResource(R.string.menu_share_contact),
        icon = Icons.Default.Share,
        onClick = { onShareContact(chat) },
    ),
    // Архив и беззвучный режим переключаются; подпись показывает следующий шаг.
    BubbleMenuAction(
        title = if (chat.isArchived) stringResource(R.string.menu_unarchive) else stringResource(R.string.menu_archive),
        icon = Icons.Default.Archive,
        onClick = { onToggleArchive(chat) },
    ),
    BubbleMenuAction(
        title = if (chat.isMuted()) stringResource(R.string.menu_unmute) else stringResource(R.string.menu_mute),
        icon = if (chat.isMuted()) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff,
        onClick = { onToggleMute(chat) },
    ),
    BubbleMenuAction(
        title = stringResource(R.string.menu_mark_read),
        icon = Icons.Default.DoneAll,
        onClick = { onMarkRead(chat.id) },
    ),
    BubbleMenuAction(
        title = stringResource(R.string.menu_clear_chat),
        icon = Icons.Default.CleaningServices,
        onClick = { onClear(chat) },
    ),
    BubbleMenuAction(
        title = stringResource(R.string.menu_delete_chat),
        icon = Icons.Default.Delete,
        destructive = true,
        onClick = { onDelete(chat) },
    ),
)
