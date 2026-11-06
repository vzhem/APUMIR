package com.vladimir.messenger.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/**
 * Проигрыватель видеоаватара поверх кадра-постера: свой и чужой.
 *
 * Один на оба случая — свой в профиле (MyAvatar) и ролики собеседников везде,
 * где встречается их аватар (PeerAvatar). Копия «для чужих» расходилась бы с
 * профильной по зацикливанию, звуку и паузе в фоне: правило одно — TextureView
 * через StoryVideoSurface (SurfaceView уходит из-под круглой маски Compose и
 * видео торчит квадратом), звук выключен (аватар, а не просмотр), на уходе
 * экрана в фон плеер стоит и возобновляется при возврате, снятие с композиции
 * освобождает декодер.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun VideoAvatarPlayback(path: String, circle: Boolean, onLoadingFinished: () -> Unit = {}) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val loadingFinished by rememberUpdatedState(onLoadingFinished)
    // Ролик мог не доехать (зеркало, чистка кэша): тогда молча остаётся постер.
    var broken by remember(path) { mutableStateOf(false) }
    val player = remember(path) { ExoPlayer.Builder(context).build() }
    DisposableEffect(player, owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> player.pause()
                Lifecycle.Event.ON_START -> player.play()
                else -> Unit
            }
        }
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                loadingFinished()
            }

            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.w("VideoAvatarPlayer", "Avatar playback failed", error)
                broken = true
                loadingFinished()
            }
        }
        owner.lifecycle.addObserver(observer)
        player.addListener(listener)
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.volume = 0f
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
        player.prepare()
        player.playWhenReady = true
        onDispose {
            owner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }
    if (!broken) {
        StoryVideoSurface(player = player, circle = circle, modifier = Modifier.fillMaxSize())
    }
}
