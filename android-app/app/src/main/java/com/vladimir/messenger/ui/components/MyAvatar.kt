package com.vladimir.messenger.ui.components

import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.R
// =============================================================================
// MYAVATAR.KT
// =============================================================================
// Мой аватар: видеоаватар (короткий ролик по кругу, без звука), картинка из
// галереи (AvatarHolder) либо, если ничего нет, стандартный круг с инициалами.
// Размер задаёт вызывающий экран через modifier (например, Modifier.size(96.dp)).
// =============================================================================

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.vladimir.messenger.data.profile.VideoAvatarStore
import com.vladimir.messenger.ui.theme.AvatarHolder
import java.io.File

@Composable
fun MyAvatar(
    displayName: String,
    modifier: Modifier = Modifier,
    size: Int = 52,
    /**
     * Играть видеоаватар живьём. Включают там, где свой аватар виден крупно и
     * долго: профиль, «поделиться профилем» и своя ячейка в ленте историй
     * списка чатов. По умолчанию выключено — в мелких аватарах (просмотр
     * истории, отклики) плеер крутил бы ролик впустую и сажал батарею.
     */
    playVideo: Boolean = false,
) {
    val context = LocalContext.current
    val avatarUri by AvatarHolder.uri.collectAsState()
    // Видеоаватар: ролик и форма, выбранная в редакторе (круг/обычный).
    val videoPath by VideoAvatarStore.path.collectAsState()
    val videoCircle by VideoAvatarStore.circle.collectAsState()
    // Чтение файла и разбор картинки - вне главного потока. Раньше это шло
    // прямо в отрисовке: свой аватар читался с диска на каждом показе экрана
    // настроек и на каждой смене вкладки, подвешивая рисование.
    var bitmap by remember(avatarUri) {
        mutableStateOf(AvatarBitmaps.cachedUri(avatarUri))
    }
    LaunchedEffect(avatarUri) {
        if (bitmap == null && avatarUri != null) {
            bitmap = AvatarBitmaps.loadUri(context, avatarUri)
        }
    }

    // Локальная копия: у делегата (var ... by) умного приведения к non-null
    // не бывает, поэтому обращение к .asImageBitmap() без неё не собирается.
    val shown = bitmap
    // Мой аккаунт в элите: вокруг аватарки — то же объёмное золотое кольцо, что
    // у собеседников в списке чатов, в группах и каналах. Владелец 2026-10-07:
    // «чтобы аккаунт VIP должно быть видно везде».
    val selfVip = rememberSelfVip()
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (selfVip) VipRingWidth else 0.dp)
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            // Нижний слой — кадр-постер (или инициалы). Он же остаётся, если
            // ролик пропал: на втором устройстве путь есть, а файла пока нет.
            if (shown != null) {
                Image(
                    bitmap = shown.asImageBitmap(),
                    contentDescription = stringResource(R.string.settings_avatar),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Avatar(name = displayName, modifier = Modifier.fillMaxSize(), size = size)
            }
            val video = videoPath
            if (video != null && playVideo) {
                // key: при смене видео плеер создаётся заново под новый файл.
                androidx.compose.runtime.key(video, videoCircle) {
                    VideoAvatarPlayback(path = video, circle = videoCircle)
                }
            }
        }
        if (selfVip) {
            VipRing(Modifier.matchParentSize())
        }
    }
}

/**
 * Проигрыватель видеоаватара поверх кадра-постера.
 *
 * TextureView, а не VideoView: отдельный слой SurfaceView уходит из-под
 * круглой маски Compose, и квадратное видео торчало бы за круг аватара (та же
 * причина, по которой истории рисуются через `StoryVideoSurface`). Ролик
 * зациклен и выключен по звуку — аватар, а не просмотр; на уходе экрана в фон
 * плеер останавливается и возобновляется при возврате.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoAvatarPlayback(path: String, circle: Boolean) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
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
            override fun onPlayerError(error: PlaybackException) {
                broken = true
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
