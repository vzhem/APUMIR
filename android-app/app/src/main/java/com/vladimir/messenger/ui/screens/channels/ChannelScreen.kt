package com.vladimir.messenger.ui.screens.channels

// =============================================================================
// CHANNELSCREEN.KT - лента канала
// =============================================================================
// Посты пишут владелец и администраторы, под каждым постом - комментарии.
// Канал живёт на тех же таблицах и той же доставке, что и группа: пост это
// тема, первое сообщение темы это текст поста, остальные - комментарии.
// =============================================================================

import com.vladimir.messenger.ui.components.swipeBack
import com.vladimir.messenger.ui.components.ApuScrollbar
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.ui.components.ChatWallpaper
import com.vladimir.messenger.ui.components.HintBubble
import com.vladimir.messenger.ui.components.HintBubbleTextColor
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
    val context = LocalContext.current
    // Ссылку на пост создаёт репозиторий (запрос к базе), поэтому «Поделиться»
    // работает в корутине, а не прямо в обработчике нажатия.
    val shareScope = rememberCoroutineScope()

    // Подложка на весь экран, в том числе под верхней панелью.
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Смахивание вправо работает как «Назад».
            .swipeBack(onBack = onBackClick),
    ) {
        ChatWallpaper()
        Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    // Прокрутка НЕ должна красить панель: под ней обои APU.
                    scrolledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
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
                uiState.channel == null -> HintBubble(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                ) {
                    Text(
                        "Канал недоступен: вы в нём больше не состоите.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = HintBubbleTextColor,
                    )
                }

                // Раунд 48: подсказка в пузыре HintBubble - голым Text она
                // терялась на тёмной теме поверх обоев.
                uiState.posts.isEmpty() -> HintBubble(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                ) {
                    Text(
                        if (uiState.canPost) {
                            "Постов пока нет. Нажмите «+», чтобы опубликовать первый."
                        } else {
                            "В канале пока нет постов."
                        },
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = HintBubbleTextColor,
                    )
                }

                else -> {
                val listState = rememberLazyListState()
                // Лента растёт вниз: при открытии и при новом посте
                // прокручиваемся к самому свежему, как в переписке.
                LaunchedEffect(uiState.posts.size) {
                    if (uiState.posts.isNotEmpty()) {
                        listState.scrollToItem(uiState.posts.lastIndex)
                    }
                }
                // Бегунок справа: в длинном списке видно, где мы находимся.
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        // Снизу больше места: круглая кнопка «+» висит поверх
                        // ленты и раньше накрывала «Поделиться» и «В избранное»
                        // у последнего поста - нажать их было нельзя.
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            end = 12.dp,
                            top = 12.dp,
                            bottom = 88.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(uiState.posts, key = { it.topicId }) { post ->
                            // Пост показался на экране - засчитываем просмотр.
                            // Повторные открытия счётчик не двигают: считаются
                            // разные читатели, а не показы.
                            LaunchedEffect(post.topicId) {
                                viewModel.onPostSeen(post.topicId)
                            }
                            PostCard(
                                post = post,
                                onOpenComments = { onOpenComments(uiState.channelId, post.topicId) },
                                onSaveToFavorites = { viewModel.savePostToFavorites(post) },
                                onSharePost = {
                                    shareScope.launch {
                                        val link = viewModel.postLink(post.topicId)
                                        if (link != null) {
                                            val title = post.title.ifBlank { "Пост" }
                                            val send = android.content.Intent().apply {
                                                action = android.content.Intent.ACTION_SEND
                                                type = "text/plain"
                                                putExtra(
                                                    android.content.Intent.EXTRA_TEXT,
                                                    "$title\n\n${post.text}\n\nОткрыть в APU:\n$link",
                                                )
                                            }
                                            context.startActivity(
                                                android.content.Intent.createChooser(send, "Поделиться постом")
                                            )
                                        }
                                    }
                                },
                                reactions = uiState.reactions[post.messageId].orEmpty(),
                                onToggleReaction = { emoji ->
                                    viewModel.toggleReaction(post.messageId, emoji)
                                },
                                onRemoveReaction = { viewModel.removeReaction(post.messageId) },
                            )
                        }
                    }
                    ApuScrollbar(state = listState)
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
            onPublish = { text, imageB64 ->
                viewModel.createPost(text, imageB64)
                showNewPost = false
            },
            onPickImage = { uri, onReady -> viewModel.prepareImage(context, uri, onReady) },
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun PostCard(
    post: ChannelPost,
    onOpenComments: () -> Unit,
    onSaveToFavorites: () -> Unit = {},
    onSharePost: () -> Unit = {},
    reactions: List<com.vladimir.messenger.data.reaction.ReactionSummary> = emptyList(),
    onToggleReaction: (String) -> Unit = {},
    onRemoveReaction: () -> Unit = {},
) {
    var showReactions by remember { mutableStateOf(false) }
    // Копирование текста поста: удержание открывает окно с выделением, как в
    // переписке. Раньше текст поста нельзя было скопировать вообще.
    var selectPostText by remember { mutableStateOf(false) }
    val time = remember(post.timeMs) {
        SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(post.timeMs))
    }

    if (selectPostText) {
        com.vladimir.messenger.ui.components.SelectTextDialog(
            // Заголовок и текст вместе: в посте они смысловое целое, и
            // копировать чаще нужно оба.
            text = listOf(post.title, post.text)
                .filter { it.isNotBlank() }
                .joinToString("\n\n"),
            onDismiss = { selectPostText = false },
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    if (post.title.isNotBlank() || post.text.isNotBlank()) {
                        selectPostText = true
                    }
                },
            ),
    ) {
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
            // Прикреплённое фото поста: разбор строки base64 - в фоне и с кэшем.
            val attached = com.vladimir.messenger.ui.components.AvatarBitmaps
                .rememberAvatar(post.imageB64)
            val shownAttached = attached
            if (shownAttached != null) {
                androidx.compose.foundation.Image(
                    bitmap = shownAttached.asImageBitmap(),
                    contentDescription = "Фото поста",
                    // Без contentScale картинка рисовалась в своих пикселях и
                    // висела крошечной посреди карточки: сжатие ужимает её до
                    // нескольких сотен точек по стороне. FillWidth растягивает
                    // на всю ширину поста, высота подстраивается сама.
                    contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
                if (post.text.isNotBlank()) {
                    Text(post.text, modifier = Modifier.padding(top = 8.dp))
                }
            } else {
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
            }
            // Поставленные реакции - прямо под текстом поста, как в привычных
            // каналах: значок с числом, свой обведён золотом.
            com.vladimir.messenger.ui.components.ReactionRow(
                reactions = reactions,
                onToggle = { showReactions = true },
            )
            // Просмотры и общее число реакций - как в привычных каналах:
            // автору видно, дошёл ли пост, читателю - насколько он живой.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Icon(
                    Icons.Default.Visibility,
                    contentDescription = "Просмотры",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    post.views.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val totalReactions = reactions.sumOf { it.count }
                if (totalReactions > 0) {
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        Icons.Default.Favorite,
                        contentDescription = "Реакции",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        totalReactions.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.padding(top = 10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showReactions = true }) {
                    Text("Реакция")
                }
                TextButton(
                    onClick = onOpenComments,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (post.comments > 0) {
                            "Комментарии (${post.comments})"
                        } else {
                            "Оставить комментарий"
                        },
                    )
                }
                // Переслать пост: и внутрь APU, и в любой другой мессенджер.
                IconButton(onClick = onSharePost) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = "Поделиться постом",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                // Пост сохраняется себе одним нажатием - так человек забирает
                // нужное из канала, не переписывая текст вручную.
                IconButton(onClick = onSaveToFavorites) {
                    Icon(
                        Icons.Default.BookmarkBorder,
                        contentDescription = "В избранное",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
    if (showReactions) {
        val mine = reactions.firstOrNull { it.mine }?.emoji
        com.vladimir.messenger.ui.components.ReactionPickerDialog(
            onDismiss = { showReactions = false },
            myEmoji = mine,
            onRemove = {
                showReactions = false
                onRemoveReaction()
            },
            onPick = { emoji ->
                showReactions = false
                onToggleReaction(emoji)
            },
        )
    }
}

@Composable
private fun NewPostDialog(
    creating: Boolean,
    onDismiss: () -> Unit,
    onPublish: (String, String?) -> Unit,
    /** Сжатие выбранной картинки: делается во ViewModel, вне главного потока. */
    onPickImage: (android.net.Uri, (String?) -> Unit) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    // Уже сжатая картинка (base64) и её же превью.
    var imageB64 by remember { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(false) }

    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            preparing = true
            onPickImage(uri) { encoded ->
                imageB64 = encoded
                preparing = false
            }
        }
    }

    val preview = com.vladimir.messenger.ui.components.AvatarBitmaps.rememberAvatar(imageB64)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый пост") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Текст поста") },
                    modifier = Modifier.heightIn(min = 120.dp),
                )
                Spacer(Modifier.height(8.dp))
                val shownPreview = preview
                if (shownPreview != null) {
                    androidx.compose.foundation.Image(
                        bitmap = shownPreview.asImageBitmap(),
                        contentDescription = "Прикреплённое фото",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { picker.launch("image/*") },
                        enabled = !preparing && !creating,
                    ) {
                        Text(if (imageB64 == null) "Прикрепить фото" else "Заменить фото")
                    }
                    if (imageB64 != null) {
                        TextButton(onClick = { imageB64 = null }) { Text("Убрать") }
                    }
                    if (preparing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onPublish(text, imageB64) },
                enabled = (text.isNotBlank() || imageB64 != null) && !creating && !preparing,
            ) { Text("Опубликовать") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
