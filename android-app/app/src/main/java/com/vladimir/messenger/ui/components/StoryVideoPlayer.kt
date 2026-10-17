package com.vladimir.messenger.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.vladimir.messenger.data.story.StoryStore
import com.vladimir.messenger.data.story.StoryVideoStorage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** No text timer: only a verified local file can advance this story's playback. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun StoryVideoPlayer(story: StoryStore.Story, paused: Boolean,
    onProgress: (Float) -> Unit, onEnded: () -> Unit) {
    val context = LocalContext.current
    var file by remember(story.id) { mutableStateOf<File?>(null) }
    var unavailable by remember(story.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(story.id) {
        while (file == null) {
            file = withContext(Dispatchers.IO) { StoryVideoStorage.file(context, story) }
            unavailable = StoryVideoStorage.failure(story)
            if (file == null) delay(1000)
        }
    }
    val progressNow by rememberUpdatedState(onProgress)
    val endedNow by rememberUpdatedState(onEnded)
    val source = file
    if (source == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            StoryContentCanvas(story.text, story.photoB64, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black, Color.Black)), photoCircle = story.videoCircle)
            Text(unavailable ?: "Видео загружается. Можно перейти к следующей истории.", color = Color.White,
                modifier = Modifier.align(Alignment.TopCenter).padding(20.dp))
        }
        return
    }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val pausedNow by rememberUpdatedState(paused)
    val player = remember(story.id, source, owner) { ExoPlayer.Builder(context).build() }
    var state by remember(player) { mutableIntStateOf(Player.STATE_IDLE) }
    var error by remember(player) { mutableStateOf<String?>(null) }
    DisposableEffect(player) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || event == androidx.lifecycle.Lifecycle.Event.ON_STOP) player.pause()
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) player.playWhenReady = !pausedNow
        }
        owner.lifecycle.addObserver(observer)
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { if (!story.mine) StoryStore.markSeen(story.id) }
            override fun onPlaybackStateChanged(playbackState: Int) { state = playbackState }
            override fun onPlayerError(failure: PlaybackException) { error = "Не удалось воспроизвести видео" }
        }
        player.addListener(listener)
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(source)))
        player.prepare()
        onDispose { owner.lifecycle.removeObserver(observer); player.removeListener(listener); player.release() }
    }
    LaunchedEffect(player, paused) { player.playWhenReady = !paused }
    LaunchedEffect(player, state, paused) {
        if (state == Player.STATE_ENDED && !paused) endedNow()
    }
    LaunchedEffect(player) {
        while (true) {
            val duration = player.duration
            if (duration > 0) progressNow((player.currentPosition.toFloat() / duration).coerceIn(0f, 1f))
            delay(100)
        }
    }
    Box(Modifier.fillMaxSize()) {
        StoryVideoSurface(player, story.videoCircle, Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.BottomCenter).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state == Player.STATE_BUFFERING) Text("Буферизация…", color = Color.White)
            if (error != null) {
                Text(error.orEmpty(), color = Color.White)
                ApuPremiumButton(label = "Повторить", onClick = { error = null; player.prepare() })
            }
            Text(story.text, color = Color.White)
        }
    }
}
