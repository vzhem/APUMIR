package com.vladimir.messenger.ui.screens.groups

// =============================================================================
// GROUPADMINSCREEN.KT — административный кабинет группы
// =============================================================================
// Вкладки: Обзор, Участники (с поиском), Заявки, Ссылки (текст + QR),
// Статистика, Разрешения.
// =============================================================================

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import com.vladimir.messenger.ui.components.ChatWallpaper
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import com.vladimir.messenger.ui.components.AvatarPickerDialog
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
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
import com.vladimir.messenger.data.group.TopicSummary
import com.vladimir.messenger.util.AppShare
import com.vladimir.messenger.util.QrCodeGenerator

/**
 * Вкладки админ-кабинета.
 *
 * Обычному участнику показываются только «Обзор» (без полей редактирования) и
 * «Участники»: раньше он видел все вкладки и мог создавать и удалять ссылки.
 */
private enum class AdminTab(val title: String) {
    Overview("Обзор"),
    Admins("Администраторы"),
    Members("Участники"),
    Requests("Заявки"),
    Invites("Ссылки"),
    Stats("Статистика"),
    Permissions("Разрешения"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupAdminScreen(
    onBackClick: () -> Unit,
    onLeftGroup: () -> Unit,
    viewModel: GroupAdminViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val visibleTabs = if (uiState.isAdmin) {
        AdminTab.entries.toList()
    } else {
        listOf(AdminTab.Overview, AdminTab.Members)
    }
    var tab by remember { mutableStateOf(AdminTab.Overview) }
    // Права могли измениться (сняли администратора) - уходим на доступную вкладку.
    LaunchedEffect(visibleTabs) {
        if (tab !in visibleTabs) tab = AdminTab.Overview
    }

    // Подложка на весь экран, в том числе под верхней панелью.
    Box(modifier = Modifier.fillMaxSize()) {
        ChatWallpaper()
        Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent
                ),
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

            ScrollableTabRow(
                selectedTabIndex = visibleTabs.indexOf(tab).coerceAtLeast(0),
                edgePadding = 8.dp,
            ) {
                visibleTabs.forEach { item ->
                    Tab(
                        selected = tab == item,
                        onClick = { tab = item },
                        text = { Text(item.title) },
                    )
                }
            }

            when (tab) {
                AdminTab.Overview -> OverviewTab(
                    groupId = uiState.groupId,
                    title = uiState.group?.title.orEmpty(),
                    about = uiState.group?.about.orEmpty(),
                    isPublic = uiState.group?.isPublic == true,
                    isOwner = uiState.isOwner,
                    canChangeInfo = uiState.canChangeInfo,
                    canChangeVisibility = uiState.isAdmin,
                    onSetAvatar = viewModel::setGroupAvatar,
                    onSave = viewModel::updateProfile,
                    onTogglePublic = viewModel::setPublic,
                    onLeave = { viewModel.leaveGroup(onLeftGroup) },
                    onDeleteGroup = { viewModel.deleteGroup(onLeftGroup) },
                )

                AdminTab.Admins -> AdminsTab(
                    members = uiState.members,
                    onToggleAdmin = viewModel::toggleAdmin,
                    onTogglePermission = viewModel::setAdminPermission,
                )

                AdminTab.Members -> MembersTab(
                    isAdmin = uiState.isAdmin,
                    members = uiState.searchResults,
                    query = uiState.searchQuery,
                    onQueryChange = viewModel::onSearchQueryChanged,
                    onToggleAdmin = viewModel::toggleAdmin,
                    onTogglePermission = viewModel::setAdminPermission,
                    onBlock = viewModel::blockMember,
                    onResync = viewModel::resyncMembers,
                )

                AdminTab.Requests -> RequestsTab(requests = uiState.requests, onDecide = viewModel::decideRequest)

                AdminTab.Invites -> InvitesTab(
                    invites = uiState.invites,
                    groupTitle = uiState.group?.title.orEmpty(),
                    isPublic = uiState.group?.isPublic == true,
                    canManage = uiState.canManageInvites,
                    onCreate = viewModel::createInvite,
                    onRevoke = viewModel::revokeInvite,
                    onDelete = viewModel::deleteInvite,
                )

                AdminTab.Stats -> StatsTab(stats = uiState.stats, topics = uiState.topics)

                AdminTab.Permissions -> PermissionsTab(
                    mask = uiState.memberPermissions,
                    onToggle = viewModel::setMemberPermission,
                )
            }
        }
    }
    }
}

@Composable
private fun OverviewTab(
    groupId: String,
    title: String,
    about: String,
    isPublic: Boolean,
    isOwner: Boolean,
    canChangeInfo: Boolean,
    canChangeVisibility: Boolean,
    onSetAvatar: (android.net.Uri) -> Unit,
    onSave: (String, String) -> Unit,
    onTogglePublic: (Boolean) -> Unit,
    onLeave: () -> Unit,
    onDeleteGroup: () -> Unit,
) {
    // Ключи в remember обязательны: без них черновики запоминали пустые строки
    // с первой композиции, когда группа ещё не загрузилась, и название с
    // описанием появлялись только после переключения вкладок туда-сюда.
    var titleDraft by remember(title) { mutableStateOf(title) }
    var aboutDraft by remember(about) { mutableStateOf(about) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // Раунд 42: аватар группы/канала.
    var showAvatarPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let(onSetAvatar) }
    val groupAvatars by com.vladimir.messenger.ui.theme.AvatarStore.avatars
        .collectAsState()
    val groupAvatarBitmap = remember(groupAvatars["g:$groupId"]) {
        groupAvatars["g:$groupId"]?.let { b64 ->
            try {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                null
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (canChangeInfo) {
            OutlinedTextField(value = titleDraft, onValueChange = { titleDraft = it }, label = { Text("Название") })
            OutlinedTextField(value = aboutDraft, onValueChange = { aboutDraft = it }, label = { Text("Описание") })
            TextButton(onClick = { onSave(titleDraft, aboutDraft) }) { Text("Сохранить") }
        } else {
            // Без права менять информацию показываем только текст: поля и
            // кнопка «Сохранить» обещали правку, которой на самом деле нет -
            // сохранение молча отклонялось.
            Text("Название", style = MaterialTheme.typography.labelLarge)
            Text(title.ifBlank { "—" })
            Text("Описание", style = MaterialTheme.typography.labelLarge)
            Text(about.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Название и описание меняют администраторы.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // Аватар группы/канала: меняют те же, кто название и описание.
        if (canChangeInfo) {
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (groupAvatarBitmap != null) {
                    Image(
                        bitmap = groupAvatarBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(52.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        Icons.Filled.Groups,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Аватар группы", fontWeight = FontWeight.Medium)
                    Text(
                        "Видят участники и Ваши контакты в списке чатов",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { showAvatarPicker = true }) { Text("Сменить") }
            }
        }

        HorizontalDivider()

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Публичная группа", fontWeight = FontWeight.Medium)
                Text(
                    if (isPublic) "Вход по ссылке без одобрения" else "Вход только по одобрению заявки",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (canChangeVisibility) {
                Switch(checked = isPublic, onCheckedChange = onTogglePublic)
            }
        }

        HorizontalDivider()
        TextButton(onClick = { showLeaveConfirm = true }) { Text("Покинуть группу") }

        if (isOwner) {
            Text(
                "Удаление стирает группу, её темы и сообщения у всех участников.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = { showDeleteConfirm = true }) {
                Text("Удалить группу", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text("Покинуть группу?") },
            text = { Text("Вы перестанете получать сообщения этой группы. Вернуться можно только по ссылке-приглашению.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLeaveConfirm = false
                        onLeave()
                    },
                ) { Text("Покинуть") }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirm = false }) { Text("Отмена") }
            },
        )
    }

    if (showDeleteConfirm) {
        DeleteGroupDialog(
            expectedTitle = title,
            onDismiss = { showDeleteConfirm = false },
            onConfirm = {
                showDeleteConfirm = false
                onDeleteGroup()
            },
        )
    }

    if (showAvatarPicker) {
        AvatarPickerDialog(
            context = context,
            onPickUri = { uri ->
                showAvatarPicker = false
                onSetAvatar(android.net.Uri.parse(uri))
            },
            onPickGallery = {
                showAvatarPicker = false
                galleryPicker.launch("image/*")
            },
            onDismiss = { showAvatarPicker = false },
        )
    }
}

/**
 * Защита от случайного удаления: кроме нажатия кнопки нужно ввести название
 * группы. Если названия нет, просим ввести слово УДАЛИТЬ.
 */
@Composable
private fun DeleteGroupDialog(
    expectedTitle: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val expected = expectedTitle.trim().ifBlank { "УДАЛИТЬ" }
    var typed by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Удалить группу?") },
        text = {
            Column {
                Text(
                    "Группа, её темы, сообщения и ссылки-приглашения будут удалены " +
                        "у всех участников. Отменить это нельзя.",
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("Введите: $expected") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = typed.trim() == expected,
                onClick = onConfirm,
            ) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

/** Вкладка «Администраторы»: кто управляет группой и с какими правами. */
@Composable
private fun AdminsTab(
    members: List<MemberSummary>,
    onToggleAdmin: (String, Boolean) -> Unit,
    onTogglePermission: (String, Long, Boolean) -> Unit,
) {
    val admins = members.filter { it.role == GroupRole.OWNER || it.role == GroupRole.ADMIN }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                "Назначить администратора можно во вкладке «Участники».",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (admins.isEmpty()) {
            item { Text("Администраторов пока нет") }
        }
        items(admins, key = { it.nodeId }) { admin ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(admin.displayName.ifBlank { admin.nodeId }, fontWeight = FontWeight.Medium)
                    Text(
                        if (admin.role == GroupRole.OWNER) "Владелец — все права безусловно" else "Администратор",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (admin.role == GroupRole.ADMIN) {
                        // Разрешения занимают пол-экрана, поэтому список сворачивается:
                        // нажал на строку - развернулось, нажал ещё раз - свернулось.
                        var expanded by remember(admin.nodeId) { mutableStateOf(false) }
                        HorizontalDivider()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expanded = !expanded },
                        ) {
                            Text(
                                if (expanded) {
                                    "Разрешения администратора — нажмите, чтобы свернуть"
                                } else {
                                    "Разрешения администратора — нажмите, чтобы развернуть"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (expanded) {
                            GroupPermissions.Admin.entries.forEach { entry ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(entry.title)
                                        Text(entry.hint, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Switch(
                                        checked = GroupPermissions.has(admin.permissions, entry.flag),
                                        onCheckedChange = { onTogglePermission(admin.nodeId, entry.flag, it) },
                                    )
                                }
                            }
                        }
                        if (!admin.isMe) {
                            TextButton(onClick = { onToggleAdmin(admin.nodeId, false) }) {
                                Text("Снять администратора")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MembersTab(
    isAdmin: Boolean,
    members: List<MemberSummary>,
    query: String,
    onQueryChange: (String) -> Unit,
    onToggleAdmin: (String, Boolean) -> Unit,
    onTogglePermission: (String, Long, Boolean) -> Unit,
    onBlock: (String) -> Unit,
    onResync: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // У вступивших раньше, чем появились темы, список тем пустой.
        // Кнопка рассылает карточку группы, темы и состав заново.
        // Обычному участнику не показываем: рассылка - дело администратора.
        if (isAdmin) {
            TextButton(onClick = onResync) {
                Text("Разослать темы и состав заново")
            }
        }
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
                    isAdmin = isAdmin,
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
    isAdmin: Boolean,
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
                // Управление участниками видно только администраторам:
                // обычный участник не должен видеть чужие «Права» и «Исключить».
                if (isAdmin && !member.isMe && member.role != GroupRole.OWNER) {
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
    groupTitle: String,
    isPublic: Boolean,
    canManage: Boolean,
    onCreate: (Boolean) -> Unit,
    onRevoke: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            if (canManage) {
                // Одна ссылка и один понятный переключатель.
                //
                // Две кнопки («с одобрением» и «без одобрения») читались
                // двояко: «с одобрением» можно понять и как «уже одобрено,
                // заходи». Из-за этого создавалась ссылка не того типа, и
                // владелец либо получал лишнюю заявку, либо не получал её вовсе.
                var needsApproval by remember { mutableStateOf(!isPublic) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Вход только после одобрения", fontWeight = FontWeight.Medium)
                        Text(
                            if (needsApproval) {
                                "По ссылке человек оставляет заявку владельцу"
                            } else {
                                "По ссылке человек входит сразу"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = needsApproval, onCheckedChange = { needsApproval = it })
                }
                TextButton(onClick = { onCreate(needsApproval) }) { Text("Создать ссылку") }
            } else {
                Text(
                    "Ссылки создают и отзывают администраторы. Попросите ссылку у них " +
                        "или воспользуйтесь QR-кодом группы.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!isPublic) {
                Text(
                    "Группа частная: даже по ссылке участник попадёт в заявки.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        items(invites, key = { it.slug }) { invite ->
            InviteCard(
                invite = invite,
                groupTitle = groupTitle,
                canManage = canManage,
                onRevoke = { onRevoke(invite.slug) },
                onDelete = { onDelete(invite.slug) },
            )
        }
    }
}

/**
 * Ссылка-приглашение и QR-код того же текста рядом с ней.
 * Ссылку можно выделить пальцем, скопировать одной кнопкой и отдать в любое
 * приложение системным меню «Поделиться».
 */
@Composable
private fun InviteCard(
    invite: InviteSummary,
    groupTitle: String,
    canManage: Boolean,
    onRevoke: () -> Unit,
    onDelete: () -> Unit,
) {
    val bitmap = remember(invite.link) { QrCodeGenerator.generateQrCode(invite.link, 320) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row {
                Column(modifier = Modifier.weight(1f)) {
                    // SelectionContainer делает текст выделяемым: без него
                    // ссылку нельзя было ни отметить, ни скопировать.
                    SelectionContainer {
                        Text(invite.link, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        buildString {
                            append(if (invite.requestApproval) "по заявке" else "вход сразу")
                            append(" • использований: ").append(invite.useCount)
                            if (invite.maxUses > 0) append("/").append(invite.maxUses)
                            if (invite.revoked) append(" • отозвана")
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
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
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(invite.link)) }) {
                    Text("Копировать")
                }
                TextButton(onClick = { AppShare.shareGroupInvite(context, groupTitle, invite.link) }) {
                    Text("Поделиться")
                }
                // Отозвать и удалить ссылку может только администратор:
                // участник без права приглашать этих кнопок не видит.
                if (canManage) {
                    if (invite.revoked) {
                        TextButton(onClick = onDelete) { Text("Удалить") }
                    } else {
                        TextButton(onClick = onRevoke) { Text("Отозвать") }
                    }
                }
            }
        }
    }
}


@Composable
private fun StatsTab(stats: GroupStats?, topics: List<TopicSummary>) {
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
        // Идентификатор темы человеку ничего не говорит — показываем её имя.
        stats.perTopic.forEach { (topicId, count) ->
            val name = topics.firstOrNull { it.id == topicId }
                ?.name
                ?.takeIf { it.isNotBlank() }
                ?: if (topicId.isBlank()) "Без темы" else topicId.take(8)
            StatRow(name, count.toString())
        }
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
