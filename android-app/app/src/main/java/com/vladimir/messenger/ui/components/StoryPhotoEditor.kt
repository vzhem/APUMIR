package com.vladimir.messenger.ui.components

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vladimir.messenger.R
import com.vladimir.messenger.data.story.StoryCrop
import kotlin.math.roundToInt

/**
 * Редактор фото истории: «обрезать как хочешь, увеличить и другие функции, как в
 * топовых мессенджерах» (владелец 2026-10-15).
 *
 * На экране ровно то, что уедет друзьям: кадр рисуется той же функцией, что и
 * публикация ([StoryCrop.draw]), поэтому обрезка совпадает с предпросмотром.
 *
 * Работает с уже декодированным снимком из [com.vladimir.messenger.data.story.StoryPhoto]:
 * сюда попадают и галерея, и снимок с камеры, поэтому один экран обслуживает
 * оба случая, а второй дороги декодирования не появляется.
 */
@Composable
internal fun StoryPhotoEditorDialog(
    source: Bitmap,
    onCancel: () -> Unit,
    onApply: (StoryCrop.State) -> Unit,
) {
    val imageWidth = source.width
    val imageHeight = source.height
    var state by remember(source) { mutableStateOf(StoryCrop.initialState(imageWidth, imageHeight)) }
    val paint = remember(source) { StoryCrop.bitmapPaint() }
    val density = LocalDensity.current
    BackHandler(onBack = onCancel)

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF071426))
                .safeDrawingPadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, stringResource(R.string.action_close), tint = Color.White)
                }
                Text(
                    stringResource(R.string.story_edit_photo),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                ApuTextAction(label = stringResource(R.string.action_done), onClick = { onApply(state) })
            }
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Кадр истории 9:16 - тот же прямоугольник, что и у превью истории,
                // поэтому обрезка сразу видна в тех же пропорциях, что у друзей.
                val availableWidth = with(density) { maxWidth.toPx() }
                val availableHeight = with(density) { maxHeight.toPx() }
                val surfaceHeight = minOf(availableHeight, availableWidth / StoryCrop.STORY_ASPECT)
                val surfaceWidth = surfaceHeight * StoryCrop.STORY_ASPECT
                if (surfaceWidth > 1f && surfaceHeight > 1f) {
                    StoryCropSurface(
                        source = source,
                        state = state,
                        onState = { state = it },
                        paint = paint,
                        widthDp = with(density) { surfaceWidth.toDp() },
                        heightDp = with(density) { surfaceHeight.toDp() },
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StoryCrop.aspectPresets.forEach { id ->
                    ApuPremiumChip(
                        label = if (id == StoryCrop.ASPECT_ORIGINAL) stringResource(R.string.story_crop_original) else id,
                        selected = state.aspect == id,
                        onClick = { state = StoryCrop.withAspect(state, id, imageWidth, imageHeight) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ApuPremiumButton(
                    label = stringResource(R.string.story_crop_rotate),
                    onClick = { state = StoryCrop.withQuarter(state, imageWidth, imageHeight) },
                    icon = Icons.Default.RotateRight,
                    compact = true,
                )
                ApuPremiumButton(
                    label = stringResource(R.string.story_crop_mirror),
                    onClick = { state = StoryCrop.toggleFlipH(state, imageWidth, imageHeight) },
                    icon = Icons.Default.SwapHoriz,
                    compact = true,
                )
                ApuPremiumButton(
                    label = stringResource(R.string.story_crop_flip),
                    onClick = { state = StoryCrop.toggleFlipV(state, imageWidth, imageHeight) },
                    icon = Icons.Default.SwapVert,
                    compact = true,
                )
                ApuPremiumButton(
                    label = stringResource(R.string.story_crop_reset),
                    onClick = { state = StoryCrop.reset(imageWidth, imageHeight) },
                    icon = Icons.Default.Refresh,
                    compact = true,
                    enabled = StoryCrop.isEdited(state, imageWidth, imageHeight),
                )
            }
            Spacer(Modifier.height(4.dp))
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(
                    stringResource(R.string.story_crop_straighten_value, state.straighten.roundToInt()),
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelMedium,
                )
                ApuPremiumSlider(
                    value = state.straighten,
                    onValueChange = { state = StoryCrop.withStraighten(state, it, imageWidth, imageHeight) },
                    valueRange = -StoryCrop.STRAIGHTEN_LIMIT..StoryCrop.STRAIGHTEN_LIMIT,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                stringResource(R.string.story_crop_hint),
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * Кадр истории с рамкой обрезки.
 *
 * Пальцами двигают и приближают снимок внутри рамки, уголки тянут саму рамку.
 * Значения читаются через [rememberUpdatedState]: жесты живут дольше одной
 * перерисовки, и без этого следующее движение считалось бы от старого кадра.
 */
@Composable
private fun StoryCropSurface(
    source: Bitmap,
    state: StoryCrop.State,
    onState: (StoryCrop.State) -> Unit,
    paint: Paint,
    widthDp: Dp,
    heightDp: Dp,
) {
    val density = LocalDensity.current
    var surface by remember { mutableStateOf(IntSize.Zero) }
    val currentState by rememberUpdatedState(state)
    val currentSurface by rememberUpdatedState(surface)
    val handle = 28.dp
    val handlePx = with(density) { handle.toPx() }
    val goldEdge = with(density) { 1.5.dp.toPx() }

    val frame = state.frame
    val frameLeft = frame.left * surface.width
    val frameTop = frame.top * surface.height
    val frameWidth = (frame.right - frame.left) * surface.width
    val frameHeight = (frame.bottom - frame.top) * surface.height

    Box(
        Modifier
            .size(widthDp, heightDp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black)
            .onSizeChanged { surface = it }
            .pointerInput(source) {
                detectTransformGestures { _, pan, zoom, rotation ->
                    val now = currentState
                    val size = currentSurface
                    if (size.width <= 0 || size.height <= 0) return@detectTransformGestures
                    val area = now.frame
                    val areaWidth = (area.right - area.left) * size.width
                    val areaHeight = (area.bottom - area.top) * size.height
                    if (areaWidth <= 0f || areaHeight <= 0f) return@detectTransformGestures
                    var next = StoryCrop.panBy(now, pan.x / areaWidth, pan.y / areaHeight, source.width, source.height)
                    next = StoryCrop.zoomBy(next, zoom, source.width, source.height)
                    next = StoryCrop.withRotation(next, next.rotationDeg + rotation, source.width, source.height)
                    onState(next)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.save()
                native.translate(frameLeft, frameTop)
                native.clipRect(0f, 0f, frameWidth.coerceAtLeast(0f), frameHeight.coerceAtLeast(0f))
                StoryCrop.draw(native, source, state, frameWidth, frameHeight, paint)
                native.restore()
            }
            // Всё, что вне рамки, притемнено: видно, что именно уедет друзьям.
            val dim = Color.Black.copy(alpha = 0.55f)
            drawRect(dim, Offset(0f, 0f), Size(size.width, frameTop.coerceAtLeast(0f)))
            drawRect(
                dim,
                Offset(0f, frameTop + frameHeight),
                Size(size.width, (size.height - frameTop - frameHeight).coerceAtLeast(0f)),
            )
            drawRect(dim, Offset(0f, frameTop), Size(frameLeft.coerceAtLeast(0f), frameHeight.coerceAtLeast(0f)))
            drawRect(
                dim,
                Offset(frameLeft + frameWidth, frameTop),
                Size((size.width - frameLeft - frameWidth).coerceAtLeast(0f), frameHeight.coerceAtLeast(0f)),
            )
            // Сетка третей: подсказывает, как встанет кадр, и не мешает смотреть.
            val gridColor = Color.White.copy(alpha = 0.22f)
            for (step in 1..2) {
                val x = frameLeft + frameWidth * step / 3f
                drawLine(gridColor, Offset(x, frameTop), Offset(x, frameTop + frameHeight), strokeWidth = 1f)
                val y = frameTop + frameHeight * step / 3f
                drawLine(gridColor, Offset(frameLeft, y), Offset(frameLeft + frameWidth, y), strokeWidth = 1f)
            }
            drawRect(
                Color(0xFFD4AF37),
                Offset(frameLeft, frameTop),
                Size(frameWidth.coerceAtLeast(0f), frameHeight.coerceAtLeast(0f)),
                style = Stroke(goldEdge),
            )
        }
        for (index in 0 until 4) {
            // 0 - левый верхний, дальше по часовой стрелке.
            val cornerX = if (index == 0 || index == 3) state.frame.left else state.frame.right
            val cornerY = if (index == 0 || index == 1) state.frame.top else state.frame.bottom
            val label = stringResource(R.string.story_crop_corner, index + 1)
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        IntOffset(
                            (cornerX * surface.width - handlePx / 2f).roundToInt(),
                            (cornerY * surface.height - handlePx / 2f).roundToInt(),
                        )
                    }
                    .size(handle)
                    .semantics { contentDescription = label }
                    .pointerInput(index) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            val size = currentSurface
                            if (size.width > 0 && size.height > 0) {
                                onState(
                                    StoryCrop.withCorner(
                                        currentState, index,
                                        drag.x / size.width, drag.y / size.height,
                                        source.width, source.height,
                                    ),
                                )
                            }
                        }
                    },
            ) {
                Box(Modifier.fillMaxSize().clip(CircleShape).border(2.dp, ApuGold, CircleShape))
            }
        }
    }
}
