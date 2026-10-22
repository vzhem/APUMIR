package com.vladimir.messenger.ui.screens.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import com.vladimir.messenger.data.textcolor.TextColorPalette
import com.vladimir.messenger.data.textcolor.TextColorSettingsStore
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.R
import com.vladimir.messenger.data.group.GroupRole
import com.vladimir.messenger.data.group.MemberSummary
import com.vladimir.messenger.ui.components.ApuBubbleMutedColor
import com.vladimir.messenger.ui.components.ApuPremiumContentButton
import com.vladimir.messenger.ui.components.ApuSearchField
import com.vladimir.messenger.ui.components.ApuSettingsDialog
import com.vladimir.messenger.ui.components.ApuTextAction
import com.vladimir.messenger.ui.components.DiagnosticsActionStyle
import com.vladimir.messenger.ui.components.PeerAvatar
import com.vladimir.messenger.ui.theme.AvatarStore

@Composable
internal fun GroupMembersDialog(
    members: List<MemberSummary>,
    canView: Boolean,
    onDismiss: () -> Unit,
    onInvite: () -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    val avatars by AvatarStore.avatars.collectAsState()
    val colorContext = LocalContext.current
    val colorRevision by TextColorSettingsStore.revision.collectAsState()
    ApuSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_members_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ApuPremiumContentButton(
                    onClick = onInvite,
                    modifier = Modifier.fillMaxWidth(),
                    style = DiagnosticsActionStyle.PRIMARY,
                ) {
                    Icon(Icons.Filled.PersonAdd, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.contacts_invite_friend),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (!canView) {
                    Text(stringResource(R.string.group_members_hidden))
                } else {
                    ApuSearchField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = stringResource(R.string.group_members_search),
                    )
                    val shown = members.filter {
                        !it.isBanned && it.displayName.contains(query.trim(), ignoreCase = true)
                    }
                    if (shown.isEmpty()) {
                        Text(
                            if (query.isBlank()) stringResource(R.string.group_members_empty)
                            else stringResource(R.string.group_members_not_found),
                        )
                    }
                    LazyColumn(
                        Modifier.heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(shown, key = { it.nodeId }) { member ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PeerAvatar(
                                    name = member.displayName.ifBlank { member.nodeId.take(8) },
                                    avatarB64 = avatars[member.nodeId],
                                    size = 40.dp,
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    val memberNameColor = remember(member.nodeId, colorRevision) {
                                        TextColorPalette.colorOr(
                                            TextColorSettingsStore.peerNameColor(colorContext, member.nodeId),
                                            MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                    Text(
                                        member.displayName.ifBlank { member.nodeId.take(8) },
                                        color = memberNameColor,
                                    )
                                    if (member.isMe) Text(stringResource(R.string.call_captions_you))
                                    when (member.role) {
                                        GroupRole.OWNER -> Text(stringResource(R.string.group_role_owner))
                                        GroupRole.ADMIN -> Text(stringResource(R.string.group_role_admin))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            ApuTextAction(
                label = stringResource(R.string.action_close),
                onClick = onDismiss,
            )
        },
    )
}

/** First step after «Пригласить друга»: choose an in-APU or external share. */
@Composable
internal fun GroupInviteMethodDialog(
    onDismiss: () -> Unit,
    onInApu: () -> Unit,
    onExternal: () -> Unit,
) {
    ApuSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contacts_invite_friend)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(R.string.group_invite_choose_method),
                    color = ApuBubbleMutedColor,
                )
                ApuPremiumContentButton(
                    onClick = onInApu,
                    modifier = Modifier.fillMaxWidth(),
                    style = DiagnosticsActionStyle.PRIMARY,
                ) {
                    Icon(Icons.Filled.Send, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.contacts_send_in_apu),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(R.string.invite_send_apu_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                ApuPremiumContentButton(
                    onClick = onExternal,
                    modifier = Modifier.fillMaxWidth(),
                    style = DiagnosticsActionStyle.GLASS,
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.contacts_other_app),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(R.string.group_invite_external_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            ApuTextAction(
                label = stringResource(R.string.action_cancel),
                onClick = onDismiss,
            )
        },
    )
}
