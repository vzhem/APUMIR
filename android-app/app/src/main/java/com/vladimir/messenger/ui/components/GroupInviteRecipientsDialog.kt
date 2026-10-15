package com.vladimir.messenger.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.R
import com.vladimir.messenger.data.group.GroupSummary
import com.vladimir.messenger.domain.model.Chat
import com.vladimir.messenger.util.GroupInviteCardSender

/**
 * Выбор личных чатов APU для приглашения в группу или подписки на канал.
 * Используется и из списка сообществ, и непосредственно из группового чата.
 */
@Composable
fun GroupInviteRecipientsDialog(
    group: GroupSummary,
    loadPersonalChats: ((List<Chat>) -> Unit) -> Unit,
    loadMemberIds: (String, (Set<String>) -> Unit) -> Unit,
    sendInvites: (GroupSummary, List<String>, (Int, Int) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var chats by remember(group.id) { mutableStateOf<List<Chat>?>(null) }
    var memberIds by remember(group.id) { mutableStateOf(emptySet<String>()) }
    var selected by remember(group.id) { mutableStateOf(emptySet<String>()) }
    var query by remember(group.id) { mutableStateOf("") }
    var sending by remember(group.id) { mutableStateOf(false) }
    val maxRecipients = GroupInviteCardSender.MAX_RECIPIENTS

    LaunchedEffect(group.id) {
        loadPersonalChats { chats = it }
        loadMemberIds(group.id) { memberIds = it }
    }

    ApuPremiumDialog(
        title = stringResource(R.string.chat_send_to),
        onDismiss = { if (!sending) onDismiss() },
        confirmLabel = if (selected.isNotEmpty()) {
            if (sending) stringResource(R.string.chat_sending)
            else stringResource(R.string.action_send)
        } else {
            null
        },
        confirmEnabled = !sending,
        onConfirm = {
            if (selected.isNotEmpty() && !sending) {
                sending = true
                sendInvites(group, selected.toList()) { sent, failed ->
                    sending = false
                    val message = if (failed == 0) {
                        context.getString(R.string.sent_count, sent)
                    } else {
                        context.getString(R.string.sent_failed, sent, failed)
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            }
        },
        dismissEnabled = !sending,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.group_invite_recipients_hint, group.title),
                style = MaterialTheme.typography.bodySmall,
                color = ApuBubbleMutedColor,
            )
            when {
                chats == null -> Text(stringResource(R.string.action_loading))
                chats.isNullOrEmpty() -> Text(stringResource(R.string.chat_no_personal))
                else -> {
                    ApuSearchField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = stringResource(R.string.contacts_search_placeholder),
                    )
                    Text(
                        stringResource(R.string.selected_of, selected.size, maxRecipients),
                        style = MaterialTheme.typography.labelSmall,
                        color = ApuBubbleMutedColor,
                    )
                    val visibleChats = chats.orEmpty().filter { chat ->
                        query.isBlank() || chat.contactName.contains(query, ignoreCase = true)
                    }
                    if (visibleChats.isEmpty()) {
                        Text(stringResource(R.string.contacts_nothing_found, query))
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(visibleChats, key = { it.id }) { chat ->
                                val alreadyMember = chat.contactId.isNotBlank() &&
                                    chat.contactId in memberIds
                                val isSelected = chat.id in selected
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary
                                            else Color.White.copy(alpha = 0.85f),
                                            RoundedCornerShape(18.dp),
                                        )
                                        .border(
                                            1.dp,
                                            if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                                            RoundedCornerShape(18.dp),
                                        )
                                        .clickable(enabled = !alreadyMember && !sending) {
                                            selected = if (isSelected) {
                                                selected - chat.id
                                            } else if (selected.size < maxRecipients) {
                                                selected + chat.id
                                            } else {
                                                selected
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val checkTint = when {
                                        alreadyMember -> Color(0xFFE7EAF0)
                                        isSelected -> Color.White
                                        else -> Color.Transparent
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(
                                                when {
                                                    alreadyMember -> Color(0xFF9AA3AF)
                                                    isSelected -> MaterialTheme.colorScheme.primary
                                                    else -> Color.Transparent
                                                },
                                                CircleShape,
                                            )
                                            .border(
                                                1.5.dp,
                                                if (alreadyMember) Color(0xFF9AA3AF)
                                                else MaterialTheme.colorScheme.primary,
                                                CircleShape,
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (isSelected || alreadyMember) {
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = checkTint,
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        chat.contactName.ifBlank { stringResource(R.string.chat_no_name) },
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = when {
                                            isSelected -> Color.White
                                            alreadyMember -> Color(0xFF9AA3AF)
                                            else -> Color.Unspecified
                                        },
                                    )
                                    if (alreadyMember) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            if (group.isChannel) stringResource(R.string.chat_already_channel)
                                            else stringResource(R.string.chat_already_group),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF9AA3AF),
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
