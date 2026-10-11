package com.vladimir.messenger.ui.components

import android.text.format.DateUtils
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
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
        // Отдельный плюс доступен и когда собственная история уже опубликована.
        item(key = "add-story") {
            Column(Modifier.width(72.dp).clickable(onClick = onAddClick), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    .border(1.dp, storyGold.copy(alpha = .5f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Add, stringResource(R.string.story_new), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.story_new), style = MaterialTheme.typography.labelSmall,
                    maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
            }
        }
        if (mine.isNotEmpty()) item(key = "mine") {
            StoryBubble(stringResource(R.string.story_mine), avatars[mine.first().authorId], mine.map { true }) {
                onOpenAuthor(mine.first().authorId)
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
private fun StoryBubble(title: String, avatar: String?, viewed: List<Boolean>, onClick: () -> Unit) {
    Column(Modifier.width(72.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val count = viewed.size.coerceAtMost(30)
                val step = 360f / count.coerceAtLeast(1)
                val gap = if (count == 1) 0f else 4f
                val stroke = 2.5.dp.toPx()
                // Очень большие подборки объединяются в кольцо без исчезающих сегментов.
                repeat(count) { i ->
                    drawArc(if (if (viewed.size > 30) viewed.all { it } else viewed[i]) Color(0xFF84909F) else storyGold,
                        -90f + i * step + gap / 2, step - gap, false,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                        style = Stroke(stroke))
                }
            }
            PeerAvatar(name = title, avatarB64 = avatar, size = 54.dp)
        }
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.labelSmall, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** Полноэкранный просмотр: удержание останавливает таймер; фон приложения — тоже. */
@Composable
fun StoryViewerDialog(stories: List<StoryStore.Story>, onDismiss: () -> Unit) {
    if (stories.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val authorId = stories.first().authorId
    var selectedId by remember(authorId) {
        mutableStateOf(stories.firstOrNull { !it.mine && it.id !in StoryStore.seen.value }?.id ?: stories.first().id)
    }
    val index = stories.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val current = stories[index]
    var progress by remember(current.id) { mutableFloatStateOf(0f) }
    var held by remember(current.id) { mutableStateOf(false) }
    var userPaused by remember(authorId) { mutableStateOf(false) }
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
    val previous: () -> Unit = { if (index > 0) selectedId = stories[index - 1].id else progress = 0f }
    val next: () -> Unit = { if (index < stories.lastIndex) selectedId = stories[index + 1].id else onDismiss() }
    val nextNow by rememberUpdatedState(next)
    val previousNow by rememberUpdatedState(previous)
    val dismissNow by rememberUpdatedState(onDismiss)
    val setHeldNow by rememberUpdatedState<(Boolean) -> Unit>({ held = it })
    // Не отмечаем собственные истории. Это локальный статус, не счётчик просмотров автора.
    LaunchedEffect(current.id) { if (!current.mine) StoryStore.markSeen(current.id) }
    LaunchedEffect(current.id, held, userPaused, resumed) {
        if (held || userPaused || !resumed) return@LaunchedEffect
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
                        Box(Modifier.fillMaxSize().padding(28.dp),
                            contentAlignment = Alignment.Center) {
                            Text(story.text, color = Color.White, fontSize = if (story.text.length > 120) 25.sp else 32.sp,
                                lineHeight = if (story.text.length > 120) 34.sp else 42.sp,
                                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        }
                    }
                }
                Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .4f), Color.Transparent)))
                    .padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        stories.forEachIndexed { i, _ ->
                            Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = .25f))) {
                                val fraction = if (i < index) 1f else if (i == index) progress else 0f
                                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Color.White))
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        PeerAvatar(if (current.mine) stringResource(R.string.story_you) else current.authorName,
                            avatars[current.authorId], 40.dp)
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
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, stringResource(R.string.action_close), tint = Color.White)
                        }
                    }
                }
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    StoryNavigation(stringResource(R.string.story_previous), previous, Modifier.weight(1f))
                    Text("${index + 1} / ${stories.size}", color = Color.White.copy(alpha = .7f),
                        modifier = Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelMedium)
                    StoryNavigation(stringResource(R.string.story_next), next, Modifier.weight(1f))
                }
            }
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
fun StoryComposerDialog(onDismiss: () -> Unit, onPublish: (text: String, bg: Int) -> Unit) {
    var text by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var bg by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF071426)).safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.story_new), color = Color.White, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(start = 8.dp))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.action_close), tint = Color.White) }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(24.dp)).background(storyBackgrounds[bg])
                .padding(24.dp).verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                BasicTextField(value = text, onValueChange = { text = it.take(200) }, modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = Color.White, fontSize = if (text.length > 120) 25.sp else 32.sp,
                        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center), cursorBrush = SolidColor(storyGold),
                    decorationBox = { inner ->
                        if (text.isEmpty()) Text(stringResource(R.string.story_placeholder), color = Color.White.copy(alpha = .6f),
                            fontSize = 26.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        inner()
                    })
            }
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        storyBackgrounds.forEachIndexed { i, brush ->
                            Box(Modifier.size(48.dp).clip(CircleShape).clickable { bg = i }.padding(6.dp)
                                .border(if (i == bg) 2.dp else 0.dp, if (i == bg) Color.White else Color.Transparent, CircleShape)
                                .padding(4.dp).background(brush, CircleShape)
                                .then(Modifier.storyBackgroundSemantics(stringResource(R.string.story_background, i + 1), i == bg)))
                        }
                    }
                    Text("${text.length}/200", color = Color.White.copy(alpha = .6f), style = MaterialTheme.typography.labelSmall)
                }
                Text(stringResource(R.string.story_lifetime), color = Color.White.copy(alpha = .7f),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp), textAlign = TextAlign.Center)
                ApuPremiumButton(label = stringResource(R.string.story_publish), onClick = { onPublish(text.trim(), bg) },
                    enabled = text.isNotBlank(), style = DiagnosticsActionStyle.PRIMARY, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private fun Modifier.storyBackgroundSemantics(label: String, isSelected: Boolean): Modifier =
    semantics { contentDescription = label; selected = isSelected }
