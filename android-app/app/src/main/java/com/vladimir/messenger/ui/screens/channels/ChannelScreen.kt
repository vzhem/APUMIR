package com.vladimir.messenger.ui.screens.channels

// =============================================================================
// CHANNELSCREEN.KT - лента канала
// =============================================================================
// Посты пишут владелец и администраторы, под каждым постом - комментарии.
// Канал живёт на тех же таблицах и той же доставке, что и группа: пост это
// тема, первое сообщение темы это текст поста, остальные - комментарии.
// =============================================================================

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import com.vladimir.messenger.ui.components.ChatWallpaper
import com.vladimir.messenger.ui.components.ImagePreview
import com.vladimir.messenger.util.ImageLinkDetector
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelScreen(
    onOpenComments: (channelId: String, topicId: String) -> Unit,
    onOpenAdmin: (channelId: String) -> Unit,
    onBackClick: () -> Unit,
    viewModel: ChannelViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showNewPost by remember { mutableStateOf(false) }

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
                title = {
                    Column {
                        Text(
                            uiState.channel?.title ?: "Канал",
                            maxLines = 1,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "подписчиков: ${uiState.channel?.memberCount ?: 0}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    // Админ-кабинет канала - тот же, что у группы: участники,
                    // ссылки, заявки, статистика и разрешения.
                    if (uiState.canPost) {
                        IconButton(onClick = { onOpenAdmin(uiState.channelId) }) {
                            Icon(Icons.Default.Settings, contentDescription = "Админ-кабинет")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (uiState.canPost) {
                FloatingActionButton(onClick = { showNewPost = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Новый пост")
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            when {
                uiState.isLoading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )

                // Канала нет в базе: вышли из него или исключили. Экран не
                // должен оставаться пустым - объясняем и оставляем выход.
                uiState.channel == null -> Text(
                    "Канал недоступен: вы в нём больше не состоите.",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )

                uiState.posts.isEmpty() -> Text(
                    if (uiState.canPost) {
                        "Постов пока нет. Нажмите «+», чтобы опубликовать первый."
                    } else {
                        "В канале пока нет постов."
                    },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(uiState.posts, key = { it.topicId }) { post ->
                        PostCard(
                            post = post,
                            onOpenComments = { onOpenComments(uiState.channelId, post.topicId) },
                        )
                    }
                }
            }

            uiState.error?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                )
            }
        }
    }
    }

    if (showNewPost) {
        NewPostDialog(
            creating = uiState.creating,
            onDismiss = {
                showNewPost = false
                viewModel.dismissError()
            },
            onPublish = { text ->
                viewModel.createPost(text)
                showNewPost = false
            },
        )
    }
}

@Composable
private fun PostCard(
    post: ChannelPost,
    onOpenComments: () -> Unit,
) {
    val time = remember(post.timeMs) {
        SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(post.timeMs))
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                post.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${post.authorName} - $time",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val imageUrl = remember(post.text) { ImageLinkDetector.directImageUrl(post.text) }
            if (imageUrl != null) {
                ImagePreview(
                    model = imageUrl,
                    contentDescription = "Картинка к посту",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .padding(top = 8.dp),
                )
            } else {
                Text(
                    post.text,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            HorizontalDivider(modifier = Modifier.padding(top = 10.dp))
            TextButton(onClick = onOpenComments) {
                Text(
                    if (post.comments > 0) {
                        "Комментарии (${post.comments})"
                    } else {
                        "Оставить комментарий"
                    },
                )
            }
        }
    }
}

@Composable
private fun NewPostDialog(
    creating: Boolean,
    onDismiss: () -> Unit,
    onPublish: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый пост") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Текст поста") },
                modifier = Modifier.heightIn(min = 120.dp),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onPublish(text) },
                enabled = text.isNotBlank() && !creating,
            ) { Text("Опубликовать") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
