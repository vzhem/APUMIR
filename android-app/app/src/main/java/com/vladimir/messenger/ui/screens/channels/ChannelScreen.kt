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
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import com.vladimir.messenger.util.InlineImage
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
    // Пост, который сейчас правят (автор или владелец канала).
    var editingPost by remember { mutableStateOf<ChannelPost?>(null) }
    val context = LocalContext.current

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
                            // Править пост может его автор и владелец канала.
                            // Считаем из состояния экрана, а не через ViewModel:
                            // так карандаш появится сразу, как только станет
                            // известен мой идентификатор.
                            val myId = uiState.myId
                            PostCard(
                                post = post,
                                canEdit = myId.isNotBlank() &&
                                    (post.authorId == myId || uiState.channel?.ownerId == myId),
                                onEdit = { editingPost = post },
                                onOpenComments = { onOpenComments(uiState.channelId, post.topicId) },
                                onSaveToFavorites = { viewModel.savePostToFavorites(post) },
                                // Репост: текст, ссылка и фотографии файлами.
                                onSharePost = { viewModel.sharePost(context, post) },
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
        PostEditorDialog(
            title = "Новый пост",
            confirmLabel = "Опубликовать",
            creating = uiState.creating,
            onDismiss = {
                showNewPost = false
                viewModel.dismissError()
            },
            onConfirm = { text, photos ->
                viewModel.createPost(text, photos)
                showNewPost = false
            },
            onPickImages = { uris, onReady -> viewModel.prepareImages(context, uris, onReady) },
        )
    }

    // Правка: тот же редактор с готовым текстом. Фотографии показываем, но
    // не меняем - они уже разошлись по подписчикам отдельными пакетами.
    editingPost?.let { post ->
        PostEditorDialog(
            title = "Изменить пост",
            confirmLabel = "Сохранить",
            initialText = post.text,
            initialImages = post.images,
            imagesEditable = false,
            creating = uiState.creating,
            onDismiss = {
                editingPost = null
                viewModel.dismissError()
            },
            onConfirm = { text, _ ->
                viewModel.editPost(post, text)
                editingPost = null
            },
            onPickImages = { _, onReady -> onReady(emptyList()) },
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun PostCard(
    post: ChannelPost,
    /** Правка доступна автору поста и владельцу канала. */
    canEdit: Boolean = false,
    onEdit: () -> Unit = {},
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
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
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
                }
                // Карандаш в углу поста: не теснит нижний ряд кнопок, который
                // на узком экране и так заполнен.
                if (canEdit) {
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Изменить пост",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            if (post.images.isNotEmpty() || post.pendingPhotos > 0) {
                // Фотографии поста: одна - во всю ширину, несколько - галерея
                // с листанием и счётчиком «1/3».
                PostGallery(images = post.images, pending = post.pendingPhotos)
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

/**
 * Фотографии поста.
 *
 * Одна фотография рисуется во всю ширину, высота подстраивается. Несколько -
 * листаются по одной (HorizontalPager) в рамке фиксированной высоты, чтобы
 * карточка не прыгала между вертикальными и горизонтальными снимками; в углу
 * счётчик «2/5». Недоехавшие фото обозначены подписью - их куски ещё в пути.
 */
@Composable
private fun PostGallery(images: List<String>, pending: Int) {
    // Нажатие на фото открывает его на весь экран (щипок - увеличение,
    // свайп - соседние фото поста). Здесь помним, с какого начать.
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    viewerIndex?.let { start ->
        com.vladimir.messenger.ui.components.PhotoViewer(
            photos = images.map { com.vladimir.messenger.ui.components.PhotoSource.Encoded(it) },
            initialIndex = start,
            onDismiss = { viewerIndex = null },
        )
    }
    if (images.size == 1 && pending == 0) {
        // Разбор строки base64 - в фоне и с кэшем.
        val single = com.vladimir.messenger.ui.components.AvatarBitmaps.rememberAvatar(images[0])
        if (single != null) {
            androidx.compose.foundation.Image(
                bitmap = single.asImageBitmap(),
                contentDescription = "Фото поста",
                // Без contentScale картинка рисовалась в своих пикселях и
                // висела крошечной посреди карточки: сжатие ужимает её до
                // нескольких сотен точек по стороне. FillWidth растягивает
                // на всю ширину поста, высота подстраивается сама.
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { viewerIndex = 0 },
            )
        }
        return
    }
    if (images.isEmpty()) {
        Text(
            if (pending == 1) "Фото ещё загружается…" else "Фото ещё загружаются…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        return
    }
    val pagerState = rememberPagerState(pageCount = { images.size })
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .height(280.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { page -> page },
        ) { page ->
            val bitmap = com.vladimir.messenger.ui.components.AvatarBitmaps
                .rememberAvatar(images[page])
            Box(modifier = Modifier.fillMaxSize()) {
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Фото поста ${page + 1} из ${images.size}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { viewerIndex = page },
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }
        }
        Text(
            buildString {
                append(pagerState.currentPage + 1).append('/').append(images.size)
                if (pending > 0) append(" · ещё ").append(pending)
            },
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * Редактор поста: и для нового, и для правки существующего.
 *
 * Фотографий до [InlineImage.MAX_PHOTOS]; выбираются сразу несколько. При
 * правке фотографии показываются, но не меняются ([imagesEditable] = false):
 * они уже разошлись подписчикам отдельными пакетами, меняется только текст.
 */
@Composable
private fun PostEditorDialog(
    title: String,
    confirmLabel: String,
    creating: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, List<String>) -> Unit,
    /** Сжатие выбранных картинок: делается во ViewModel, вне главного потока. */
    onPickImages: (List<android.net.Uri>, (List<String>) -> Unit) -> Unit,
    initialText: String = "",
    initialImages: List<String> = emptyList(),
    imagesEditable: Boolean = true,
) {
    var text by remember { mutableStateOf(initialText) }
    // Уже сжатые картинки (base64) по порядку.
    var images by remember { mutableStateOf(initialImages) }
    var preparing by remember { mutableStateOf(false) }
    var overflowHint by remember { mutableStateOf<String?>(null) }

    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val room = (InlineImage.MAX_PHOTOS - images.size).coerceAtLeast(0)
            val taken = uris.take(room)
            overflowHint = if (uris.size > room) {
                "К посту можно приложить не больше ${InlineImage.MAX_PHOTOS} фото"
            } else {
                null
            }
            if (taken.isNotEmpty()) {
                preparing = true
                onPickImages(taken) { encoded ->
                    images = (images + encoded).take(InlineImage.MAX_PHOTOS)
                    preparing = false
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Текст поста") },
                    modifier = Modifier.heightIn(min = 120.dp),
                )
                Spacer(Modifier.height(8.dp))
                if (images.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(images, key = { index, _ -> index }) { index, b64 ->
                            val thumb = com.vladimir.messenger.ui.components.AvatarBitmaps
                                .rememberAvatar(b64)
                            Box(
                                modifier = Modifier
                                    .size(76.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                if (thumb != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = thumb.asImageBitmap(),
                                        contentDescription = "Фото ${index + 1}",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                if (imagesEditable) {
                                    IconButton(
                                        onClick = {
                                            images = images.toMutableList().also { it.removeAt(index) }
                                        },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(24.dp)
                                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp)),
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Убрать фото",
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                if (imagesEditable) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { picker.launch("image/*") },
                            enabled = !preparing && !creating && images.size < InlineImage.MAX_PHOTOS,
                        ) {
                            Text(
                                if (images.isEmpty()) "Прикрепить фото" else "Ещё фото (${images.size}/${InlineImage.MAX_PHOTOS})",
                            )
                        }
                        if (preparing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                    overflowHint?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (images.isNotEmpty()) {
                    Text(
                        "Фотографии при правке не меняются",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text, images) },
                enabled = (text.isNotBlank() || images.isNotEmpty()) && !creating && !preparing,
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
