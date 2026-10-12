package com.vladimir.messenger.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.text.format.DateUtils
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.util.Base64
import com.vladimir.messenger.data.story.StoryPhoto
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
import androidx.compose.material.icons.filled.Cameraswitch
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
    val previous: () -> Unit = { if (index > 0) selectedId = activeStories[index - 1].id else progress = 0f }
    val next: () -> Unit = { if (index < activeStories.lastIndex) selectedId = activeStories[index + 1].id else onDismiss() }
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
                            if (story.photoB64 != null) {
                                StoryPhotoView(story.photoB64, Modifier.fillMaxSize())
                                if (story.text.isNotBlank()) Text(story.text, color = Color.White,
                                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                        .background(Color.Black.copy(alpha = .55f), RoundedCornerShape(16.dp)).padding(12.dp),
                                    textAlign = TextAlign.Center, fontSize = 20.sp)
                            } else Text(story.text, color = Color.White, fontSize = if (story.text.length > 120) 25.sp else 32.sp,
                                lineHeight = if (story.text.length > 120) 34.sp else 42.sp,
                                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
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
                    Text("${index + 1} / ${activeStories.size}", color = Color.White.copy(alpha = .7f),
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
                if (photo != null) StoryPhotoView(photo!!, Modifier.fillMaxSize())
                BasicTextField(value = text, onValueChange = { text = it.take(200) }, modifier = Modifier.fillMaxWidth()
                        .align(if (photo != null) Alignment.BottomCenter else Alignment.Center)
                        .background(if (photo != null) Color.Black.copy(alpha = .5f) else Color.Transparent, RoundedCornerShape(16.dp))
                        .padding(8.dp),
                    textStyle = TextStyle(color = Color.White, fontSize = if (text.length > 120) 25.sp else 32.sp,
                        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center), cursorBrush = SolidColor(storyGold),
                    decorationBox = { inner ->
                        if (text.isEmpty()) Text(stringResource(if (photo != null) R.string.story_caption else R.string.story_placeholder), color = Color.White.copy(alpha = .6f),
                            fontSize = 26.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        inner()
                    })
            }
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ApuPremiumButton(stringResource(R.string.story_choose_photo), { picker.launch("image/*") },
                        enabled = !preparing, compact = true, modifier = Modifier.weight(1f))
                    ApuPremiumButton(stringResource(R.string.story_take_photo), { requestCamera() },
                        enabled = !preparing, compact = true, modifier = Modifier.weight(1f))
                }
                if (photo != null) ApuPremiumButton(stringResource(R.string.story_remove_photo),
                    { photo = null; photoError = false; photoSkipped = false },
                    enabled = !preparing, compact = true, modifier = Modifier.fillMaxWidth())
                if (preparing) Text(stringResource(R.string.story_photo_preparing), color = Color.White)
                if (photoError) Text(stringResource(R.string.story_photo_failed), color = Color(0xFFFF8A80),
                    fontWeight = FontWeight.SemiBold)
                // Прикрепление не удалось, снимка у истории нет: показываем это
                // отдельно и не даём опубликовать молча - человек должен сам
                // решить, отправлять ли историю без фото.
                if (photoMissing) ApuPremiumButton(stringResource(R.string.story_publish_without_photo),
                    { photoSkipped = true },
                    enabled = !preparing, compact = true, modifier = Modifier.fillMaxWidth())
                cameraError?.let { Text(stringResource(it), color = Color.White) }
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
                ApuPremiumButton(label = stringResource(R.string.story_publish), onClick = { onPublish(text.trim(), bg, photo) },
                    enabled = !preparing && StoryPublish.canPublish(text, photo, photoMissing),
                    style = DiagnosticsActionStyle.PRIMARY, modifier = Modifier.fillMaxWidth())
            }
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

@Composable
private fun StoryCameraDialog(
    onDismiss: () -> Unit,
    onCaptured: (StoryPhoto.CaptureTarget) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember(context) { ProcessCameraProvider.getInstance(context) }
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var lensFacing by androidx.compose.runtime.saveable.rememberSaveable {
        mutableIntStateOf(CameraSelector.LENS_FACING_BACK)
    }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraReady by remember { mutableStateOf(false) }
    var canSwitchCamera by remember { mutableStateOf(false) }
    var isTakingPhoto by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<Int?>(null) }

    DisposableEffect(cameraProviderFuture, lifecycleOwner, lensFacing, previewView) {
        var active = true
        var providerForCleanup: ProcessCameraProvider? = null
        imageCapture = null
        cameraReady = false
        canSwitchCamera = false
        cameraError = null

        val listener = Runnable {
            if (active) {
                try {
                    val provider = cameraProviderFuture.get()
                    providerForCleanup = provider
                    if (active) {
                        val selector = CameraSelector.Builder()
                            .requireLensFacing(lensFacing)
                            .build()
                        if (!provider.hasCamera(selector)) {
                            val fallbackFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                CameraSelector.LENS_FACING_FRONT
                            } else {
                                CameraSelector.LENS_FACING_BACK
                            }
                            val fallbackSelector = CameraSelector.Builder()
                                .requireLensFacing(fallbackFacing)
                                .build()
                            if (provider.hasCamera(fallbackSelector)) {
                                lensFacing = fallbackFacing
                            } else {
                                cameraError = R.string.story_camera_unavailable
                            }
                        } else {
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            val capture = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
                                .build()
                            provider.unbindAll()
                            if (active) {
                                provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
                                imageCapture = capture
                                cameraReady = true
                                val otherFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                    CameraSelector.LENS_FACING_FRONT
                                } else {
                                    CameraSelector.LENS_FACING_BACK
                                }
                                canSwitchCamera = runCatching {
                                    provider.hasCamera(
                                        CameraSelector.Builder().requireLensFacing(otherFacing).build(),
                                    )
                                }.getOrDefault(false)
                            }
                        }
                    }
                } catch (_: Exception) {
                    if (active) {
                        imageCapture = null
                        cameraReady = false
                        canSwitchCamera = false
                        cameraError = R.string.story_camera_unavailable
                    }
                }
            }
        }
        cameraProviderFuture.addListener(listener, mainExecutor)

        onDispose {
            active = false
            imageCapture = null
            cameraReady = false
            canSwitchCamera = false
            providerForCleanup?.unbindAll()
        }
    }

    fun takePhoto() {
        val capture = imageCapture ?: return
        if (isTakingPhoto) return
        isTakingPhoto = true
        cameraError = null
        val target = try {
            StoryPhoto.createCaptureTarget(context)
        } catch (_: Exception) {
            isTakingPhoto = false
            cameraError = R.string.story_camera_unavailable
            return
        }
        capture.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val options = ImageCapture.OutputFileOptions.Builder(target.file).build()
        try {
            capture.takePicture(options, mainExecutor, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    isTakingPhoto = false
                    onCaptured(target)
                }

                override fun onError(exception: ImageCaptureException) {
                    target.delete()
                    isTakingPhoto = false
                    cameraError = R.string.story_camera_capture_failed
                }
            })
        } catch (_: Exception) {
            target.delete()
            isTakingPhoto = false
            cameraError = R.string.story_camera_capture_failed
        }
    }

    Dialog(
        onDismissRequest = { if (!isTakingPhoto) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss, enabled = !isTakingPhoto) {
                        Icon(Icons.Default.Close, stringResource(R.string.action_close), tint = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        stringResource(if (lensFacing == CameraSelector.LENS_FACING_BACK) R.string.story_camera_back else R.string.story_camera_front),
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    IconButton(
                        onClick = {
                            imageCapture = null
                            cameraReady = false
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                CameraSelector.LENS_FACING_FRONT
                            } else {
                                CameraSelector.LENS_FACING_BACK
                            }
                        },
                        enabled = cameraReady && canSwitchCamera && !isTakingPhoto,
                    ) {
                        Icon(Icons.Default.Cameraswitch, stringResource(R.string.story_camera_switch), tint = Color.White)
                    }
                }
                Spacer(Modifier.weight(1f))
                cameraError?.let { errorId ->
                    Text(stringResource(errorId), color = Color.White, modifier = Modifier.align(Alignment.CenterHorizontally)
                        .background(Color.Black.copy(alpha = .6f), RoundedCornerShape(12.dp)).padding(12.dp))
                }
                if (cameraError == null && !cameraReady) {
                    Text(stringResource(R.string.story_camera_starting), color = Color.White,
                        modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                ApuPremiumButton(
                    label = stringResource(if (isTakingPhoto) R.string.story_camera_capturing else R.string.story_camera_capture),
                    onClick = { takePhoto() },
                    enabled = cameraReady && !isTakingPhoto,
                    progress = isTakingPhoto,
                    style = DiagnosticsActionStyle.PRIMARY,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun Modifier.storyBackgroundSemantics(label: String, isSelected: Boolean): Modifier =
    semantics { contentDescription = label; selected = isSelected }

@Composable
private fun StoryPhotoView(b64: String, modifier: Modifier) {
    val bytes = remember(b64) { runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() }
    AsyncImage(model = bytes, contentDescription = stringResource(R.string.story_photo),
        modifier = modifier, contentScale = ContentScale.Fit)
}
