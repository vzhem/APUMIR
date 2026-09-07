package com.vladimir.messenger.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Выбор области для аватара.
 *
 * Раньше приложение молча брало центр снимка. Если лицо было сбоку или сверху,
 * в круг попадало не то - поправить было нечем. Теперь картинку двигают
 * пальцем и приближают щипком, а в аватар уходит ровно то, что видно в круге.
 *
 * Работает с уже загруженным Bitmap: сюда попадают и снимок с камеры, и
 * картинка из галереи, поэтому один экран обслуживает оба случая.
 */
@Composable
fun AvatarCropDialog(
    source: Bitmap,
    onConfirm: (Bitmap) -> Unit,
    onDismiss: () -> Unit,
) {
    // Масштаб и сдвиг в долях экрана: пересчёт в пиксели картинки делается
    // один раз при подтверждении.
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var viewportPx by remember { mutableStateOf(1f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Область аватара") },
        text = {
            Column {
                Text(
                    "Двигайте фото пальцем и приближайте щипком. В аватар попадёт то, " +
                        "что видно в круге.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(CircleShape)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    // Сторона круга в пикселях: она же - сторона будущего
                    // квадрата, поэтому запоминаем для пересчёта.
                    val sidePx = with(androidx.compose.ui.platform.LocalDensity.current) {
                        maxWidth.toPx()
                    }
                    viewportPx = sidePx

                    Image(
                        bitmap = source.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offsetX,
                                translationY = offsetY,
                            )
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    // Меньше единицы не пускаем: иначе под
                                    // картинкой открывался бы чёрный фон.
                                    scale = (scale * zoom).coerceIn(1f, 5f)
                                    // Сдвиг ограничен так, чтобы край картинки
                                    // не заходил внутрь круга.
                                    val limit = sidePx * (scale - 1f) / 2f
                                    offsetX = (offsetX + pan.x).coerceIn(-limit, limit)
                                    offsetY = (offsetY + pan.y).coerceIn(-limit, limit)
                                }
                            },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cropped = cropVisibleArea(source, scale, offsetX, offsetY, viewportPx)
                onConfirm(cropped)
            }) { Text("Готово") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

/**
 * Перевести то, что видно в круге, в пиксели исходной картинки.
 *
 * На экране показан центральный квадрат снимка (ContentScale.Crop), увеличенный
 * в [scale] раз и сдвинутый на [offsetX]/[offsetY] экранных пикселей. Здесь
 * проделывается обратное преобразование.
 */
private fun cropVisibleArea(
    source: Bitmap,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    viewportPx: Float,
): Bitmap {
    if (viewportPx <= 0f) return source

    // Что попадает в квадрат предпросмотра до масштабирования.
    val baseSide = min(source.width, source.height)
    val baseLeft = (source.width - baseSide) / 2f
    val baseTop = (source.height - baseSide) / 2f

    // Приближение уменьшает видимую часть.
    val visibleSide = baseSide / scale.coerceAtLeast(1f)

    // Сдвиг экранных пикселей в пиксели картинки. Знак обратный: палец тянет
    // картинку вправо - показываем то, что было левее.
    val pxPerScreen = baseSide / (viewportPx * scale.coerceAtLeast(1f))
    val shiftX = -offsetX * pxPerScreen
    val shiftY = -offsetY * pxPerScreen

    val centerX = baseLeft + baseSide / 2f + shiftX
    val centerY = baseTop + baseSide / 2f + shiftY

    var left = (centerX - visibleSide / 2f).roundToInt()
    var top = (centerY - visibleSide / 2f).roundToInt()
    val side = visibleSide.roundToInt().coerceAtLeast(1)

    // Не выходим за границы: округление могло сдвинуть на пиксель.
    left = left.coerceIn(0, max(0, source.width - side))
    top = top.coerceIn(0, max(0, source.height - side))
    val safeSide = min(side, min(source.width - left, source.height - top)).coerceAtLeast(1)

    return runCatching {
        Bitmap.createBitmap(source, left, top, safeSide, safeSide)
    }.getOrDefault(source)
}
