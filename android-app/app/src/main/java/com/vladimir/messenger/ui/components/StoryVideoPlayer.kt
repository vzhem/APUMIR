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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
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
    LaunchedEffect(story.id) {
        while (file == null) {
            file = withContext(Dispatchers.IO) { StoryVideoStorage.file(context, story) }
            if (file == null) delay(1000)
        }
    }
    val progressNow by rememberUpdatedState(onProgress)
    val endedNow by rememberUpdatedState(onEnded)
    val source = file
    if (source == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            StoryContentCanvas(story.text, story.photoB64, Color.Black)
            Text("Видео загружается. Можно перейти к следующей истории.", color = Color.White,
                modifier = Modifier.align(Alignment.TopCenter).padding(20.dp))
        }
        return
    }
    val player = remember(story.id, source) { ExoPlayer.Builder(context).build() }
    var state by remember(player) { mutableIntStateOf(Player.STATE_IDLE) }
    var error by remember(player) { mutableStateOf<String?>(null) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) { state = playbackState }
            override fun onPlayerError(failure: PlaybackException) { error = "Не удалось воспроизвести видео" }
        }
        player.addListener(listener)
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(source)))
        player.prepare()
        onDispose { player.removeListener(listener); player.release() }
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
        AndroidView(factory = { ctx -> PlayerView(ctx).apply {
            this.player = player
            useController = false
        } }, modifier = Modifier.fillMaxSize())
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
