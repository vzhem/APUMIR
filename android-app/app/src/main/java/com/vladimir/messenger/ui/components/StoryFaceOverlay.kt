package com.vladimir.messenger.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import com.vladimir.messenger.data.story.StoryFaceCanvas
import com.vladimir.messenger.data.story.StoryFaceFilter
import com.vladimir.messenger.data.story.StoryFaceFrame
import com.vladimir.messenger.data.story.StoryFaceTransform
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
    Canvas(modifier.fillMaxSize()) {
        val transform = StoryFaceTransform.fillCenter(frame.width, frame.height, size.width, size.height, mirrored)
        drawStoryFaces(ComposeStoryFaceCanvas(this), frame, filter, transform)
    }
}

private class ComposeStoryFaceCanvas(private val scope: DrawScope) : StoryFaceCanvas {
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
