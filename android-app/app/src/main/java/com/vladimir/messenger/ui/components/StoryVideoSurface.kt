package com.vladimir.messenger.ui.components

import android.view.LayoutInflater
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.vladimir.messenger.R

private val storyCircleOutline = object : android.view.ViewOutlineProvider() {
    override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
        outline.setOval(0, 0, view.width, view.height)
    }
}

internal fun applyStoryVideoShape(view: android.view.View, circle: Boolean) {
    view.outlineProvider = if (circle) storyCircleOutline else android.view.ViewOutlineProvider.BACKGROUND
    view.clipToOutline = circle
    view.invalidateOutline()
}

/** A presentation mask only: switching back to regular never destroys source pixels. */
@Composable
internal fun StoryVideoFrame(circle: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val frame = if (circle) Modifier.size(minOf(maxWidth, maxHeight)).clip(CircleShape)
            else Modifier.fillMaxSize()
        Box(frame, contentAlignment = Alignment.Center) { content() }
    }
}

/** TextureView is essential: a separate SurfaceView layer would escape the circular clip. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun StoryVideoSurface(player: Player, circle: Boolean, modifier: Modifier = Modifier) {
    StoryVideoFrame(circle, modifier) {
        AndroidView(factory = { context ->
            (LayoutInflater.from(context).inflate(R.layout.story_video_texture, null) as PlayerView).apply {
                this.player = player
            }
        }, update = {
            it.player = player
            applyStoryVideoShape(it, circle)
            it.resizeMode = if (circle) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
        }, modifier = Modifier.fillMaxSize())
    }
}
