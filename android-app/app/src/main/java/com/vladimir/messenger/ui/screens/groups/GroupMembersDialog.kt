package com.vladimir.messenger.ui.screens.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.data.group.GroupRole
import com.vladimir.messenger.data.group.MemberSummary
import com.vladimir.messenger.ui.components.ApuSearchField
import com.vladimir.messenger.ui.components.ApuSettingsDialog
import com.vladimir.messenger.ui.components.ApuTextAction
import com.vladimir.messenger.ui.components.PeerAvatar
import com.vladimir.messenger.ui.theme.AvatarStore

@Composable
internal fun GroupMembersDialog(members: List<MemberSummary>, canView: Boolean, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val avatars by AvatarStore.avatars.collectAsState()
    ApuSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text("Участники группы") },
        text = {
            if (!canView) {
                Text("Просмотр списка участников закрыт администратором группы.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ApuSearchField(value = query, onValueChange = { query = it }, placeholder = "Поиск участников")
                    val shown = members.filter { !it.isBanned && it.displayName.contains(query.trim(), ignoreCase = true) }
                    if (shown.isEmpty()) Text(if (query.isBlank()) "Список участников пока пуст" else "Никого не найдено")
                    LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(shown, key = { it.nodeId }) { member ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PeerAvatar(name = member.displayName.ifBlank { member.nodeId.take(8) },
                                    avatarB64 = avatars[member.nodeId], size = 40.dp)
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(member.displayName.ifBlank { member.nodeId.take(8) })
                                    if (member.isMe) Text("Вы")
                                    when (member.role) {
                                        GroupRole.OWNER -> Text("Владелец")
                                        GroupRole.ADMIN -> Text("Администратор")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { ApuTextAction(label = "Закрыть", onClick = onDismiss) },
    )
}
