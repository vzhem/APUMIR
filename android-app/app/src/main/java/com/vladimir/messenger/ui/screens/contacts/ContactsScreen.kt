package com.vladimir.messenger.ui.screens.contacts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import android.content.Intent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.vladimir.messenger.util.AppShare
import com.vladimir.messenger.util.ContactShareLink
import com.vladimir.messenger.util.OwnInvite
import androidx.compose.ui.Alignment
import com.vladimir.messenger.ui.components.ChatWallpaper
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vladimir.messenger.domain.model.Contact
import com.vladimir.messenger.domain.model.Chat
import com.vladimir.messenger.ui.components.BubbleKind
import com.vladimir.messenger.ui.components.BubbleMenuAction
import com.vladimir.messenger.ui.components.ContactCard
import com.vladimir.messenger.ui.components.HintBubble
import com.vladimir.messenger.ui.components.HintBubbleTextColor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.clip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    onNavigateBack: () -> Unit,
    onContactClick: (Contact) -> Unit,
    onAddContactClick: () -> Unit,
    onRenameContactClick: (contactId: String, currentName: String) -> Unit = { _, _ -> },
    onCallContactClick: (contactId: String, contactName: String) -> Unit = { _, _ -> },
    viewModel: ContactsViewModel = hiltViewModel()
) {
    val contacts by viewModel.contacts.collectAsState()
    val context = LocalContext.current
    // Подтверждение удаления контакта из меню «⋮» в пузыре.
    var confirmDelete by remember { mutableStateOf<Contact?>(null) }
    // Контакт, которого приглашаем в группы: не null - открыт выбор групп.
    var inviteFor by remember { mutableStateOf<Contact?>(null) }

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
                title = { Text("Контакты") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    // Пригласить друга — в один тап, прямо из списка контактов.
                    IconButton(
                        onClick = {
                            OwnInvite.link(context)?.let { link ->
                                AppShare.shareInvite(context, OwnInvite.displayName(context), link)
                            }
                        },
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Пригласить друга")
                    }
                    IconButton(onClick = onAddContactClick) {
                        Icon(Icons.Default.Add, contentDescription = "Добавить контакт")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(Modifier.fillMaxSize().padding(paddingValues)) {
            var query by remember { mutableStateOf("") }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                placeholder = { Text("Поиск: имя или @никнейм") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
            )
            val shown = remember(contacts, query) {
                val q = query.trim().lowercase()
                val qNick = q.removePrefix("@")
                if (q.isEmpty()) contacts
                else contacts.filter {
                    it.displayName.lowercase().contains(q) ||
                        it.username.lowercase().contains(qNick)
                }
            }
            if (shown.isEmpty() && contacts.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    // Раунд 48: подсказка в пузыре HintBubble - на обоях и в
                    // ночной теме голый текст не читался.
                    HintBubble {
                        Text(
                            "Никого не нашли по запросу «$query»",
                            style = MaterialTheme.typography.bodyMedium,
                            color = HintBubbleTextColor,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        if (contacts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                // Раунд 48: пустой список контактов тоже в пузыре HintBubble.
                HintBubble {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                    Text(
                        text = "Пока нет контактов",
                        style = MaterialTheme.typography.titleMedium,
                        color = HintBubbleTextColor
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onAddContactClick) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Добавить контакт")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // Пустой список - самое место, чтобы позвать первого друга.
                    OutlinedButton(
                        onClick = {
                            OwnInvite.link(context)?.let { link ->
                                AppShare.shareInvite(context, OwnInvite.displayName(context), link)
                            }
                        },
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Пригласить друга")
                    }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(
                    items = shown,
                    key = { it.id }
                ) { contact ->
                    // ContactCard expects Chat, create a minimal Chat from Contact
                    val ctx = LocalContext.current
                    val shareContact = {
                        try {
                                val link = ContactShareLink.build(contact.id, contact.displayName)
                                val send = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "Мой контакт ${'$'}{contact.displayName} в APU. Открой ссылку для добавления:\n${'$'}{link}",
                                    )
                                    type = "text/plain"
                                }
                                ctx.startActivity(Intent.createChooser(send, "Поделиться контактом"))
                            } catch (_: Exception) {
                            }
                        Unit
                    }
                    // Пузырь контакта — тот же ContactCard, что на главной:
                    // владелец просил, чтобы списки выглядели одинаково.
                    ContactCard(
                        chat = Chat(
                            id = contact.id,
                            contactId = contact.id,
                            contactName = contact.displayName,
                            isContactOnline = contact.isOnline,
                        ),
                        onClick = { onContactClick(contact) },
                        username = contact.username,
                        kind = BubbleKind.Personal,
                        onShareClick = shareContact,
                        menuActions = listOf(
                            BubbleMenuAction(
                                title = "Написать",
                                icon = Icons.Default.Forum,
                                onClick = { onContactClick(contact) },
                            ),
                            BubbleMenuAction(
                                title = "Позвонить",
                                icon = Icons.Default.Call,
                                onClick = {
                                    onCallContactClick(contact.id, contact.displayName)
                                },
                            ),
                            BubbleMenuAction(
                                title = "Переименовать",
                                icon = Icons.Default.Edit,
                                onClick = {
                                    onRenameContactClick(contact.id, contact.displayName)
                                },
                            ),
                            BubbleMenuAction(
                                title = "Пригласить в группу",
                                icon = Icons.Default.GroupAdd,
                                onClick = { inviteFor = contact },
                            ),
                            BubbleMenuAction(
                                title = "Поделиться контактом",
                                icon = Icons.Default.Share,
                                onClick = { shareContact() },
                            ),
                            BubbleMenuAction(
                                title = "Удалить контакт",
                                icon = Icons.Default.Delete,
                                destructive = true,
                                onClick = { confirmDelete = contact },
                            ),
                        ),
                    )
                }
            }
        }
        }
    }
    }

    // Подтверждение удаления контакта.
    confirmDelete?.let { contact ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Удалить контакт?") },
            text = { Text("«${contact.displayName}» будет удалён из списка контактов.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteContact(contact.id)
                    confirmDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Отмена") }
            },
        )
    }

    // Выбор групп для приглашения: список с поиском, можно отметить несколько.
    inviteFor?.let { contact ->
        InviteToGroupsDialog(
            contactName = contact.displayName,
            groups = viewModel.invitableGroups.collectAsState().value,
            onDismiss = { inviteFor = null },
            onConfirm = { ids ->
                inviteFor = null
                viewModel.buildGroupInvites(ids) { invites ->
                    AppShare.shareGroupInvites(context, invites)
                }
            },
        )
    }
}

/**
 * Диалог «Пригласить в группу»: свои группы и каналы, поиск по названию,
 * отметки на нескольких сразу. Одна ссылка годится и тем, у кого APU уже
 * стоит, и тем, кому его ещё ставить - в тексте есть ссылка на установку.
 */
@Composable
private fun InviteToGroupsDialog(
    contactName: String,
    groups: List<InvitableGroup>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }
    val shown = remember(groups, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) groups else groups.filter { it.title.lowercase().contains(q) }
    }
    val listState = rememberLazyListState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Пригласить $contactName") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Поиск группы или канала") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                if (groups.isEmpty()) {
                    Text(
                        "Пока нет групп со ссылкой-приглашением",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else if (shown.isEmpty()) {
                    Text(
                        "Ничего не нашли по запросу «$query»",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    ) {
                        items(items = shown, key = { it.id }) { group ->
                            val checked = selected.contains(group.id)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable {
                                        if (checked) selected.remove(group.id)
                                        else selected.add(group.id)
                                    }
                                    .padding(horizontal = 4.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = checked, onCheckedChange = {
                                    if (checked) selected.remove(group.id)
                                    else selected.add(group.id)
                                })
                                Spacer(Modifier.width(4.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        group.title.ifBlank {
                                            if (group.isChannel) "Канал" else "Группа"
                                        },
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Text(
                                        (if (group.isChannel) "Канал" else "Группа") +
                                            " • участников: ${group.memberCount}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selected.toList()) },
                enabled = selected.isNotEmpty(),
            ) { Text("Пригласить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
