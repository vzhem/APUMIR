package com.vladimir.messenger.ui.screens.groups

// =============================================================================
// GROUPCHATSCREEN.KT — чат группы: темы, сообщения, закрепы
// =============================================================================

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.data.local.entity.MessageEntity
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
    viewModel: GroupChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    var showNewTopic by remember { mutableStateOf(false) }
    val senderNames = remember(uiState.members) {
        uiState.members.associate { it.nodeId to it.displayName }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(uiState.group?.title ?: "Группа", fontWeight = FontWeight.SemiBold)
                        Text(
                            (uiState.group?.memberCount ?: 0).toString() + " участн.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                navigationIcon = { TextButton(onClick = onBackClick) { Text("Назад") } },
                actions = {
                    IconButton(onClick = { onOpenAdmin(uiState.groupId) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Управление группой")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

            // ── Темы: у каждой видно, сколько сообщений накопилось и сколько не прочитано
            if (uiState.group?.topicsEnabled == true && uiState.topics.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(uiState.topics, key = { it.id }) { topic ->
                        FilterChip(
                            selected = topic.id == uiState.selectedTopicId,
                            onClick = { viewModel.selectTopic(topic.id) },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(topic.name)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        topic.messageCount.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                    if (topic.unreadCount > 0) {
                                        Spacer(Modifier.width(6.dp))
                                        Badge { Text(topic.unreadCount.toString()) }
                                    }
                                }
                            },
                            trailingIcon = if (topic.isClosed) {
                                { Text("•", style = MaterialTheme.typography.labelSmall) }
                            } else {
                                null
                            },
                        )
                    }
                    if (uiState.canManageTopics) {
                        item {
                            FilterChip(
                                selected = false,
                                onClick = { showNewTopic = true },
                                label = { Text("Новая тема") },
                                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                            )
                        }
                    }
                }
            }

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

            if (uiState.error != null) {
                Text(
                    uiState.error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
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
                        senderName = senderNames[message.senderId] ?: message.senderId.takeLast(6),
                        canPin = uiState.canPin,
                        onTogglePin = { viewModel.togglePin(message.id, !message.isPinned) },
                    )
                }
            }

            // ── Поле ввода
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Сообщение") },
                    maxLines = 4,
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
