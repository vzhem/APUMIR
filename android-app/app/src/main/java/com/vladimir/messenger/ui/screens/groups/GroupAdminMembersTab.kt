package com.vladimir.messenger.ui.screens.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.R
import com.vladimir.messenger.data.group.GroupPermissions
import com.vladimir.messenger.data.group.GroupRole
import com.vladimir.messenger.data.group.MemberSummary
import com.vladimir.messenger.data.textcolor.TextColorPalette
import com.vladimir.messenger.data.textcolor.TextColorSettingsStore
import com.vladimir.messenger.ui.components.ApuBubble
import com.vladimir.messenger.ui.components.ApuBubbleMutedColor
import com.vladimir.messenger.ui.components.ApuPremiumButton
import com.vladimir.messenger.ui.components.ApuPremiumSwitch
import com.vladimir.messenger.ui.components.ApuSearchField
import com.vladimir.messenger.ui.components.ApuSettingsDivider
import com.vladimir.messenger.ui.components.ApuVipBadge
import com.vladimir.messenger.ui.components.DiagnosticsActionStyle
import com.vladimir.messenger.ui.components.PeerAvatar
import com.vladimir.messenger.ui.components.apuVipNameStyle

/**
 * Вкладка «Участники» админки группы — вынесена из [GroupAdminScreen]
 * отдельным файлом по шву (правило god-файлов: новый код пишем рядом, а не
 * внутрь уже большого экрана).
 *
 * Здесь же живёт кнопка «Разослать темы и состав заново» и подпись под ней:
 * сама досылка теперь автоматическая ([com.vladimir.messenger.data.group.GroupInfoResync]),
 * а ручная осталась как «сделать сейчас».
 */
@Composable
internal fun MembersTab(
    isAdmin: Boolean,
    members: List<MemberSummary>,
    /** Узлы-элита: кольцо и знак VIP у участников. */
    vipNodeIds: Set<String>,
    query: String,
    onQueryChange: (String) -> Unit,
    onToggleAdmin: (String, Boolean) -> Unit,
    onTogglePermission: (String, Long, Boolean) -> Unit,
    onBlock: (String) -> Unit,
    onResync: () -> Unit,
    /**
     * Подпись «последняя рассылка …»: досылка «темы и состав» идёт сама, и
     * администратор видит, что она уже была, а не гадает, нужно ли жать кнопку.
     * null — ещё не рассылали.
     */
    lastResyncLabel: String? = null,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Досылка «карточка группы, темы и состав» идёт сама (GroupInfoResync):
        // адресно по просьбе участника и тихими проходами не чаще раза в
        // несколько часов. Кнопка осталась для «надо прямо сейчас», а подпись
        // под ней показывает, что автоматика уже сделала. Обычному участнику
        // этот блок не показываем: рассылка - дело администратора.
        if (isAdmin) {
            ApuBubble(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                ApuPremiumButton(label = stringResource(R.string.admin_resync), onClick = onResync, style = DiagnosticsActionStyle.GLASS)
                if (lastResyncLabel != null) {
                    Text(
                        lastResyncLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = ApuBubbleMutedColor,
                    )
                }
            }
        }
        ApuSearchField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = stringResource(R.string.admin_search_placeholder),
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(members, key = { it.nodeId }) { member ->
                MemberRow(
                    isAdmin = isAdmin,
                    member = member,
                    vip = member.nodeId.lowercase() in vipNodeIds,
                    onToggleAdmin = { onToggleAdmin(member.nodeId, member.role != GroupRole.ADMIN) },
                    onTogglePermission = { flag, enabled -> onTogglePermission(member.nodeId, flag, enabled) },
                    onBlock = { onBlock(member.nodeId) },
                )
                ApuSettingsDivider(startPadding = 16.dp)
            }
        }
    }
}

@Composable
internal fun MemberRow(
    isAdmin: Boolean,
    member: MemberSummary,
    /** Участник из элиты: кольцо вокруг аватарки и знак VIP у имени. */
    vip: Boolean = false,
    onToggleAdmin: () -> Unit,
    onTogglePermission: (Long, Boolean) -> Unit,
    onBlock: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val textColorRevision by TextColorSettingsStore.revision.collectAsState()
    val textColorContext = LocalContext.current
    LaunchedEffect(Unit) { TextColorSettingsStore.initialize(textColorContext) }
    val defaultMemberNameColor = if (vip) Color(0xFF8A5F00) else MaterialTheme.colorScheme.onSurface
    val memberNameColor = remember(member.nodeId, textColorRevision, defaultMemberNameColor) {
        TextColorPalette.colorOr(
            TextColorSettingsStore.peerNameColor(textColorContext, member.nodeId),
            defaultMemberNameColor,
        )
    }

    ApuBubble(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Аватарка участника: у элиты — в объёмном золотом кольце.
                val memberAvatars by com.vladimir.messenger.ui.theme.AvatarStore.avatars
                    .collectAsState()
                PeerAvatar(
                    name = member.displayName.ifBlank { member.nodeId },
                    avatarB64 = memberAvatars[member.nodeId],
                    vip = vip,
                    size = 40.dp, peerId = member.nodeId,
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            member.displayName.ifBlank { member.nodeId },
                            style = com.vladimir.messenger.ui.components.apuVipNameStyle(
                                MaterialTheme.typography.titleMedium, vip,
                            ),
                            fontWeight = FontWeight.Medium,
                            color = memberNameColor,
                        )
                        if (vip) {
                            Spacer(Modifier.width(6.dp))
                            ApuVipBadge(compact = true)
                        }
                    }
                    Text(
                        when (member.role) {
                            GroupRole.OWNER -> stringResource(R.string.admin_role_owner)
                            GroupRole.ADMIN -> stringResource(R.string.admin_role_admin)
                            else -> stringResource(R.string.admin_role_member)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = ApuBubbleMutedColor,
                    )
                }
            }
            // Управление участниками видно только администраторам:
            // обычный участник не должен видеть чужие «Права» и «Исключить».
            if (isAdmin && !member.isMe && member.role != GroupRole.OWNER) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    ApuPremiumButton(
                        label = stringResource(R.string.admin_perms),
                        onClick = { expanded = !expanded },
                        style = DiagnosticsActionStyle.GLASS,
                        modifier = Modifier.weight(1f),
                    )
                    ApuPremiumButton(
                        label = stringResource(R.string.admin_exclude),
                        onClick = onBlock,
                        style = DiagnosticsActionStyle.DANGER,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (expanded && member.role == GroupRole.ADMIN) {
                ApuSettingsDivider(startPadding = 16.dp)
                Text(stringResource(R.string.admin_perms_title), style = MaterialTheme.typography.labelLarge)
                GroupPermissions.Admin.entries.forEach { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.title)
                            Text(
                                entry.hint,
                                style = MaterialTheme.typography.bodySmall,
                                color = ApuBubbleMutedColor,
                            )
                        }
                        ApuPremiumSwitch(
                            checked = GroupPermissions.has(member.permissions, entry.flag),
                            onCheckedChange = { onTogglePermission(entry.flag, it) },
                        )
                    }
                }
            } else if (expanded) {
                ApuSettingsDivider(startPadding = 16.dp)
                ApuPremiumButton(
                    label = if (member.role == GroupRole.ADMIN) stringResource(R.string.admin_remove_admin) else stringResource(R.string.admin_make_admin),
                    onClick = onToggleAdmin,
                    style = DiagnosticsActionStyle.GLASS,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

