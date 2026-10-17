package com.vladimir.messenger.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vladimir.messenger.data.story.StoryFaceBaker
import com.vladimir.messenger.data.story.StoryFaceFilter
import com.vladimir.messenger.data.story.StoryFaceFrame
import com.vladimir.messenger.data.story.StoryVideoDraft
import com.vladimir.messenger.data.story.StoryVideoFiles
import com.vladimir.messenger.data.story.StoryVideoRecorder
import com.vladimir.messenger.data.story.StoryEars
import com.vladimir.messenger.data.story.StoryEyes
import com.vladimir.messenger.data.story.StoryBrows
import com.vladimir.messenger.data.story.StoryCheeks
import com.vladimir.messenger.data.story.StoryLips
import com.vladimir.messenger.data.story.StoryWhiskers
import com.vladimir.messenger.data.story.StoryNose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val recordRed = Color(0xFFE5484D)

/** Какая часть лица сейчас выбирается в панели фильтров. */
private enum class FilterPart(val label: String) {
    EARS("Уши"), NOSE("Нос"), EYES("Глаза"), LIPS("Губы"), BROWS("Брови"), WHISKERS("Усы"), CHEEKS("Щёки")
}

/**
 * Экран записи видеоистории: полноэкранное превью, кнопки поверх, большой затвор
 * с кольцом прогресса 60 секунд и AR-фильтры лица. Логика записи (пауза, звук,
 * разрешения, передача файла) та же, что и раньше; меняется только вид.
 *
 * Локальный владелец файла: вызывающий получает приватный исходник и обязан удалить его после редактирования.
 */
@Composable
internal fun StoryVideoCamera(circle: Boolean = false, onDismiss: () -> Unit, onRecorded: (StoryVideoFiles.Source) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismiss)
    val recorded by rememberUpdatedState(onRecorded)
    var faceFrame by remember { mutableStateOf<StoryFaceFrame?>(null) }
    val session = remember(context, owner) { StoryVideoRecorder(context) { frame -> faceFrame = frame } }
    val preview = remember(context, owner) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var cameraAllowed by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var audio by remember { mutableStateOf(false) }
    var front by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var hasTorch by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var elapsed by remember { mutableStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var faceFilter by remember { mutableStateOf(StoryFaceFilter.NONE) }
    // Прогресс наложения фильтра после остановки записи (0..1), null — не идёт.
    var processing by remember { mutableStateOf<Float?>(null) }
    var filterPart by remember { mutableStateOf(FilterPart.EARS) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraAllowed = it
        if (!it) error = "Для записи нужен доступ к камере. Можно выбрать видео из галереи."
    }
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        audio = it
        if (!it) error = "Микрофон недоступен. Можно записать видео без звука."
    }
    DisposableEffect(session, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                session.close()
                dismiss()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            session.close()
        }
    }
    LaunchedEffect(session, cameraAllowed, front) {
        ready = false
        hasTorch = false
        torch = false
        faceFrame = null
        if (cameraAllowed) {
            try {
                hasTorch = session.bind(owner, preview, front)
                ready = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Не удалось открыть камеру"
            }
        }
    }
    // Распознавание лица работает только при выбранном фильтре: без фильтра батарея не тратится.
    LaunchedEffect(ready, faceFilter.isActive) {
        session.setFaceTracking(ready && faceFilter.isActive)
        if (!faceFilter.isActive) faceFrame = null
    }
    LaunchedEffect(ready, torch) {
        if (ready) session.setTorch(torch && !front)
    }

    fun startRecording() {
        recording = true
        paused = false
        elapsed = 0L
        error = null
        // Фильтр фиксируем на старте: смена во время записи не должна менять уже снятое видео.
        val filterForRecording = faceFilter
        try {
            session.start(audio, onElapsed = { elapsed = it }, onFinished = { result ->
                recording = false
                stopping = true
                val file = result.getOrNull()
                if (file == null) {
                    stopping = false
                    error = result.exceptionOrNull()?.message ?: "Запись не удалась"
                } else {
                    // Completion handler also runs if the scope was already cancelled.
                    var handedOff = false
                    var produced: File = file
                    scope.launch {
                        try {
                            // Накладываем фильтр на записанный файл; без фильтра он возвращается как есть.
                            val baked = StoryFaceBaker.bake(context, file, filterForRecording) { processing = it }
                            baked.message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                            produced = baked.file
                            if (produced != file) file.delete()
                            val source = withContext(Dispatchers.IO) { StoryVideoFiles.inspect(produced) }
                            recorded(source)
                            handedOff = true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.message ?: "Не удалось прочитать запись"
                        } finally {
                            stopping = false
                            processing = null
                        }
                    }.invokeOnCompletion {
                        if (!handedOff) {
                            file.delete()
                            if (produced != file) produced.delete()
                        }
                    }
                }
            })
        } catch (failure: Exception) {
            recording = false
            error = failure.message ?: "Не удалось начать запись"
        }
    }

    Dialog(onDismissRequest = { session.close(); dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val dialogView = LocalView.current
        SideEffect {
            val window = (dialogView.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        val mirrored = front
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (cameraAllowed) {
                StoryVideoFrame(circle, Modifier.fillMaxSize()) {
                    AndroidView(factory = { preview }, update = {
                        applyStoryVideoShape(it, circle)
                        it.scaleType = PreviewView.ScaleType.FILL_CENTER
                    }, modifier = Modifier.fillMaxSize())
                    StoryFaceOverlay(faceFrame, faceFilter, mirrored = mirrored, modifier = Modifier.fillMaxSize())
                }
            } else {
                Column(Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Камера выключена", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    ApuPremiumButton(label = "Разрешить камеру", onClick = {
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    })
                }
            }

            // Верхняя панель: закрыть, таймер, фонарик и переключение камеры.
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StoryCameraIconButton(Icons.Default.Close, "Закрыть", enabled = !stopping) {
                    session.close(); dismiss()
                }
                Spacer(Modifier.weight(1f))
                StoryTimerPill(elapsedMs = elapsed, recording = recording, paused = paused)
                Spacer(Modifier.weight(1f))
                if (hasTorch && !front) {
                    StoryCameraIconButton(
                        if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        if (torch) "Выключить фонарик" else "Включить фонарик",
                        enabled = ready && !stopping,
                        active = torch,
                    ) { torch = !torch }
                    Spacer(Modifier.width(8.dp))
                }
                StoryCameraIconButton(Icons.Default.Cameraswitch, "Переключить камеру",
                    enabled = cameraAllowed && !recording && !stopping) {
                    front = !front
                    error = null
                }
            }

            error?.let { message ->
                Text(
                    message,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 64.dp, start = 24.dp, end = 24.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }

            // Нижняя панель: фильтры, затвор и подписи. Фон — мягкий градиент, без плашки.
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))))
                    .navigationBarsPadding()
                    .padding(top = 40.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (cameraAllowed && !recording && !stopping) {
                    if (ready && !session.faceTrackingAvailable) {
                        Text("Фильтры лица на этом устройстве недоступны",
                            color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                    } else {
                        StoryFilterPicker(
                            filter = faceFilter,
                            part = filterPart,
                            onPart = { filterPart = it },
                            onFilter = { faceFilter = it },
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // Слева: пауза во время записи, иначе переключатель звука.
                    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                        if (recording) {
                            StoryCameraIconButton(
                                if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                if (paused) "Продолжить" else "Пауза",
                                enabled = !stopping,
                            ) {
                                if (paused) session.resume() else session.pause()
                                paused = !paused
                            }
                        } else {
                            StoryCameraIconButton(
                                if (audio) Icons.Default.Mic else Icons.Default.MicOff,
                                if (audio) "Звук включён" else "Звук выключен",
                                enabled = !recording && !stopping,
                                active = audio,
                            ) {
                                if (audio) {
                                    audio = false
                                } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    audio = true
                                } else {
                                    audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        }
                    }
                    StoryShutterButton(
                        progress = (elapsed / StoryVideoDraft.MAX_DURATION_MS.toFloat()).coerceIn(0f, 1f),
                        recording = recording,
                        enabled = if (recording) !stopping else ready && !stopping,
                        onClick = {
                            if (recording) {
                                stopping = true
                                session.stop()
                            } else {
                                startRecording()
                            }
                        },
                    )
                    // Справа — пустое место того же размера, чтобы затвор стоял точно по центру.
                    Box(Modifier.size(52.dp))
                }
                Text(
                    when {
                        stopping -> processing?.let { "Накладываем фильтр… ${(it * 100).toInt()}%" } ?: "Сохраняем…"
                        recording -> if (paused) "Пауза · нажмите кнопку, чтобы завершить" else "Нажмите, чтобы завершить"
                        !ready && cameraAllowed -> "Подготовка камеры…"
                        else -> "Видеоистория · до 60 секунд"
                    },
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelLarge,
                )
                if (!recording && !stopping) {
                    Text(
                        "Отменить",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { session.close(); dismiss() }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StoryCameraIconButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (active) ApuGold else Color.Black.copy(alpha = 0.42f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = when {
                active -> ApuGoldInk
                enabled -> Color.White
                else -> Color.White.copy(alpha = 0.4f)
            },
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun StoryTimerPill(elapsedMs: Long, recording: Boolean, paused: Boolean) {
    val seconds = (elapsedMs / 1000L).coerceAtLeast(0L)
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.42f))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (recording) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (paused) ApuGold else recordRed))
            Spacer(Modifier.width(7.dp))
        }
        Text(
            "%d:%02d / 1:00".format(seconds / 60, seconds % 60) + if (paused) " · пауза" else "",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** Большой затвор: кольцо прогресса по 60 секундам и квадрат «стоп» во время записи. */
@Composable
private fun StoryShutterButton(progress: Float, recording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(84.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = Color.White.copy(alpha = 0.35f), startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke),
            )
            if (progress > 0f) {
                drawArc(
                    color = ApuGold, startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                    topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        val inner = if (recording) 30.dp else 66.dp
        Box(
            Modifier
                .size(inner)
                .clip(if (recording) RoundedCornerShape(8.dp) else CircleShape)
                .background(
                    when {
                        recording -> recordRed
                        enabled -> Color.White
                        else -> Color.White.copy(alpha = 0.45f)
                    },
                ),
        )
    }
}

private data class FilterOption(val label: String, val selected: Boolean, val onSelect: () -> Unit)

@Composable
private fun StoryFilterPicker(
    filter: StoryFaceFilter,
    part: FilterPart,
    onPart: (FilterPart) -> Unit,
    onFilter: (StoryFaceFilter) -> Unit,
) {
    val options: List<FilterOption> = when (part) {
        FilterPart.EARS -> StoryEars.entries.map { value ->
            FilterOption(value.label, filter.ears == value) { onFilter(filter.copy(ears = value)) }
        }
        FilterPart.NOSE -> StoryNose.entries.map { value ->
            FilterOption(value.label, filter.nose == value) { onFilter(filter.copy(nose = value)) }
        }
        FilterPart.EYES -> StoryEyes.entries.map { value ->
            FilterOption(value.label, filter.eyes == value) { onFilter(filter.copy(eyes = value)) }
        }
        FilterPart.LIPS -> StoryLips.entries.map { value ->
            FilterOption(value.label, filter.lips == value) { onFilter(filter.copy(lips = value)) }
        }
        FilterPart.BROWS -> StoryBrows.entries.map { value ->
            FilterOption(value.label, filter.brows == value) { onFilter(filter.copy(brows = value)) }
        }
        FilterPart.WHISKERS -> StoryWhiskers.entries.map { value ->
            FilterOption(value.label, filter.whiskers == value) { onFilter(filter.copy(whiskers = value)) }
        }
        FilterPart.CHEEKS -> StoryCheeks.entries.map { value ->
            FilterOption(value.label, filter.cheeks == value) { onFilter(filter.copy(cheeks = value)) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Частей семь, на узком экране они не влезают в ряд: прокручиваем.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            items(FilterPart.entries) { item ->
                ApuPremiumChip(label = item.label, selected = part == item, onClick = { onPart(item) })
            }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            items(options) { option ->
                ApuPremiumChip(label = option.label, selected = option.selected, onClick = option.onSelect)
            }
        }
    }
}
