package com.vladimir.messenger.ui.screens.groups

// =============================================================================
// GROUPCHATSCREEN.KT — чат группы: слева значки групп, справа темы пузырями
// =============================================================================
// Раскладка по требованию владельца (2026-08-31, как в мессенджере со
// скриншота): после входа в группу слева остаётся вертикальная колонка
// значков всех групп и каналов с бейджами непрочитанных, а справа темы
// выбранной группы идут вертикальным списком, каждая в своём пузыре,
// и у каждой — бейдж непрочитанных. Нажатие на тему открывает ленту.

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.data.group.GroupSummary
import com.vladimir.messenger.data.group.TopicSummary
import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.ui.components.ChatWallpaper
import com.vladimir.messenger.ui.components.ImagePreview
import com.vladimir.messenger.util.ImageLinkDetector
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(
    onOpenAdmin: (groupId: String) -> Unit,
    onBackClick: () -> Unit,
    /** Нажатие на значок другой группы в левой колонке. */
    onSwitchGroup: (groupId: String) -> Unit = {},
    /** Нажатие на значок канала в левой колонке. */
    onSwitchChannel: (channelId: String) -> Unit = {},
    viewModel: GroupChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    var showNewTopic by remember { mutableStateOf(false) }
    // Открыта ли лента конкретной темы. Пока не открыта и темы есть —
    // показываем вертикальный список тем пузырями, как просил владелец.
    var showFeed by remember { mutableStateOf(uiState.startInTopic) }
    val hasTopics = uiState.group?.topicsEnabled == true && uiState.topics.isNotEmpty()
    val showTopicsList = hasTopics && !showFeed
    val selectedTopicName = uiState.topics.firstOrNull { it.id == uiState.selectedTopicId }?.name
    val senderNames = remember(uiState.members) {
        uiState.members.associate { it.nodeId to it.displayName }
    }

    // Подложка на весь экран, в том числе под верхней панелью.
    Box(modifier = Modifier.fillMaxSize()) {
        ChatWallpaper()
        Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                ),
                title = {
                    // Название группы на белой полосочке со скруглениями и
                    // золотой рамкой - читается на любой подложке.
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xFFF5F7FA).copy(alpha = 0.92f))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                RoundedCornerShape(18.dp),
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        // Аватар группы слева от названия, если задан.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val storeAvatars by com.vladimir.messenger.ui.theme.AvatarStore.avatars
                                .collectAsState()
                            val gid = uiState.group?.id.orEmpty()
                            val bmp = remember(storeAvatars["g:$gid"]) {
                                storeAvatars["g:$gid"]?.let { b64 ->
                                    try {
                                        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    } catch (e: Exception) { null }
                                }
                            }
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape),
                                    contentScale = ContentScale.Crop,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Column {
                            Text(
                                uiState.group?.title ?: "Группа",
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF1E2430),
                            )
                            Text(
                                (if (showFeed && selectedTopicName != null) "$selectedTopicName · " else "") +
                                    (uiState.group?.memberCount ?: 0).toString() + " участн.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF5A6472),
                            )
                            }
                        }
                    }
                },
                navigationIcon = {
                    TextButton(onClick = {
                        // Из ленты темы «Назад» возвращает к списку тем,
                        // а не сразу из группы.
                        if (hasTopics && showFeed) showFeed = false else onBackClick()
                    }) { Text("Назад") }
                },
                actions = {
                    IconButton(onClick = { onOpenAdmin(uiState.groupId) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Управление группой")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize()) {

            // ── Левая колонка: значки своих групп и каналов, у каждого —
            // сколько сообщений не прочитано. Выбранная обведена золотым.
            GroupRail(
                groups = uiState.allGroups,
                currentGroupId = uiState.groupId,
                onGroupClick = onSwitchGroup,
                onChannelClick = onSwitchChannel,
            )

            // ── Правая часть: список тем пузырями либо лента выбранной темы.
            Column(modifier = Modifier.weight(1f).fillMaxSize()) {

            if (uiState.error != null) {
                Text(
                    uiState.error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            if (showTopicsList) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(uiState.topics, key = { it.id }) { topic ->
                        TopicBubble(topic = topic) {
                            viewModel.selectTopic(topic.id)
                            showFeed = true
                        }
                    }
                    if (uiState.canManageTopics) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showNewTopic = true },
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFFF5F7FA).copy(alpha = 0.6f),
                                ),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = null,
                                        tint = Color(0xFF5A6472),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Новая тема", color = Color(0xFF5A6472))
                                }
                            }
                        }
                    }
                }
            } else {

            // ── Закреплённые сообщения
            if (uiState.pinned.isNotEmpty()) {
                // Закрепы у каждой темы свои, поэтому показываем и имя темы.
                val pinnedTopicName = uiState.topics
                    .firstOrNull { it.id == uiState.selectedTopicId }
                    ?.name
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.PushPin, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Закреплённые" +
                                    (pinnedTopicName?.let { " · $it" }.orEmpty()) +
                                    if (uiState.pinned.size > 1) {
                                        " (" + uiState.pinned.size + ")"
                                    } else {
                                        ""
                                    },
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        // Каждое закреплённое сообщение — своей строкой, и рядом
                        // кнопка «Открепить»: снять закреп можно прямо отсюда,
                        // не разыскивая сообщение в ленте.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 168.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            uiState.pinned.forEach { m ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        m.content,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (uiState.canPin) {
                                        IconButton(
                                            onClick = { viewModel.togglePin(m.id, false) },
                                        ) {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = "Открепить",
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Лента темы
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(uiState.messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        senderName = senderNames[message.senderId]?.takeIf { it.isNotBlank() }
                            ?: "Участник " + message.senderId.takeLast(4),
                        canPin = uiState.canPin,
                        onTogglePin = { viewModel.togglePin(message.id, !message.isPinned) },
                    )
                }
            }

            // ── Поле ввода: подложка следует теме и пропускает обои (раунд 45).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
                    )
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                        RoundedCornerShape(18.dp),
                    )
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Сообщение") },
                    maxLines = 4,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFF1E2430),
                        unfocusedTextColor = Color(0xFF1E2430),
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                        focusedPlaceholderColor = Color(0xFF5A6472),
                        unfocusedPlaceholderColor = Color(0xFF5A6472),
                    ),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    enabled = draft.isNotBlank() && !uiState.sending,
                    onClick = {
                        viewModel.send(draft)
                        draft = ""
                    },
                ) { Text("Отправить") }
            }

            } // else: лента темы
            } // правая колонка
            } // Row: левая колонка + правая
        }
    }
    }

    if (showNewTopic) {
        NewTopicDialog(
            onDismiss = { showNewTopic = false },
            onCreate = { name ->
                viewModel.createTopic(name)
                showNewTopic = false
            },
        )
    }
}

// =============================================================================
// Левая колонка: значки групп и каналов с непрочитанными
// =============================================================================

@Composable
private fun GroupRail(
    groups: List<GroupSummary>,
    currentGroupId: String,
    onGroupClick: (String) -> Unit,
    onChannelClick: (String) -> Unit,
) {
    val storeAvatars by com.vladimir.messenger.ui.theme.AvatarStore.avatars
        .collectAsState()
    LazyColumn(
        modifier = Modifier
            .width(76.dp)
            .fillMaxHeight()
            .background(Color(0xFFF5F7FA).copy(alpha = 0.55f)),
        contentPadding = PaddingValues(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(groups, key = { it.id }) { group ->
            val selected = group.id == currentGroupId
            Box(
                modifier = Modifier.clickable {
                    if (!selected) {
                        if (group.isChannel) onChannelClick(group.id) else onGroupClick(group.id)
                    }
                },
            ) {
                GroupRailAvatar(
                    groupId = group.id,
                    title = group.title,
                    avatarB64 = storeAvatars["g:" + group.id],
                    selected = selected,
                )
                if (group.unreadCount > 0) {
                    Box(modifier = Modifier.align(Alignment.TopEnd)) {
                        UnreadBadge(group.unreadCount)
                    }
                }
            }
        }
    }
}

/** Круглый аватар группы: картинка из хранилища либо первая буква названия. */
@Composable
private fun GroupRailAvatar(
    groupId: String,
    title: String,
    avatarB64: String?,
    selected: Boolean,
) {
    val bmp = remember(avatarB64) {
        avatarB64?.let { b64 ->
            try {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) { null }
        }
    }
    Box(
        modifier = Modifier
            .size(52.dp)
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                } else {
                    Modifier
                }
            )
            .padding(2.dp)
            .clip(CircleShape)
            .then(if (bmp == null) Modifier.background(Color(0xFFE8EEF5)) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                title.take(1).uppercase(),
                color = Color(0xFF1E2430),
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Кружок с числом непрочитанных: тёмная цифра на золоте, как в списках. */
@Composable
private fun UnreadBadge(count: Int) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF1E2430),
            fontWeight = FontWeight.Bold,
        )
    }
}

// =============================================================================
// Тема в своём пузыре: иконка, название, превью, время и непрочитанные
// =============================================================================

@Composable
private fun TopicBubble(topic: TopicSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFF5F7FA).copy(alpha = 0.92f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE8EEF5)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (topic.iconEmoji.isNotBlank()) topic.iconEmoji else "#",
                    fontSize = 20.sp,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        topic.name,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1E2430),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (topic.isClosed) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = "Тема закрыта",
                            modifier = Modifier.size(12.dp),
                            tint = Color(0xFF5A6472),
                        )
                    }
                }
                Text(
                    topic.lastMessagePreview
                        ?: if (topic.messageCount > 0) {
                            topic.messageCount.toString() + " сообщ."
                        } else {
                            "Нет сообщений"
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF5A6472),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    topicTimeLabel(topic.lastMessageAtMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF5A6472),
                )
                if (topic.unreadCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    UnreadBadge(topic.unreadCount)
                }
            }
        }
    }
}

/** Как в мессенджерах: сегодня — время, неделя — день недели, дальше — дата. */
private fun topicTimeLabel(ms: Long?): String {
    if (ms == null || ms <= 0L) return ""
    val now = System.currentTimeMillis()
    return when {
        isSameDay(ms, now) -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
        now - ms < 7L * 86_400_000L -> SimpleDateFormat("EEE", Locale("ru")).format(Date(ms))
        else -> SimpleDateFormat("d MMM", Locale("ru")).format(Date(ms))
    }
}

private fun isSameDay(a: Long, b: Long): Boolean {
    val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
    val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) &&
        ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
}

@Composable
private fun MessageBubble(
    // Картинки и гифки в темах показываем так же, как в личном чате.
    message: MessageEntity,
    senderName: String,
    canPin: Boolean,
    onTogglePin: () -> Unit,
) {
    val time = remember(message.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isFromMe) Arrangement.End else Arrangement.Start,
    ) {
        Card(modifier = Modifier.widthIn(max = 300.dp)) {
            Column(modifier = Modifier.padding(8.dp)) {
                if (!message.isFromMe) {
                    Text(senderName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
                val imageUrl = remember(message.content) {
                    ImageLinkDetector.directImageUrl(message.content)
                }
                if (imageUrl != null) {
                    ImagePreview(
                        model = imageUrl,
                        contentDescription = "Картинка из сообщения",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                    )
                } else {
                    Text(message.content)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(time, style = MaterialTheme.typography.labelSmall)
                    if (message.isPinned) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.PushPin, contentDescription = "Закреплено", modifier = Modifier.size(12.dp))
                    }
                }
            }
        }
        if (canPin) {
            IconButton(onClick = onTogglePin, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.PushPin,
                    contentDescription = if (message.isPinned) "Открепить" else "Закрепить",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun NewTopicDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая тема") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Название темы") }, singleLine = true)
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name) }) { Text("Создать") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } },
    )
}
