package com.vladimir.messenger.ui.screens.groups

// =============================================================================
// GROUPADMINSCREEN.KT — административный кабинет группы
// =============================================================================
// Вкладки: Обзор, Участники (с поиском), Заявки, Ссылки (текст + QR),
// Статистика, Разрешения.
// =============================================================================

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.data.group.GroupPermissions
import com.vladimir.messenger.data.group.GroupRole
import com.vladimir.messenger.data.group.GroupStats
import com.vladimir.messenger.data.group.InviteSummary
import com.vladimir.messenger.data.group.JoinRequestSummary
import com.vladimir.messenger.data.group.MemberSummary
import com.vladimir.messenger.util.QrCodeGenerator

private val TABS = listOf("Обзор", "Участники", "Заявки", "Ссылки", "Статистика", "Разрешения")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupAdminScreen(
    onBackClick: () -> Unit,
    onLeftGroup: () -> Unit,
    viewModel: GroupAdminViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.group?.title ?: "Группа") },
                navigationIcon = { TextButton(onClick = onBackClick) { Text("Назад") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

            uiState.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
            }
            uiState.notice?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(12.dp))
            }

            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                TABS.forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }

            when (tab) {
                0 -> OverviewTab(
                    title = uiState.group?.title.orEmpty(),
                    about = uiState.group?.about.orEmpty(),
                    isPublic = uiState.group?.isPublic == true,
                    onSave = viewModel::updateProfile,
                    onTogglePublic = viewModel::setPublic,
                    onLeave = { viewModel.leaveGroup(onLeftGroup) },
                )

                1 -> MembersTab(
                    members = uiState.searchResults,
                    query = uiState.searchQuery,
                    onQueryChange = viewModel::onSearchQueryChanged,
                    onToggleAdmin = viewModel::toggleAdmin,
                    onTogglePermission = viewModel::setAdminPermission,
                    onBlock = viewModel::blockMember,
                )

                2 -> RequestsTab(requests = uiState.requests, onDecide = viewModel::decideRequest)

                3 -> InvitesTab(
                    invites = uiState.invites,
                    isPublic = uiState.group?.isPublic == true,
                    onCreate = viewModel::createInvite,
                    onRevoke = viewModel::revokeInvite,
                )

                4 -> StatsTab(stats = uiState.stats)

                5 -> PermissionsTab(
                    mask = uiState.memberPermissions,
                    onToggle = viewModel::setMemberPermission,
                )
            }
        }
    }
}

@Composable
private fun OverviewTab(
    title: String,
    about: String,
    isPublic: Boolean,
    onSave: (String, String) -> Unit,
    onTogglePublic: (Boolean) -> Unit,
    onLeave: () -> Unit,
) {
    var titleDraft by remember { mutableStateOf(title) }
    var aboutDraft by remember { mutableStateOf(about) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(value = titleDraft, onValueChange = { titleDraft = it }, label = { Text("Название") })
        OutlinedTextField(value = aboutDraft, onValueChange = { aboutDraft = it }, label = { Text("Описание") })
        TextButton(onClick = { onSave(titleDraft, aboutDraft) }) { Text("Сохранить") }

        HorizontalDivider()

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Публичная группа", fontWeight = FontWeight.Medium)
                Text(
                    if (isPublic) "Вход по ссылке без одобрения" else "Вход только по одобрению заявки",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = isPublic, onCheckedChange = onTogglePublic)
        }

        HorizontalDivider()
        TextButton(onClick = onLeave) { Text("Покинуть группу") }
    }
}

@Composable
private fun MembersTab(
    members: List<MemberSummary>,
    query: String,
    onQueryChange: (String) -> Unit,
    onToggleAdmin: (String, Boolean) -> Unit,
    onTogglePermission: (String, Long, Boolean) -> Unit,
    onBlock: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            placeholder = { Text("Поиск участника по имени или узлу") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(members, key = { it.nodeId }) { member ->
                MemberRow(
                    member = member,
                    onToggleAdmin = { onToggleAdmin(member.nodeId, member.role != GroupRole.ADMIN) },
                    onTogglePermission = { flag, enabled -> onTogglePermission(member.nodeId, flag, enabled) },
                    onBlock = { onBlock(member.nodeId) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun MemberRow(
    member: MemberSummary,
    onToggleAdmin: () -> Unit,
    onTogglePermission: (Long, Boolean) -> Unit,
    onBlock: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(member.displayName.ifBlank { member.nodeId }, fontWeight = FontWeight.Medium)
                    Text(
                        when (member.role) {
                            GroupRole.OWNER -> "Владелец"
                            GroupRole.ADMIN -> "Администратор"
                            else -> "Участник"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (!member.isMe && member.role != GroupRole.OWNER) {
                    TextButton(onClick = { expanded = !expanded }) { Text("Права") }
                    TextButton(onClick = onBlock) { Text("Исключить") }
                }
            }

            if (expanded && member.role == GroupRole.ADMIN) {
                HorizontalDivider()
                Text("Разрешения администратора", style = MaterialTheme.typography.labelLarge)
                GroupPermissions.Admin.entries.forEach { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.title)
                            Text(entry.hint, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = GroupPermissions.has(member.permissions, entry.flag),
                            onCheckedChange = { onTogglePermission(entry.flag, it) },
                        )
                    }
                }
            } else if (expanded) {
                HorizontalDivider()
                TextButton(onClick = onToggleAdmin) {
                    Text(if (member.role == GroupRole.ADMIN) "Снять администратора" else "Назначить администратором")
                }
            }
        }
    }
}

@Composable
private fun RequestsTab(requests: List<JoinRequestSummary>, onDecide: (String, Boolean) -> Unit) {
    if (requests.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Новых заявок нет")
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(requests, key = { it.nodeId }) { request ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(request.displayName.ifBlank { request.nodeId }, fontWeight = FontWeight.Medium)
                    if (request.note.isNotBlank()) {
                        Text(request.note, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onDecide(request.nodeId, true) }) { Text("Одобрить") }
                        TextButton(onClick = { onDecide(request.nodeId, false) }) { Text("Отклонить") }
                    }
                }
            }
        }
    }
}

@Composable
private fun InvitesTab(
    invites: List<InviteSummary>,
    isPublic: Boolean,
    onCreate: (Boolean) -> Unit,
    onRevoke: (String) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onCreate(false) }) { Text("Ссылка с одобрением") }
                TextButton(onClick = { onCreate(true) }) { Text("Ссылка без одобрения") }
            }
            if (!isPublic) {
                Text(
                    "Группа частная: даже по ссылке участник попадёт в заявки.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        items(invites, key = { it.slug }) { invite ->
            InviteCard(invite = invite, onRevoke = { onRevoke(invite.slug) })
        }
    }
}

/** Ссылка-приглашение и QR-код того же текста рядом с ней. */
@Composable
private fun InviteCard(invite: InviteSummary, onRevoke: () -> Unit) {
    val bitmap = remember(invite.link) { QrCodeGenerator.generateQrCode(invite.link, 320) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(invite.link, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append(if (invite.requestApproval) "с одобрением" else "без одобрения")
                        append(" • использований: ").append(invite.useCount)
                        if (invite.maxUses > 0) append("/").append(invite.maxUses)
                        if (invite.revoked) append(" • отозвана")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!invite.revoked) {
                    TextButton(onClick = onRevoke) { Text("Отозвать") }
                }
            }
            Spacer(Modifier.width(12.dp))
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "QR-код приглашения",
                    modifier = Modifier.size(120.dp),
                )
            } else {
                Text("QR недоступен", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StatsTab(stats: GroupStats?) {
    if (stats == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Статистика доступна администраторам")
        }
        return
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatRow("Участников", stats.memberCount.toString())
        StatRow("Администраторов", stats.adminCount.toString())
        StatRow("Тем", stats.topicCount.toString())
        StatRow("Заявок в ожидании", stats.pendingRequests.toString())
        StatRow("Сообщений всего", stats.totalMessages.toString())

        HorizontalDivider()
        Text("Сообщения за 7 дней", fontWeight = FontWeight.Medium)
        if (stats.last7Days.isEmpty()) {
            Text("Пока нет данных", style = MaterialTheme.typography.bodySmall)
        } else {
            stats.last7Days.forEach { day ->
                StatRow(day.dayKey, "${day.messageCount} сообщ., ${day.senderCount} отправ.")
            }
        }

        HorizontalDivider()
        Text("Сообщения по темам", fontWeight = FontWeight.Medium)
        stats.perTopic.forEach { (topicId, count) -> StatRow(topicId.take(8), count.toString()) }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PermissionsTab(mask: Long, onToggle: (Long, Boolean) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
    ) {
        Text("Разрешения для участников", fontWeight = FontWeight.Medium)
        Text(
            "Применяются ко всем обычным участникам. Администраторы и владелец не ограничиваются.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        GroupPermissions.Member.entries.forEach { entry ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.title)
                    Text(entry.hint, style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = GroupPermissions.has(mask, entry.flag),
                    onCheckedChange = { onToggle(entry.flag, it) },
                )
            }
            HorizontalDivider()
        }
    }
}
