package com.vladimir.messenger.ui.components

import android.Manifest
import android.graphics.BitmapFactory
import android.content.pm.PackageManager
import android.net.Uri
import android.text.format.DateUtils
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.util.Base64
import com.vladimir.messenger.data.story.StoryPhoto
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.R
import com.vladimir.messenger.data.story.StoryStore
import com.vladimir.messenger.ui.theme.AvatarStore

/** Порядок фонов — часть wire-формата, не менять при обновлении палитры. */
private val storyBackgrounds = listOf(
    Brush.linearGradient(listOf(Color(0xFF18265D), Color(0xFF683C92), Color(0xFFB66D8A))),
    Brush.linearGradient(listOf(Color(0xFF083F50), Color(0xFF167B83), Color(0xFF315BBC))),
    Brush.linearGradient(listOf(Color(0xFF813739), Color(0xFFBE6847), Color(0xFFB78B57))),
    Brush.linearGradient(listOf(Color(0xFF071426), Color(0xFF1D3450), Color(0xFF334A69))),
)
private val storyGold = Color(0xFFE6BE70)

@Composable
fun StoriesStrip(
    stories: List<StoryStore.Story>,
    onAddClick: () -> Unit,
    onOpenAuthor: (authorId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val seen by StoryStore.seen.collectAsStateWithLifecycle()
    val avatars by AvatarStore.avatars.collectAsStateWithLifecycle()
    val profileName by com.vladimir.messenger.ui.theme.UsernameHolder.name.collectAsStateWithLifecycle()
    val mine = stories.filter { it.mine }
    val byAuthor = stories.filter { !it.mine }.groupBy { it.authorId }.values
        .map { it.sortedBy { s -> s.createdAtMs } }
        .sortedWith(compareByDescending<List<StoryStore.Story>> { list -> list.any { it.id !in seen } }
            .thenByDescending { it.last().createdAtMs })
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Как в Telegram: свой кружок один. Плюс на нём — добавить, кружок —
        // смотреть (если история уже есть).
        item(key = "mine") {
            val hasOwn = mine.isNotEmpty()
            StoryBubble(
                title = stringResource(R.string.story_mine),
                avatar = null,
                viewed = if (hasOwn) mine.map { true } else emptyList(),
                self = true,
                selfName = profileName,
                onAddClick = onAddClick,
            ) {
                if (StoryStrip.selfOpensViewer(hasOwn)) onOpenAuthor(mine.first().authorId)
                else onAddClick()
            }
        }
        items(byAuthor, key = { it.first().authorId }) { list ->
            StoryBubble(list.last().authorName.ifBlank { "?" }, avatars[list.first().authorId], list.map { it.id in seen }) {
                onOpenAuthor(list.first().authorId)
            }
        }
    }
}

@Composable
private fun StoryBubble(
    title: String,
    avatar: String?,
    viewed: List<Boolean>,
    self: Boolean = false,
    selfName: String? = null,
    onAddClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(
        Modifier.width(if (self) 80.dp else 72.dp).then(if (self) Modifier else Modifier.clickable(onClick = onClick)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(if (self) 72.dp else 64.dp)) {
            Box(
                Modifier
                    .size(64.dp)
                    .align(Alignment.TopStart)
                    .then(if (self) Modifier.clickable(onClick = onClick) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val count = viewed.size.coerceAtMost(30)
                    val stroke = 2.5.dp.toPx()
                    if (count == 0) {
                        drawCircle(
                            color = storyGold.copy(alpha = .45f),
                            radius = (size.minDimension - stroke) / 2f,
                            style = Stroke(stroke),
                        )
                    } else {
                        val step = 360f / count.coerceAtLeast(1)
                        val gap = if (count == 1) 0f else 4f
                        // Очень большие подборки объединяются в кольцо без исчезающих сегментов.
                        repeat(count) { i ->
                            drawArc(
                                if (if (viewed.size > 30) viewed.all { it } else viewed[i]) Color(0xFF84909F) else storyGold,
                                -90f + i * step + gap / 2, step - gap, false,
                                topLeft = Offset(stroke / 2, stroke / 2),
                                size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                                style = Stroke(stroke),
                            )
                        }
                    }
                }
                if (self) {
                    MyAvatar(
                        displayName = selfName.orEmpty().ifBlank { title },
                        modifier = Modifier.size(54.dp),
                        size = 54,
                    )
                } else {
                    PeerAvatar(name = title, avatarB64 = avatar, size = 54.dp)
                }
            }
            if (self && onAddClick != null) {
                StoryAddBadge(
                    onClick = onAddClick,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = if (self) Modifier.clickable(onClick = onClick) else Modifier,
        )
    }
}

/** Золотой объёмный плюс, выпирает из своего кружка. */
@Composable
private fun StoryAddBadge(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val view = LocalView.current
    LaunchedEffect(pressed) {
        if (pressed) view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = tween(durationMillis = 110),
        label = "story-add-press",
    )
    val lift by animateDpAsState(
        targetValue = if (pressed) 2.dp else 8.dp,
        animationSpec = tween(durationMillis = 110),
        label = "story-add-lift",
    )
    val shape = CircleShape
    Box(
        modifier
            .size(24.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .apuPremiumLift(lift, shape, ApuGold.copy(alpha = 0.55f))
            .clip(shape)
            .background(apuGoldBrush())
            .border(1.5.dp, Color.White, shape)
            .apuPremiumGloss(shape, intensity = 1.05f, topFraction = 0.7f)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Add,
            contentDescription = stringResource(R.string.story_new),
            tint = ApuGoldInk,
            modifier = Modifier.size(14.dp),
        )
    }
}

/** Полноэкранный просмотр: удержание останавливает таймер; фон приложения — тоже. */
@Composable
fun StoryViewerDialog(
    stories: List<StoryStore.Story>,
    onDismiss: () -> Unit,
    onDeleteMine: ((String) -> Unit)? = null,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { delay(1_000); now = System.currentTimeMillis() }
    }
    val activeStories = stories.filter { now - it.createdAtMs < StoryStore.LIFETIME_MS }
    if (activeStories.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val authorId = activeStories.first().authorId
    var selectedId by remember(authorId) {
        mutableStateOf(activeStories.firstOrNull { !it.mine && it.id !in StoryStore.seen.value }?.id ?: activeStories.first().id)
    }
    val index = activeStories.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val current = activeStories[index]
    var progress by remember(current.id) { mutableFloatStateOf(0f) }
    var held by remember(current.id) { mutableStateOf(false) }
    var userPaused by remember(authorId) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    val owner = LocalLifecycleOwner.current
    var resumed by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val avatars by AvatarStore.avatars.collectAsStateWithLifecycle()
    val profileName by com.vladimir.messenger.ui.theme.UsernameHolder.name.collectAsStateWithLifecycle()
    val previous: () -> Unit = {
        if (!confirmDelete) {
            if (index > 0) selectedId = activeStories[index - 1].id else progress = 0f
        }
    }
    val next: () -> Unit = {
        if (!confirmDelete) {
            if (index < activeStories.lastIndex) selectedId = activeStories[index + 1].id else onDismiss()
        }
    }
    val nextNow by rememberUpdatedState(next)
    val previousNow by rememberUpdatedState(previous)
    val dismissNow by rememberUpdatedState(onDismiss)
    val setHeldNow by rememberUpdatedState<(Boolean) -> Unit>({ held = it })
    // Не отмечаем собственные истории. Это локальный статус, не счётчик просмотров автора.
    LaunchedEffect(current.id) { if (!current.mine) StoryStore.markSeen(current.id) }
    LaunchedEffect(current.id, held, userPaused, confirmDelete, resumed) {
        if (StoryPlayback.frozen(held, userPaused, confirmDelete, resumed)) return@LaunchedEffect
        val duration = StoryPlayback.durationMs(current.text.length)
        var last = withFrameMillis { it }
        while (progress < 1f) {
            val now = withFrameMillis { it }
            progress = StoryPlayback.advance(progress, now - last, duration)
            last = now
        }
        nextNow()
    }
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp))
                .background(storyBackgrounds[current.bg.coerceIn(storyBackgrounds.indices)])) {
                // Жесты только в области контента: верхние кнопки не перекрыты прозрачным слоем.
                Box(Modifier.fillMaxSize().padding(top = 96.dp, bottom = 72.dp)
                    .pointerInput(current.id) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            setHeldNow(true)
                            val start = down.position
                            var end = start
                            var dragged = false
                            var cancelled = false
                            var releasedAt = down.uptimeMillis
                            try {
                                do {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change == null || event.changes.size > 1 || change.isConsumed) {
                                        cancelled = true
                                        break
                                    }
                                    end = change.position
                                    releasedAt = change.uptimeMillis
                                    if ((end - start).getDistance() > viewConfiguration.touchSlop) dragged = true
                                    if (dragged) change.consume()
                                } while (change.pressed)
                                val dx = end.x - start.x
                                val dy = end.y - start.y
                                when (StoryPlayback.gesture(dx, dy, dragged, cancelled, releasedAt - down.uptimeMillis,
                                    viewConfiguration.longPressTimeoutMillis, start.x, size.width, density)) {
                                    StoryPlayback.Gesture.DISMISS -> dismissNow()
                                    StoryPlayback.Gesture.NEXT -> nextNow()
                                    StoryPlayback.Gesture.PREVIOUS -> previousNow()
                                    StoryPlayback.Gesture.NONE -> Unit
                                }
                            } finally { setHeldNow(false) }
                        }
                    }, contentAlignment = Alignment.Center) {
                    Crossfade(targetState = current, label = "story-content") { story ->
                        Box(Modifier.fillMaxSize().padding(top = 96.dp, bottom = 64.dp), contentAlignment = Alignment.Center) {
                            StoryContentCanvas(story.text, story.photoB64, storyBackgrounds[story.bg.coerceIn(storyBackgrounds.indices)])
                        }
                    }
                }
                Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .4f), Color.Transparent)))
                    .padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        activeStories.forEachIndexed { i, _ ->
                            Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = .25f))) {
                                val fraction = if (i < index) 1f else if (i == index) progress else 0f
                                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Color.White))
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (current.mine) {
                            MyAvatar(
                                displayName = profileName.orEmpty().ifBlank { stringResource(R.string.story_you) },
                                modifier = Modifier.size(40.dp),
                                size = 40,
                            )
                        } else {
                            PeerAvatar(current.authorName, avatars[current.authorId], 40.dp)
                        }
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(if (current.mine) stringResource(R.string.story_you) else current.authorName,
                                color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(DateUtils.getRelativeTimeSpanString(current.createdAtMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                                color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                        IconButton(onClick = { userPaused = !userPaused }) {
                            Icon(if (userPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                stringResource(if (userPaused) R.string.story_resume else R.string.story_pause), tint = Color.White)
                        }
                        if (current.mine && onDeleteMine != null) {
                            IconButton(onClick = {
                                userPaused = true
                                pendingDeleteId = current.id
                                confirmDelete = true
                            }) {
                                Icon(Icons.Default.Delete, stringResource(R.string.story_delete), tint = Color.White)
                            }
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, stringResource(R.string.action_close), tint = Color.White)
                        }
                    }
                }
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    StoryNavigation(stringResource(R.string.story_previous), previous, Modifier.weight(1f))
                    Text("${index + 1} / ${activeStories.size}", color = Color.White.copy(alpha = .7f),
                        modifier = Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelMedium)
                    StoryNavigation(stringResource(R.string.story_next), next, Modifier.weight(1f))
                }
            }
        }
    }
    if (confirmDelete && current.mine && onDeleteMine != null) {
        ApuPremiumDialog(
            title = stringResource(R.string.story_delete_q),
            onDismiss = { confirmDelete = false },
            confirmLabel = stringResource(R.string.story_delete_confirm),
            onConfirm = {
                val id = pendingDeleteId ?: current.id
                confirmDelete = false
                pendingDeleteId = null
                onDeleteMine.invoke(id)
            },
            confirmStyle = DiagnosticsActionStyle.DANGER,
        ) {
            Text(stringResource(R.string.story_delete_body))
        }
    }
}

@Composable
private fun StoryNavigation(label: String, onClick: () -> Unit, modifier: Modifier) {
    Box(modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp))
        .background(Color.White.copy(alpha = .1f)).clickable(onClick = onClick).padding(10.dp), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
    }
}

/** Текст сразу редактируется на выбранном фоне — так же он выглядит у получателя. */
@Composable
fun StoryComposerDialog(onDismiss: () -> Unit, onPublish: (text: String, bg: Int, photoB64: String?) -> Unit) {
    var text by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var bg by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    // Do not put large photo bytes into the saved-instance Bundle.
    var photo by remember { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var photoError by remember { mutableStateOf(false) }
    // «Опубликовать без фото» - осознанный выбор человека после неудачного
    // прикрепления. Без него кнопка публикации ждёт фото: иначе история молча
    // уходила текстом, и у друзей вместо снимка была только подпись.
    var photoSkipped by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<Int?>(null) }
    var showCamera by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    fun preparePhoto(uri: Uri, cleanup: (() -> Unit)? = null) {
        scope.launch {
            preparing = true
            photoError = false
            photoSkipped = false
            cameraError = null
            try {
                photo = withContext(Dispatchers.IO) { StoryPhoto.prepare(context, uri) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { photoError = true }
            finally {
                runCatching { cleanup?.invoke() }
                preparing = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) preparePhoto(uri)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showCamera = true
        else cameraError = R.string.story_camera_permission_denied
    }
    fun requestCamera() {
        cameraError = null
        keyboardController?.hide()
        val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (hasPermission) showCamera = true
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    /**
     * Прикрепление не удалось и другого фото в редакторе нет: молча отправлять
     * историю без картинки нельзя - у друзей была бы видна только подпись.
     */
    val photoMissing = photoError && photo == null && !photoSkipped
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    BackHandler(onBack = onDismiss)
    val imeOpen = WindowInsets.ime.getBottom(density) > 0
    fun hideKeyboard() {
        keyboardController?.hide()
        focusManager.clearFocus()
    }
    fun confirmPublish() {
        hideKeyboard()
        if (!preparing && StoryPublish.canPublish(text, photo, photoMissing)) {
            onPublish(text, bg, photo)
        }
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF071426))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {},
            )
            .safeDrawingPadding(),
    ) {
            val heightDp = maxHeight.value.toInt()
            val showTools = StoryComposerLayout.showAccessoryTools(imeOpen, heightDp)
            val showCompact = StoryComposerLayout.showCompactToolRow(imeOpen, heightDp)
            Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.story_new), color = Color.White, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(start = 8.dp))
                if (imeOpen) IconButton(onClick = { hideKeyboard() }) {
                    Icon(Icons.Default.KeyboardHide, stringResource(R.string.story_hide_keyboard), tint = Color.White)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.action_close), tint = Color.White) }
            }
            // Превью занимает всё свободное место. «Опубликовать» живёт НИЖЕ
            // этого блока — клавиатура его не выталкивает за край экрана.
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .pointerInput(imeOpen) {
                        if (!imeOpen) return@pointerInput
                        detectVerticalDragGestures { _, dragAmount ->
                            if (dragAmount > 24f) hideKeyboard()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                StoryContentCanvas(
                    text = text,
                    photoB64 = photo,
                    background = storyBackgrounds[bg],
                    onTextChange = { text = StoryPublish.editorText(it) },
                )
            }
            if (showTools) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ApuPremiumButton(stringResource(R.string.story_choose_photo), { picker.launch("image/*") },
                            enabled = !preparing, compact = true, modifier = Modifier.weight(1f))
                        ApuPremiumButton(stringResource(R.string.story_take_photo), { requestCamera() },
                            enabled = !preparing, compact = true, modifier = Modifier.weight(1f))
                    }
                    if (photo != null) ApuPremiumButton(stringResource(R.string.story_remove_photo),
                        { photo = null; photoError = false; photoSkipped = false },
                        enabled = !preparing, compact = true, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            storyBackgrounds.forEachIndexed { i, brush ->
                                Box(Modifier.size(36.dp).clip(CircleShape).clickable { bg = i }.padding(4.dp)
                                    .border(if (i == bg) 2.dp else 0.dp, if (i == bg) Color.White else Color.Transparent, CircleShape)
                                    .padding(3.dp).background(brush, CircleShape)
                                    .then(Modifier.storyBackgroundSemantics(stringResource(R.string.story_background, i + 1), i == bg)))
                            }
                        }
                        Text("${text.length}/200", color = Color.White.copy(alpha = .6f), style = MaterialTheme.typography.labelSmall)
                    }
                    Text(stringResource(R.string.story_lifetime), color = Color.White.copy(alpha = .7f),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp), textAlign = TextAlign.Center)
                    if (photo != null) Text(stringResource(R.string.story_photo_needs_update),
                        color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center)
                }
            } else if (showCompact) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ApuPremiumButton(stringResource(R.string.story_choose_photo), { picker.launch("image/*") },
                        enabled = !preparing, compact = true, modifier = Modifier.weight(1f))
                    ApuPremiumButton(stringResource(R.string.story_take_photo), { requestCamera() },
                        enabled = !preparing, compact = true, modifier = Modifier.weight(1f))
                    if (photo != null) ApuPremiumButton(stringResource(R.string.story_remove_photo),
                        { photo = null; photoError = false; photoSkipped = false },
                        enabled = !preparing, compact = true, modifier = Modifier.weight(1f))
                }
                Text(
                    "${text.length}/200",
                    color = Color.White.copy(alpha = .6f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    textAlign = TextAlign.End,
                )
            } else {
                Text(
                    "${text.length}/200",
                    color = Color.White.copy(alpha = .6f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    textAlign = TextAlign.End,
                )
            }
            if (preparing) Text(stringResource(R.string.story_photo_preparing), color = Color.White,
                modifier = Modifier.padding(horizontal = 16.dp))
            if (photoError) Text(stringResource(R.string.story_photo_failed), color = Color(0xFFFF8A80),
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp))
            if (photoMissing) ApuPremiumButton(stringResource(R.string.story_publish_without_photo),
                { photoSkipped = true },
                enabled = !preparing, compact = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            cameraError?.let { Text(stringResource(it), color = Color.White, modifier = Modifier.padding(horizontal = 16.dp)) }
            // Всегда над клавиатурой и системной панелью: не внутри прокрутки.
            ApuPremiumButton(
                label = stringResource(R.string.story_publish),
                onClick = { confirmPublish() },
                enabled = !preparing && StoryPublish.canPublish(text, photo, photoMissing),
                style = DiagnosticsActionStyle.PRIMARY,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
            )
            }
        }
    if (showCamera) {
        StoryCameraDialog(
            onDismiss = { showCamera = false },
            onCaptured = { target ->
                showCamera = false
                preparePhoto(target.uri, target::delete)
            },
        )
    }
}

private fun Modifier.storyBackgroundSemantics(label: String, isSelected: Boolean): Modifier =
    semantics { contentDescription = label; selected = isSelected }

@Composable
internal fun StoryPhotoView(b64: String, modifier: Modifier, contentScale: ContentScale = ContentScale.Fit) {
    val bytes = remember(b64) { runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() }
    var fallback by remember(b64) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var coilFailed by remember(b64) { mutableStateOf(false) }
    var fallbackFailed by remember(b64) { mutableStateOf(false) }
    if (bytes != null && !coilFailed) {
        AsyncImage(model = bytes, contentDescription = stringResource(R.string.story_photo),
            modifier = modifier, contentScale = contentScale, onError = { coilFailed = true })
    }
    if (bytes == null || coilFailed) {
        // Coil не отдал картинку (или строка не разобралась): декодируем сами, чтобы
        // у получателя вместо фото не оставалось пустое место с одной подписью.
        LaunchedEffect(b64) {
            val decoded = bytes?.let { raw ->
                withContext(Dispatchers.IO) {
                    runCatching { BitmapFactory.decodeByteArray(raw, 0, raw.size) }.getOrNull()
                }
            }
            if (decoded == null) fallbackFailed = true else fallback = decoded
        }
    }
    val decoded = fallback
    if (decoded != null) {
        Image(bitmap = decoded.asImageBitmap(), contentDescription = stringResource(R.string.story_photo),
            modifier = modifier, contentScale = contentScale)
    } else if (fallbackFailed) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.story_photo_unavailable), color = Color.White.copy(alpha = .85f),
                textAlign = TextAlign.Center, fontSize = 18.sp)
        }
    }
}
