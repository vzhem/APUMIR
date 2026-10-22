package com.vladimir.messenger.ui.components

import android.media.MediaPlayer
import android.media.PlaybackParams
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
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.EmojiEmotions
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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vladimir.messenger.data.voice.VoicePlaybackSpeedStore
import com.vladimir.messenger.data.voice.VoiceTranscriber
import com.vladimir.messenger.util.VoiceNote
import kotlinx.coroutines.launch
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
    val context = LocalContext.current
    var playing by remember(file.path) { mutableStateOf(false) }
    var progress by remember(file.path) { mutableFloatStateOf(0f) }
    var speedMenu by remember(file.path) { mutableStateOf(false) }
    val playbackSpeed by VoicePlaybackSpeedStore.speed.collectAsStateWithLifecycle()
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
    LaunchedEffect(Unit) { VoicePlaybackSpeedStore.initialize(context) }
    LaunchedEffect(player, playbackSpeed) {
        player?.let { applyVoicePlaybackSpeed(it, playbackSpeed) }
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
                    runCatching {
                        applyVoicePlaybackSpeed(p, playbackSpeed)
                        p.start()
                        playing = true
                    }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    formatVoiceTime(info.durationSec),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.7f),
                )
                Spacer(Modifier.weight(1f))
                Box {
                    Text(
                        stringResource(voiceSpeedLabelRes(playbackSpeed)),
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        modifier = Modifier
                            .clickable { speedMenu = true }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                    DropdownMenu(
                        expanded = speedMenu,
                        onDismissRequest = { speedMenu = false },
                    ) {
                        VoicePlaybackSpeedStore.OPTIONS.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (VoicePlaybackSpeedStore.normalize(option) == 1f) {
                                                R.string.voice_speed_default
                                            } else {
                                                voiceSpeedLabelRes(option)
                                            },
                                        ),
                                    )
                                },
                                onClick = {
                                    VoicePlaybackSpeedStore.set(context, option)
                                    speedMenu = false
                                },
                            )
                        }
                    }
                }
            }
            VoiceTranscript(file = file, textColor = textColor)
        }
    }
}

/**
 * Кнопка «В текст» под голосовым: одна кнопка переводит сообщение в текст
 * прямо на телефоне (см. [VoiceTranscriber]). Работает и для входящих, и для
 * своих: проигрывателю направление не важно, у него есть только файл.
 */
@Composable
private fun VoiceTranscript(file: File, textColor: Color) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var text by remember(file.path) { mutableStateOf(VoiceTranscriber.cached(context, file)) }
    var expanded by remember(file.path) { mutableStateOf(false) }
    var stage by remember(file.path) { mutableStateOf<VoiceTranscriber.Stage?>(null) }
    var failed by remember(file.path) { mutableStateOf<VoiceTranscriber.Reason?>(null) }
    val working = stage != null

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.Subtitles,
            contentDescription = stringResource(R.string.voice_to_text),
            tint = textColor.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            when {
                working -> stringResource(
                    when (stage) {
                        VoiceTranscriber.Stage.MODEL -> R.string.voice_model_downloading
                        VoiceTranscriber.Stage.DECODE -> R.string.voice_transcribing
                        else -> R.string.voice_transcribing
                    },
                )
                text != null && !expanded -> stringResource(R.string.voice_show_text)
                text != null -> stringResource(R.string.voice_hide_text)
                else -> stringResource(R.string.voice_to_text)
            },
            style = MaterialTheme.typography.labelMedium,
            color = textColor.copy(alpha = 0.85f),
            modifier = Modifier.clickable(enabled = !working) {
                val ready = text
                if (ready != null) {
                    expanded = !expanded
                } else {
                    failed = null
                    scope.launch {
                        stage = VoiceTranscriber.Stage.DECODE
                        when (val outcome = VoiceTranscriber.transcribe(context, file) { stage = it }) {
                            is VoiceTranscriber.Outcome.Success -> {
                                text = outcome.text
                                expanded = true
                            }
                            is VoiceTranscriber.Outcome.Failure -> failed = outcome.reason
                        }
                        stage = null
                    }
                }
            },
        )
    }
    if (working) {
        Text(
            stringResource(R.string.voice_transcribe_hint),
            style = MaterialTheme.typography.labelSmall,
            color = textColor.copy(alpha = 0.6f),
        )
    }
    failed?.let { reason ->
        Text(
            stringResource(
                when (reason) {
                    VoiceTranscriber.Reason.MODEL_DOWNLOAD -> R.string.voice_model_error
                    VoiceTranscriber.Reason.TOO_LONG -> R.string.voice_too_long
                    VoiceTranscriber.Reason.EMPTY -> R.string.voice_empty
                    else -> R.string.voice_transcribe_error
                },
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (expanded && text != null) {
        Text(
            text!!,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Время голоса «м:сс». */
fun formatVoiceTime(total: Int): String = "%d:%02d".format(total / 60, total % 60)

private fun applyVoicePlaybackSpeed(player: MediaPlayer, speed: Float) {
    runCatching {
        player.setPlaybackParams(
            PlaybackParams()
                .setSpeed(VoicePlaybackSpeedStore.normalize(speed))
                // Keep the speaker's pitch natural while stretching the timing.
                .setPitch(1f),
        )
    }
}

@androidx.annotation.StringRes
private fun voiceSpeedLabelRes(speed: Float): Int = when (VoicePlaybackSpeedStore.normalize(speed)) {
    0.5f -> R.string.voice_speed_half
    0.75f -> R.string.voice_speed_three_quarters
    1.25f -> R.string.voice_speed_one_quarter_up
    1.5f -> R.string.voice_speed_one_half
    2f -> R.string.voice_speed_double
    else -> R.string.voice_speed_normal
}

/**
 * Голосовое в карточке файла (группы и каналы): проигрыватель и меню «⋯»
 * с теми же действиями, что у обычного файла.
 */
@Composable
fun VoiceFileCard(
    state: FileCardState,
    file: File,
    info: VoiceNote.Info,
    isFromMe: Boolean,
    messageActions: List<ApuAction>,
    onLongPress: (() -> Unit)?,
    /** «Поставить реакцию» в меню голосового (точки): в личке это отдельный путь. */
    onReact: (() -> Unit)? = null,
) {
    val actions = buildList {
        state.onSave?.let { save -> add(ApuAction(stringResource(R.string.ft_save_folder), Icons.Filled.Save) { save() }) }
        state.onShare?.let { share -> add(ApuAction(stringResource(R.string.admin_share), Icons.Filled.Share) { share() }) }
        state.onFavorite?.let { fav -> add(ApuAction(stringResource(R.string.chat_file_to_saved), Icons.Filled.Star) { fav() }) }
        addAll(messageActions)
    }
    VoiceActionsRow(
        file = file,
        info = info,
        textColor = ApuBubbleTextColor,
        actions = actions,
        onLongPress = onLongPress,
        onReact = onReact,
    )
}

/** Проигрыватель голоса и «⋯» с переданным списком действий. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun VoiceActionsRow(
    file: File,
    info: VoiceNote.Info,
    textColor: Color,
    actions: List<ApuAction>,
    onLongPress: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** Поставить реакцию на голосовое - первым пунктом его меню (точки). */
    onReact: (() -> Unit)? = null,
) {
    var showActions by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
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
            textColor = textColor,
            modifier = Modifier.weight(1f),
        )
        if (actions.isNotEmpty()) {
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
                    if (onReact != null) {
                    ApuActionBubble(
                        label = stringResource(R.string.chat_add_reaction),
                        icon = Icons.Filled.EmojiEmotions,
                    ) {
                        showActions = false
                        onReact()
                    }
                }
                actions.forEach { action ->
                        ApuActionBubble(
                            label = action.title,
                            icon = action.icon,
                            enabled = action.enabled,
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
