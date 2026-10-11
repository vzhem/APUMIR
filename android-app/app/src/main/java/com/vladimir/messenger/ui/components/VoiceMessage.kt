package com.vladimir.messenger.ui.components

import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.R
import com.vladimir.messenger.util.VoiceNote
import java.io.File
import kotlinx.coroutines.delay

/**
 * Голосовое сообщение: кнопка «играть», волна из 32 столбиков и время.
 * Волна берётся из имени файла (см. [VoiceNote]); проигрывается локальная
 * копия файла. Пока играет, сыгранная часть волны подсвечивается.
 */
@Composable
fun VoiceMessagePlayer(
    file: File,
    info: VoiceNote.Info,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    var playing by remember(file.path) { mutableStateOf(false) }
    var progress by remember(file.path) { mutableFloatStateOf(0f) }
    val player = remember(file.path) {
        runCatching {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    playing = false
                    progress = 0f
                    seekTo(0)
                }
            }
        }.getOrNull()
    }
    DisposableEffect(player) {
        onDispose { runCatching { player?.release() } }
    }
    LaunchedEffect(playing) {
        while (playing && player != null) {
            val total = player.duration.takeIf { it > 0 } ?: 1
            progress = (player.currentPosition.toFloat() / total).coerceIn(0f, 1f)
            delay(80)
        }
    }

    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ApuPremiumIconTile(
            icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = stringResource(if (playing) R.string.voice_pause else R.string.voice_play),
            size = 40.dp,
            corner = 20.dp,
            modifier = Modifier.clickable(enabled = player != null) {
                val p = player ?: return@clickable
                if (playing) {
                    runCatching { p.pause() }
                    playing = false
                } else {
                    runCatching { p.start() }
                    playing = true
                }
            },
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp),
            ) {
                val bars = VoiceNote.BARS
                val step = size.width / bars
                val barWidth = step * 0.55f
                val played = (progress * bars).toInt()
                for (i in 0 until bars) {
                    val level = info.levels.getOrElse(i) { 0 }
                    val h = (3f + level / 9f * (size.height - 3f)).coerceAtMost(size.height)
                    val x = i * step + (step - barWidth) / 2f
                    val color = if (i < played) accent else textColor.copy(alpha = 0.35f)
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(x, (size.height - h) / 2f),
                        size = Size(barWidth, h),
                        cornerRadius = CornerRadius(barWidth / 2f),
                    )
                }
            }
            Text(
                formatVoiceTime(info.durationSec),
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.7f),
            )
        }
    }
}

/** Время голоса «м:сс». */
fun formatVoiceTime(total: Int): String = "%d:%02d".format(total / 60, total % 60)

/**
 * Голосовое в карточке файла: проигрыватель вместо пиктограммы и тот же
 * значок «⋯» с действиями сообщения, что и у обычного файла.
 */
@Composable
fun VoiceFileCard(
    state: FileCardState,
    file: File,
    info: VoiceNote.Info,
    isFromMe: Boolean,
    messageActions: List<ApuAction>,
    onLongPress: (() -> Unit)?,
) {
    var showActions by remember { mutableStateOf(false) }
    val hasActions = state.onSave != null || state.onShare != null ||
        state.onFavorite != null || messageActions.isNotEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .then(
                if (onLongPress != null) {
                    Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress)
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VoiceMessagePlayer(
            file = file,
            info = info,
            textColor = ApuBubbleTextColor,
            modifier = Modifier.weight(1f),
        )
        if (hasActions) {
            Box(
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(32.dp)
                    .apuBubbleSurface(shape = CircleShape)
                    .clickable { showActions = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.sv_actions),
                    tint = ApuBubbleAccentColor,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(
                expanded = showActions,
                onDismissRequest = { showActions = false },
            ) {
                Column(
                    modifier = Modifier
                        .padding(8.dp)
                        .widthIn(min = 210.dp, max = 320.dp),
                ) {
                    state.onSave?.let { save ->
                        ApuActionBubble(stringResource(R.string.ft_save_folder), Icons.Filled.Save) {
                            showActions = false
                            save()
                        }
                    }
                    state.onShare?.let { share ->
                        ApuActionBubble(stringResource(R.string.admin_share), Icons.Filled.Share) {
                            showActions = false
                            share()
                        }
                    }
                    state.onFavorite?.let { favorite ->
                        ApuActionBubble(stringResource(R.string.chat_file_to_saved), Icons.Filled.Star) {
                            showActions = false
                            favorite()
                        }
                    }
                    messageActions.forEach { action ->
                        ApuActionBubble(
                            label = action.title,
                            icon = action.icon,
                            destructive = action.destructive,
                        ) {
                            showActions = false
                            action.onClick()
                        }
                    }
                }
            }
        }
    }
}
