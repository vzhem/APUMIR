package com.vladimir.messenger.ui.components

// =============================================================================
// PHOTOVIEWER.KT — фото на весь экран: щипок увеличивает, палец двигает
// =============================================================================
// Владелец, 2026-09-09: «на фото нажимаешь - и фото становится на весь экран,
// и его можно увеличивать двумя пальцами». Один просмотрщик для всех мест,
// где рисуются картинки: посты канала, комментарии, файлы в чате, избранное.
//
// Управление: щипок - масштаб 1..6, палец - сдвиг (в границах картинки),
// двойное нажатие - 2.5× в точку нажатия и обратно, одно нажатие - показать
// или спрятать шапку, при масштабе 1 листаются соседние фото (если их
// несколько), кнопка «Назад» и крестик закрывают.
// =============================================================================

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.abs

/** Откуда просмотрщик берёт картинку: строка base64 (посты, комментарии) или файл (передачи). */
sealed class PhotoSource {
    data class Encoded(val dataB64: String) : PhotoSource()
    data class File(val path: String) : PhotoSource()
}

/**
 * Просмотр фотографий на весь экран.
 *
 * @param photos все фотографии набора (пост, запись избранного); листаются
 *   свайпом при масштабе 1.
 * @param initialIndex с какой начать - та, на которую нажали.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoViewer(
    photos: List<PhotoSource>,
    initialIndex: Int = 0,
    onDismiss: () -> Unit,
) {
    if (photos.isEmpty()) return
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, photos.size - 1),
        pageCount = { photos.size },
    )
    var chromeVisible by remember { mutableStateOf(true) }
    // Пока фото увеличено, листать пейджер нельзя: палец двигает картинку.
    var zoomed by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !zoomed,
                modifier = Modifier.fillMaxSize(),
                key = { it },
            ) { page ->
                ZoomablePhoto(
                    source = photos[page],
                    isCurrent = pagerState.currentPage == page,
                    onTap = { chromeVisible = !chromeVisible },
                    onZoomChanged = { if (pagerState.currentPage == page) zoomed = it },
                )
            }

            if (chromeVisible) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.White)
                    }
                    if (photos.size > 1) {
                        Text(
                            "${pagerState.currentPage + 1} / ${photos.size}",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Одна фотография с масштабом и сдвигом.
 *
 * Жесты разобраны вручную (не [androidx.compose.foundation.gestures.transformable]):
 * нужно, чтобы при масштабе 1 горизонтальный сдвиг уходил пейджеру, а при
 * увеличении - картинке; готовый модификатор так не умеет.
 */
@Composable
private fun ZoomablePhoto(
    source: PhotoSource,
    isCurrent: Boolean,
    onTap: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
) {
    val bitmap = when (source) {
        is PhotoSource.Encoded -> AvatarBitmaps.rememberAvatar(source.dataB64)
        is PhotoSource.File -> rememberFileBitmap(source.path)
    }
    var scale by remember(source) { mutableFloatStateOf(1f) }
    var offset by remember(source) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    // Ушли на соседнее фото - это возвращается к масштабу 1, чтобы при
    // возврате не встречать его увеличенным.
    LaunchedEffect(isCurrent) {
        if (!isCurrent) {
            scale = 1f
            offset = Offset.Zero
        }
    }
    LaunchedEffect(scale) { onZoomChanged(scale > 1.01f) }

    /** Сдвиг не выпускает картинку за край экрана; при масштабе 1 он всегда ноль. */
    fun clampOffset(candidate: Offset, currentScale: Float, bmp: Bitmap?): Offset {
        if (bmp == null || viewport == IntSize.Zero || currentScale <= 1f) return Offset.Zero
        // Размер картинки на экране при масштабе 1 (ContentScale.Fit).
        val fit = minOf(
            viewport.width.toFloat() / bmp.width,
            viewport.height.toFloat() / bmp.height,
        )
        val shownW = bmp.width * fit * currentScale
        val shownH = bmp.height * fit * currentScale
        val maxX = ((shownW - viewport.width) / 2f).coerceAtLeast(0f)
        val maxY = ((shownH - viewport.height) / 2f).coerceAtLeast(0f)
        return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
            .pointerInput(bitmap) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tapPoint ->
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            // Приближаем к точке нажатия: она остаётся под пальцем.
                            val target = DOUBLE_TAP_SCALE
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val toPoint = tapPoint - center
                            scale = target
                            offset = clampOffset(-toPoint * (target - 1f), target, bitmap)
                        }
                    },
                )
            }
            .pointerInput(bitmap) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        val twoFingers = event.changes.count { it.pressed } >= 2
                        if (twoFingers || scale > 1.01f) {
                            if (zoom != 1f) {
                                val centroid = event.calculateCentroid()
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                                // Масштабируем вокруг точки между пальцами.
                                val focus = centroid - center
                                val newOffset = (offset - focus) * (newScale / scale) + focus
                                scale = newScale
                                offset = clampOffset(newOffset + pan, newScale, bitmap)
                            } else if (pan != Offset.Zero) {
                                offset = clampOffset(offset + pan, scale, bitmap)
                            }
                            // Жест наш: пейджеру и нажатиям он не достаётся.
                            if (twoFingers || abs(pan.x) > 0f || abs(pan.y) > 0f) {
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (scale < 1.01f) {
                        scale = 1f
                        offset = Offset.Zero
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Фото",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
            )
        } else {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

/** Картинка файла в полном размере (без уменьшения при чтении), через общий кэш. */
@Composable
private fun rememberFileBitmap(path: String): Bitmap? {
    var bitmap by remember(path) { mutableStateOf(AvatarBitmaps.cachedFile(path)) }
    LaunchedEffect(path) {
        if (bitmap == null) bitmap = AvatarBitmaps.loadFile(path)
    }
    return bitmap
}

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 6f
private const val DOUBLE_TAP_SCALE = 2.5f
