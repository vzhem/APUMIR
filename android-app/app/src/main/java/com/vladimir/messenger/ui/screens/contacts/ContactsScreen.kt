package com.vladimir.messenger.ui.screens.contacts

import com.vladimir.messenger.ui.components.ApuTextAction
import com.vladimir.messenger.ui.components.swipeBack
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import com.vladimir.messenger.ui.components.ApuScrollbar
import com.vladimir.messenger.ui.components.ApuBubbleMutedColor
import com.vladimir.messenger.ui.components.ApuBubbleShape
import com.vladimir.messenger.ui.components.ApuBubbleSurfaceColor
import com.vladimir.messenger.ui.components.ApuBubbleTextColor
import com.vladimir.messenger.ui.components.ApuSearchField
import com.vladimir.messenger.ui.components.ShareContactChooserDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.vladimir.messenger.util.AppShare
import com.vladimir.messenger.data.link.ShortShare
import com.vladimir.messenger.util.ContactShareLink
import com.vladimir.messenger.util.OwnInvite
import androidx.compose.ui.Alignment
import com.vladimir.messenger.ui.components.ChatWallpaper
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vladimir.messenger.domain.model.Contact
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
    /** Открыть переписку: chatId уже найден, contact нужен для шапки. */
    onContactClick: (chatId: String, contact: Contact) -> Unit,
    onAddContactClick: () -> Unit,
    onRenameContactClick: (contactId: String, currentName: String) -> Unit = { _, _ -> },
    onCallContactClick: (contactId: String, contactName: String) -> Unit = { _, _ -> },
    /**
     * Нижняя панель разделов. Приходит снаружи, из навигации: экран не знает
     * маршрутов и не должен их знать. Пустая по умолчанию, чтобы превью и
     * тесты обходились без навигации.
     */
    bottomBar: @Composable () -> Unit = {},
    viewModel: ContactsViewModel = hiltViewModel()
) {
    val contacts by viewModel.contacts.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    // In contrast to the address book, a list row also needs the real chat:
    // message preview, time and unread badge live in the chats table.
    val contactChatRows by viewModel.sortedContactChatRows.collectAsState()
    val context = LocalContext.current
    // Подтверждение удаления контакта из меню «⋮» в пузыре.
    var confirmDelete by remember { mutableStateOf<Contact?>(null) }
    // Контакт, которого приглашаем в группы: не null - открыт выбор групп.
    var inviteFor by remember { mutableStateOf<Contact?>(null) }
    var showInviteShare by remember { mutableStateOf(false) }
    // Раунд 175: «Поделиться контактом» - сначала выбор пути (в APU / наружу).
    var shareTarget by remember { mutableStateOf<Contact?>(null) }
    // Сортировка открывается прямо из шапки, как в адресной книге телефона.
    var sortMenuExpanded by remember { mutableStateOf(false) }

    // Подложка на весь экран, в том числе под верхней панелью.
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Смахивание вправо работает как «Назад».
            .swipeBack(onBack = onNavigateBack),
    ) {
        ChatWallpaper()
        Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        bottomBar = bottomBar,
        topBar = {
            TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    // Прокрутка НЕ должна красить панель: под ней обои APU.
                    scrolledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                title = { Text("Контакты") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    // Адресная книга может быть длинной: выбор порядка всегда
                    // под рукой, а текущий вариант отмечен галочкой в меню.
                    Box {
                        IconButton(onClick = { sortMenuExpanded = true }) {
                            Icon(
                                Icons.Default.SortByAlpha,
                                contentDescription = "Сортировка: ${sortOrder.title}",
                            )
                        }
                        ContactSortMenu(
                            expanded = sortMenuExpanded,
                            selected = sortOrder,
                            onDismiss = { sortMenuExpanded = false },
                            onSelect = { order ->
                                viewModel.setSortOrder(order)
                                sortMenuExpanded = false
                            },
                        )
                    }
                    // Пригласить друга — в один тап, прямо из списка контактов.
                    IconButton(
                        onClick = {
                            showInviteShare = true
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
            ApuSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Поиск: имя или @никнейм",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
            val shown = remember(contactChatRows, query) {
                val q = query.trim().lowercase()
                val qNick = q.removePrefix("@")
                if (q.isEmpty()) contactChatRows
                else contactChatRows.filter { row ->
                    row.contact.displayName.lowercase().contains(q) ||
                        row.contact.username.lowercase().contains(qNick)
                }
            }
            if (shown.isEmpty() && contactChatRows.isNotEmpty()) {
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
        if (contactChatRows.isEmpty()) {
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
                            showInviteShare = true
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
            // Бегунок справа: в длинном списке видно, где мы находимся.
            val scrollState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = scrollState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(
                        items = shown,
                        key = { it.contact.id }
                    ) { row ->
                        val contact = row.contact
                        // Пузырь контакта — тот же ContactCard, что на главной:
                        // он получает настоящий чат, поэтому превью, время и
                        // счётчик непрочитанных совпадают с разделом «Чаты».
                        ContactCard(
                            chat = row.chat,
                            // Идём через viewModel: он находит настоящий чат.
                            // Прямая передача contact.id открывала «другой»
                            // чат - пустой и с вечными часиками при отправке.
                            onClick = { viewModel.openChatWith(contact) { id -> onContactClick(id, contact) } },
                            username = contact.username,
                            kind = BubbleKind.Personal,
                            presenceLabel = contactPresenceLabel(
                                isOnline = contact.isOnline,
                                lastSeenAtMs = contact.lastSeenAtMs,
                            ),
                            menuActions = listOf(
                                BubbleMenuAction(
                                    title = "Написать",
                                    icon = Icons.Default.Forum,
                                    onClick = { viewModel.openChatWith(contact) { id -> onContactClick(id, contact) } },
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
                                    title = "Пригласить в сообщество",
                                    icon = Icons.Default.GroupAdd,
                                    onClick = { inviteFor = contact },
                                ),
                                BubbleMenuAction(
                                    title = "Поделиться контактом",
                                    icon = Icons.Default.Share,
                                    onClick = { shareTarget = contact },
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
                ApuScrollbar(state = scrollState)
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
                ApuTextAction(
                    label = "Удалить",
                    onClick = {
                    viewModel.deleteContact(contact.id)
                    confirmDelete = null
                },
                )
            },
            dismissButton = {
                ApuTextAction(label = "Отмена", onClick = { confirmDelete = null })
            },
        )
    }

    // Раунд 200: перед отправкой приглашения спрашиваем про APK.
    if (showInviteShare) {
        com.vladimir.messenger.ui.components.InviteAttachDialog(
            title = "Пригласить в APU",
            onDismiss = { showInviteShare = false },
            onShare = { attach ->
                showInviteShare = false
                OwnInvite.link(context)?.let { link ->
                    ShortShare.shareInvite(
                        context, OwnInvite.displayName(context), link, attach,
                    )
                }
            },
        )
    }

    // Выбор групп для приглашения: список с поиском, можно отметить несколько.
    inviteFor?.let { contact ->
        InviteToGroupsDialog(
            contactName = contact.displayName,
            groups = viewModel.invitableGroups.collectAsState().value,
            onDismiss = { inviteFor = null },
            // Отправить прямо в APU: сообщением в чат с этим контактом.
            onSendInApp = { ids ->
                inviteFor = null
                viewModel.sendGroupInvites(contact.id, contact.displayName, ids)
            },
            // Через другие приложения: тем, у кого APU ещё не стоит.
            onShare = { ids, attachApk ->
                inviteFor = null
                viewModel.buildGroupInvites(ids) { invites ->
                    AppShare.shareGroupInvites(context, invites, attachApk)
                }
            },
        )
    }

    // Раунд 175: выбор адресата внутри APU - выбранному уходит ссылка контакта.
    shareTarget?.let { shared ->
        ShareContactChooserDialog(
            sharedName = shared.displayName,
            contacts = viewModel.contacts.collectAsState().value.filter { it.id != shared.id },
            onDismiss = { shareTarget = null },
            onPick = { to ->
                shareTarget = null
                viewModel.sendContactCard(to.id, to.displayName, shared)
            },
        )
    }

    // Короткий отчёт об отправке приглашения.
    val toast by viewModel.toast.collectAsState()
    LaunchedEffect(toast) {
        val message = toast
        if (message != null) {
            android.widget.Toast.makeText(
                context,
                message,
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            viewModel.consumeToast()
        }
    }
}

/**
 * Меню сортировки адресной книги. Это тоже светлый APU-пузырь: обычное тёмное
 * меню Material на обоях выглядело бы отдельным, чужим слоем интерфейса.
 */
@Composable
private fun ContactSortMenu(
    expanded: Boolean,
    selected: ContactSortOrder,
    onDismiss: () -> Unit,
    onSelect: (ContactSortOrder) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 244.dp),
        shape = ApuBubbleShape,
        containerColor = ApuBubbleSurfaceColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
        ),
    ) {
        ContactSortOrder.entries.forEach { order ->
            DropdownMenuItem(
                text = {
                    Column {
                        Text(
                            text = order.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = ApuBubbleTextColor,
                        )
                        Text(
                            text = order.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = ApuBubbleMutedColor,
                        )
                    }
                },
                trailingIcon = {
                    if (order == selected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Выбрано",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                onClick = { onSelect(order) },
            )
        }
    }
}

/**
 * Диалог «Пригласить в сообщество»: свои группы и каналы, поиск по названию,
 * отметки на нескольких сразу. Одна ссылка годится и тем, у кого APU уже
 * стоит, и тем, кому его ещё ставить - в тексте есть ссылка на установку.
 */
@Composable
private fun InviteToGroupsDialog(
    contactName: String,
    groups: List<InvitableGroup>,
    onDismiss: () -> Unit,
    onSendInApp: (List<String>) -> Unit,
    /** Раунд 198: вторым аргументом - «приложить установочный APK». */
    onShare: (List<String>, Boolean) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }
    // Раунд 198: получатель извне может быть без APU - по умолчанию
    // прикладываем установочный APK; галочка позволяет не таскать 40 МБ.
    var attachApk by remember { mutableStateOf(true) }
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
                ApuSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Поиск группы или канала",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { attachApk = !attachApk }
                        .padding(horizontal = 4.dp),
                ) {
                    Checkbox(checked = attachApk, onCheckedChange = { attachApk = it })
                    Text(
                        "Приложить установочный файл (APK)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(4.dp))
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
            Column(horizontalAlignment = Alignment.End) {
                Button(
                    onClick = { onSendInApp(selected.toList()) },
                    enabled = selected.isNotEmpty(),
                ) {
                    Icon(Icons.Default.Send, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Отправить в APU")
                }
                ApuTextAction(
                    label = "Другим приложением",
                    onClick = { onShare(selected.toList(), attachApk) },
                    enabled = selected.isNotEmpty(),
                    icon = Icons.Default.Share,
                )
            }
        },
        dismissButton = {
            ApuTextAction(label = "Отмена", onClick = onDismiss)
        },
    )
}
