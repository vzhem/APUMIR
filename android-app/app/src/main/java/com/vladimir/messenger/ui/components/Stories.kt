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
import com.vladimir.messenger.data.story.StoryCrop
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
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
    // «Как в Telegram»: много историй — плотная стопка; нажатие её раздвигает,
    // и тогда любая история открывается одним касанием.
    var expanded by rememberSaveable { mutableStateOf(false) }
    val compact = StoryStripLayout.compact(byAuthor.size, expanded)
    val labelsVisible = StoryStripLayout.labelsVisible(byAuthor.size, expanded)
    val gap by animateDpAsState(
        targetValue = StoryStripLayout.gapDp(byAuthor.size, expanded).dp,
        animationSpec = tween(durationMillis = 220),
        label = "story-strip-gap",
    )
    val peerSlot by animateDpAsState(
        targetValue = StoryStripLayout.peerSlotDp(byAuthor.size, expanded).dp,
        animationSpec = tween(durationMillis = 220),
        label = "story-strip-slot",
    )
    val expandStack: () -> Unit = { expanded = true }
    val toggleStack: () -> Unit = { expanded = !expanded }
    // Вернувшись из просмотра, снова видим стопку, а не раздвинутую полосу.
    val openAuthor: (String) -> Unit = { author ->
        expanded = false
        onOpenAuthor(author)
    }
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        // Как в Telegram: свой кружок один. Плюс на нём — добавить, кружок —
        // смотреть (если история уже есть). Золотая объёмная цифра на нём —
        // сколько историй в полосе всего, и ею же стопка раздвигается.
        item(key = "mine") {
            val hasOwn = mine.isNotEmpty()
            StoryBubble(
                title = stringResource(R.string.story_mine),
                avatar = null,
                viewed = if (hasOwn) mine.map { true } else emptyList(),
                self = true,
                selfName = profileName,
                totalStories = stories.size,
                slotWidth = StoryStripLayout.SELF_SLOT_DP.dp,
                showLabel = labelsVisible,
                compact = compact,
                onExpand = expandStack,
                onCountClick = toggleStack,
                onAddClick = onAddClick,
            ) {
                if (StoryStrip.selfOpensViewer(hasOwn)) openAuthor(mine.first().authorId)
                else onAddClick()
            }
        }
        items(byAuthor, key = { it.first().authorId }) { list ->
            StoryBubble(
                title = list.last().authorName.ifBlank { "?" },
                avatar = avatars[list.first().authorId],
                viewed = list.map { it.id in seen },
                slotWidth = peerSlot,
                showLabel = labelsVisible,
                compact = compact,
                onExpand = expandStack,
            ) {
                openAuthor(list.first().authorId)
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
    /** Сколько историй сейчас в полосе — для золотой объёмной цифры. */
    totalStories: Int = 0,
    /** Ширина слота: уже кружка — соседи наезжают, полоса стоит стопкой. */
    slotWidth: Dp = StoryStripLayout.PEER_SLOT_DP.dp,
    /** Подписи видны только в раздвинутой полосе: в стопке их закрывают кружки. */
    showLabel: Boolean = true,
    /** Полоса сжата в стопку: нажатие сначала её раздвигает. */
    compact: Boolean = false,
    onExpand: () -> Unit = {},
    onCountClick: (() -> Unit)? = null,
    onAddClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val labelAlpha by animateFloatAsState(
        targetValue = if (showLabel) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "story-label",
    )
    val tap: () -> Unit = { if (StoryStrip.tapOpensViewer(compact)) onClick() else onExpand() }
    Column(
        // Ширина — слот, а не кружок: в стопке слот уже кружка, и соседи
        // наезжают друг на друга. Кружок поэтому меряется `requiredSize`:
        // иначе родитель сжал бы его до ширины слота.
        Modifier.width(slotWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.requiredSize(if (self) 72.dp else 64.dp)) {
            Box(
                Modifier
                    .requiredSize(64.dp)
                    .align(Alignment.TopStart)
                    // Нажатие на кружок: в стопке кружки перекрываются, и
                    // верхний (нарисованный позже) забирает касание — палец
                    // всегда попадает в тот кружок, который видно.
                    .clickable(onClick = tap),
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
            // Золотая объёмная цифра: сколько историй в полосе всего. Ею же
            // стопка раздвигается и сжимается обратно.
            if (self && totalStories > 0) {
                StoryCountBadge(
                    total = totalStories,
                    onClick = onCountClick,
                    modifier = Modifier.align(Alignment.BottomStart),
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
            // Подпись в стопке прячется, но место под неё остаётся: полоса не
            // прыгает вверх-вниз при раздвигании.
            modifier = Modifier
                .graphicsLayer { alpha = labelAlpha }
                .then(if (self || showLabel) Modifier.clickable(onClick = tap) else Modifier),
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

/**
 * Золотая объёмная цифра на своём кружке: сколько историй в полосе прямо
 * сейчас. Выпирает влево — зеркально плюсу, который выпирает вправо; нажатие
 * раздвигает стопку и сжимает её обратно.
 */
@Composable
private fun StoryCountBadge(total: Int, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val label = StoryStripLayout.totalLabel(total)
    val wide = label.length > 2
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val view = LocalView.current
    LaunchedEffect(pressed) {
        if (pressed) view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = tween(durationMillis = 110),
        label = "story-count-press",
    )
    val lift by animateDpAsState(
        targetValue = if (pressed) 2.dp else 8.dp,
        animationSpec = tween(durationMillis = 110),
        label = "story-count-lift",
    )
    val shape = if (wide) RoundedCornerShape(percent = 50) else CircleShape
    val description = stringResource(R.string.story_total_count, total)
    val digits = if (wide) 10.sp else 13.sp
    Box(
        modifier
            .height(24.dp)
            .widthIn(min = 24.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .apuPremiumLift(lift, shape, ApuGold.copy(alpha = 0.55f))
            .clip(shape)
            .background(apuGoldBrush())
            .border(1.5.dp, Color.White, shape)
            .apuPremiumGloss(shape, intensity = 1.1f, topFraction = 0.75f)
            .then(
                if (onClick == null) Modifier
                else Modifier.clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                ),
            )
            .padding(horizontal = if (wide) 8.dp else 0.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        // Объём: снизу светлая грань, сверху на ней чёрная цифра — как отлитая
        // из золота, в тон плюсу рядом.
        Text(
            label,
            color = ApuGoldLight,
            fontSize = digits,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            modifier = Modifier.offset(y = 1.dp),
        )
        Text(
            label,
            color = ApuGoldInk,
            fontSize = digits,
            fontWeight = FontWeight.Black,
            maxLines = 1,
        )
    }
}

/** Полноэкранный просмотр: удержание останавливает таймер; фон приложения — тоже. */
@Composable
fun StoryViewerDialog(
    stories: List<StoryStore.Story>,
    onDismiss: () -> Unit,
    onDeleteMine: ((String) -> Unit)? = null,
    /** Поставить/снять значок на истории (null - экран только смотрит). */
    onReact: ((StoryStore.Story, String) -> Unit)? = null,
    /** Написать комментарий к истории. */
    onComment: ((StoryStore.Story, String) -> Unit)? = null,
    /** История показана: экран просит у автора сводку откликов, если её нет. */
    onStoryShown: ((StoryStore.Story) -> Unit)? = null,
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
    var selectedId by rememberSaveable(authorId) {
        mutableStateOf(activeStories.firstOrNull { !it.mine && it.id !in StoryStore.seen.value }?.id ?: activeStories.first().id)
    }
    val index = activeStories.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val current = activeStories[index]
    var progress by rememberSaveable(current.id) { mutableFloatStateOf(0f) }
    var held by remember(current.id) { mutableStateOf(false) }
    var userPaused by rememberSaveable(authorId) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    // Отклики на историю: палитра значков и лента комментариев. Оба окна
    // останавливают ленту (см. ключи LaunchedEffect ниже).
    var showReactionPicker by remember { mutableStateOf(false) }
    var showComments by remember { mutableStateOf(false) }
    val feedback = rememberStoryFeedback(current.id)
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
    // Зритель, который был офлайн или открыл историю первым, просит сводку
    // откликов у автора. Просьба ограничена по частоте правилами очереди.
    LaunchedEffect(current.id) { onStoryShown?.invoke(current) }
    LaunchedEffect(current.id, held, userPaused, confirmDelete, resumed, showReactionPicker, showComments) {
        if (StoryPlayback.frozen(held || showReactionPicker || showComments, userPaused, confirmDelete, resumed)) {
            return@LaunchedEffect
        }
        if (current.video != null) return@LaunchedEffect
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
        // Диалог без платформенной ширины всё ещё может получить floating-
        // размеры темы в landscape. Явно занимаем всё окно при каждом layout
        // configuration update; чёрный фон закрывает чаты и системные поля.
        val dialogView = LocalView.current
        SideEffect {
            val window = (dialogView.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            )
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
            window.statusBarColor = android.graphics.Color.BLACK
            window.navigationBarColor = android.graphics.Color.BLACK
            WindowCompat.getInsetsController(window, dialogView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
            Column(Modifier.fillMaxSize()
                .background(storyBackgrounds[current.bg.coerceIn(storyBackgrounds.indices)])) {
                Column(Modifier.fillMaxWidth()
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
                // Жесты только в области контента: верхние кнопки не перекрыты прозрачным слоем.
                // Верх и низ измеряются отдельно; холст вписывается в оставшееся место.
                Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()
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
                    if (current.video != null) {
                        androidx.compose.runtime.key(current.authorId, current.id) {
                            StoryVideoPlayer(current,
                                paused = StoryPlayback.frozen(held || showReactionPicker || showComments, userPaused, confirmDelete, resumed),
                                onProgress = { progress = it }, onEnded = { nextNow() })
                        }
                    } else Crossfade(targetState = current, label = "story-content") { story ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            StoryContentCanvas(story.text, story.photoB64, storyBackgrounds[story.bg.coerceIn(storyBackgrounds.indices)], photoCircle = story.photoCircle)
                        }
                    }

                }
                Column(Modifier.fillMaxWidth()) {
                    // Реакции и комментарии - как в привычных мессенджерах:
                    // значки со счётчиками и вход в ленту комментариев прямо на
                    // снимке. Лента публичная, её видит каждый зритель истории.
                    StoryFeedbackBar(
                        summary = feedback,
                        onOpenPicker = { showReactionPicker = true },
                        onToggleEmoji = { emoji -> onReact?.invoke(current, emoji) },
                        onOpenComments = { showComments = true },
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        StoryNavigation(stringResource(R.string.story_previous), previous, Modifier.weight(1f))
                        Text("${index + 1} / ${activeStories.size}", color = Color.White.copy(alpha = .7f),
                            modifier = Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelMedium)
                        StoryNavigation(stringResource(R.string.story_next), next, Modifier.weight(1f))
                    }
                }
            }
        }
    }
    if (showReactionPicker) {
        // Палитра та же, что в переписке: 20 доброжелательных значков, свой
        // обведён золотом, рядом - «Убрать реакцию».
        ReactionPickerDialog(
            onDismiss = { showReactionPicker = false },
            myEmoji = feedback.myEmoji,
            onPick = { emoji ->
                showReactionPicker = false
                onReact?.invoke(current, emoji)
            },
            onRemove = {
                showReactionPicker = false
                // Повторный тап по своему значку снимает его (правило книги откликов).
                feedback.myEmoji?.let { emoji -> onReact?.invoke(current, emoji) }
            },
        )
    }
    if (showComments) {
        StoryCommentsDialog(
            summary = feedback,
            onSend = { body -> onComment?.invoke(current, body) },
            onDismiss = { showComments = false },
        )
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
internal fun StoryComposerDialog(onDismiss: () -> Unit, onPublish: (text: String, bg: Int, photoB64: String?, photoCircle: Boolean) -> Unit,
    onPublishVideo: (com.vladimir.messenger.data.story.StoryVideoExporter.Exported) -> Unit) {
    var videoComposer by remember { mutableStateOf(false) }
    if (videoComposer) StoryVideoComposer(onDismiss = { videoComposer = false }, onPublish = onPublishVideo)
    var text by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var photoCircle by rememberSaveable { mutableStateOf(false) }
    var bg by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    // Do not put large photo bytes into the saved-instance Bundle.
    var photo by remember { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var photoError by remember { mutableStateOf(false) }
    // «Опубликовать без фото» - осознанный выбор человека после неудачного
    // прикрепления. Без него кнопка публикации ждёт фото: иначе история молча
    // уходила текстом, и у друзей вместо снимка была только подпись.
    var photoSkipped by remember { mutableStateOf(false) }
    // Снимок для редактора: пока он в памяти, «Изменить фото» открывает редактор
    // снова, не перечитывая галерею. В Bundle его не кладём - это крупный объект,
    // а состояние редактора восстанавливать из saved-instance нечем.
    var editSource by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var editing by remember { mutableStateOf(false) }
    // Отдельно от photoError: «не открылось фото» и «не собрался кадр» - разные
    // подсказки, вторая не советует выбирать другое фото.
    var editFailed by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<Int?>(null) }
    var showCamera by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    /**
     * Фото выбрано или снято: сначала редактор, публикация - только после «Готово».
     *
     * Раньше снимок уходил друзьям ровно таким, каким его отдала галерея: обрезать
     * было нечем (владелец 2026-10-15). Декод большого файла идёт в IO - на главном
     * потоке редактор бы подвесился.
     */
    fun preparePhoto(uri: Uri, cleanup: (() -> Unit)? = null) {
        scope.launch {
            preparing = true
            photoError = false
            photoSkipped = false
            editFailed = false
            cameraError = null
            try {
                val loaded = withContext(Dispatchers.IO) { StoryPhoto.loadForEdit(context, uri) }
                editSource = loaded
                editing = true
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { photoError = true }
            finally {
                runCatching { cleanup?.invoke() }
                preparing = false
            }
        }
    }

    /** «Готово» в редакторе: кадр собирается и кодируется один раз - уже обрезанным. */
    fun applyPhotoEdit(crop: StoryCrop.State) {
        val loaded = editSource ?: return
        editing = false
        scope.launch {
            preparing = true
            photoError = false
            editFailed = false
            try {
                photo = withContext(Dispatchers.IO) { StoryPhoto.publishEdited(loaded, crop) }
                photoSkipped = false
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { editFailed = true }
            finally { preparing = false }
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
            onPublish(text, bg, photo, photo != null && photoCircle)
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
            if (photo != null && !imeOpen) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.story_photo_shape), color = Color.White, modifier = Modifier.weight(1f))
                    ApuPremiumChip(label = stringResource(R.string.story_photo_rectangle), selected = !photoCircle,
                        enabled = !preparing, onClick = { photoCircle = false })
                    ApuPremiumChip(label = stringResource(R.string.story_photo_circle), selected = photoCircle,
                        enabled = !preparing, onClick = { photoCircle = true })
                }
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
                    photoCircle = photoCircle,
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
                        { photo = null; editSource = null; editing = false; editFailed = false; photoError = false; photoSkipped = false },
                        enabled = !preparing, compact = true, modifier = Modifier.fillMaxWidth())
                    if (photo != null && editSource != null) ApuPremiumButton(stringResource(R.string.story_edit_photo_open),
                        { editing = true },
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
                        { photo = null; editSource = null; editing = false; editFailed = false; photoError = false; photoSkipped = false },
                        enabled = !preparing, compact = true, modifier = Modifier.weight(1f))
                }
                if (photo != null && editSource != null) ApuPremiumButton(stringResource(R.string.story_edit_photo_open),
                    { editing = true },
                    enabled = !preparing, compact = true, modifier = Modifier.fillMaxWidth())
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
            ApuPremiumButton(label = "Видеоистория · до 60 с", enabled = !preparing,
                onClick = { hideKeyboard(); videoComposer = true },
                compact = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            if (preparing) Text(stringResource(R.string.story_photo_preparing), color = Color.White,
                modifier = Modifier.padding(horizontal = 16.dp))
            if (photoError) Text(stringResource(R.string.story_photo_failed), color = Color(0xFFFF8A80),
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp))
            if (editFailed) Text(stringResource(R.string.story_photo_edit_failed), color = Color(0xFFFF8A80),
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
    val editorSource = editSource
    if (editing && editorSource != null) {
        // Отмена возвращает к прежнему фото (или к его отсутствию): уже
        // прикреплённый снимок от неудачной правки не теряется.
        StoryPhotoEditorDialog(
            source = editorSource,
            onCancel = { editing = false },
            onApply = { crop -> applyPhotoEdit(crop) },
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
