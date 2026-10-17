package com.vladimir.messenger.ui.components


import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import com.vladimir.messenger.data.story.StoryFaceCanvas
import com.vladimir.messenger.data.story.StoryFaceFilter
import com.vladimir.messenger.data.story.StoryFaceFrame
import com.vladimir.messenger.data.story.StoryFaceTransform
import com.vladimir.messenger.data.story.StoryStickers
import com.vladimir.messenger.data.story.drawStoryFaces

/**
 * Живой AR-фильтр поверх предпросмотра. Рисование общее с записью файла
 * (data/story/StoryFaceArt.kt), здесь только вывод на Compose Canvas.
 */
@Composable
internal fun StoryFaceOverlay(
    frame: StoryFaceFrame?,
    filter: StoryFaceFilter,
    mirrored: Boolean,
    modifier: Modifier = Modifier,
) {
    if (frame == null || !filter.isActive) return
    AnimatedStoryFaceOverlay(frame, filter, mirrored, modifier)
}

/**
 * Часы экрана для анимации фильтра. Крутятся только пока фильтр выбран,
 * иначе кадры экрана тратились бы впустую.
 */
@Composable
private fun AnimatedStoryFaceOverlay(
    frame: StoryFaceFrame,
    filter: StoryFaceFilter,
    mirrored: Boolean,
    modifier: Modifier,
) {
    val timeMs = rememberFaceClockMs()
    val context = LocalContext.current.applicationContext
    Canvas(modifier.fillMaxSize()) {
        val transform = StoryFaceTransform.fillCenter(frame.width, frame.height, size.width, size.height, mirrored)
        drawStoryFaces(
            ComposeStoryFaceCanvas(this) { StoryStickers.bitmap(context, it) },
            frame,
            filter,
            transform,
            timeMs,
        )
    }
}

@Composable
private fun rememberFaceClockMs(): Long {
    val time = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) {
            withFrameMillis { time.longValue = it - start }
        }
    }
    return time.longValue
}

private class ComposeStoryFaceCanvas(
    private val scope: DrawScope,
    private val bitmapOf: (String) -> android.graphics.Bitmap?,
) : StoryFaceCanvas {
    override fun spriteAspect(name: String): Float? {
        val bitmap = bitmapOf(name) ?: return null
        return bitmap.height.toFloat() / bitmap.width.coerceAtLeast(1).toFloat()
    }

    override fun sprite(name: String, cx: Float, cy: Float, width: Float, flipX: Boolean, rotationDegrees: Float) {
        val bitmap = bitmapOf(name) ?: return
        val height = width * bitmap.height / bitmap.width.coerceAtLeast(1).toFloat()
        val image = bitmap.asImageBitmap()
        scope.withTransform({
            translate(left = cx - width / 2f, top = cy - height / 2f)
            if (rotationDegrees != 0f) rotate(degrees = rotationDegrees, pivot = Offset(width / 2f, height / 2f))
            if (flipX) scale(scaleX = -1f, scaleY = 1f, pivot = Offset(width / 2f, height / 2f))
        }) {
            drawImage(image, dstSize = IntSize(width.toInt().coerceAtLeast(1), height.toInt().coerceAtLeast(1)))
        }
    }

    override fun oval(cx: Float, cy: Float, w: Float, h: Float, argb: Long) {
        scope.drawOval(color = Color(argb), topLeft = Offset(cx - w / 2f, cy - h / 2f), size = Size(w, h))
    }

    override fun circle(cx: Float, cy: Float, r: Float, argb: Long) {
        scope.drawCircle(color = Color(argb), radius = r, center = Offset(cx, cy))
    }

    override fun triangle(cx: Float, cy: Float, w: Float, h: Float, tipShift: Float, argb: Long, up: Boolean) {
        val path = Path().apply {
            if (up) {
                moveTo(cx - w / 2f, cy)
                lineTo(cx + tipShift, cy - h)
                lineTo(cx + w / 2f, cy)
            } else {
                moveTo(cx - w / 2f, cy - h / 2f)
                lineTo(cx + w / 2f, cy - h / 2f)
                lineTo(cx + tipShift, cy + h / 2f)
            }
            close()
        }
        scope.drawPath(path, Color(argb))
    }

    override fun rotated(degrees: Float, cx: Float, cy: Float, block: () -> Unit) {
        scope.rotate(degrees, Offset(cx, cy)) { block() }
    }
}
