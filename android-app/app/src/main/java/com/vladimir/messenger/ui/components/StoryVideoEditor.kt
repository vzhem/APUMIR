package com.vladimir.messenger.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.vladimir.messenger.data.story.StoryVideoDraft
import com.vladimir.messenger.data.story.StoryVideoExporter
import com.vladimir.messenger.data.story.StoryVideoFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Local editor only. onReady hands off a verified export, it does not claim network publication. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun StoryVideoEditor(
    source: StoryVideoFiles.Source,
    onDismiss: () -> Unit,
    onReady: (StoryVideoExporter.Exported) -> Unit,
    draft: StoryVideoDraft,
    onDraftChange: (StoryVideoDraft) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentDraft by rememberUpdatedState(draft)
    var resetDialog by remember { mutableStateOf(false) }
    var showResetDone by remember { mutableStateOf(false) }
    var error by remember(source.file) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    var playing by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    val player = remember(source.file, owner) { ExoPlayer.Builder(context).build() }
    DisposableEffect(player, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        owner.lifecycle.addObserver(observer)
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlayerError(playbackError: androidx.media3.common.PlaybackException) {
                error = context.getString(R.string.story_record_playback_failed, playbackError.errorCodeName)
            }
        }
        player.addListener(listener)
        onDispose {
            exportJob?.cancel()
            owner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player, draft.startMs, draft.endMs) {
        player.pause()
        player.setMediaItem(MediaItem.Builder().setUri(Uri.fromFile(source.file))
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(draft.startMs).setEndPositionMs(draft.endMs).build()).build())
        player.prepare()
    }
    LaunchedEffect(player, draft.muted) { player.volume = if (draft.muted) 0f else 1f }

    Dialog(onDismissRequest = { exportJob?.cancel(); onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ApuSettingsCard(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.story_record_title))
                StoryVideoShapeSelector(draft.circle, enabled = !busy, onChange = {
                    onDraftChange(draft.copy(circle = it))
                })
                StoryVideoSurface(player, draft.circle, Modifier.fillMaxWidth().height(300.dp))
                ApuPremiumButton(label = stringResource(
                    if (playing) R.string.story_pause else R.string.story_resume),
                    enabled = !busy, onClick = {
                        if (playing) player.pause() else {
                            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                            player.play()
                        }
                    })
                Text(stringResource(R.string.story_record_range, draft.startMs / 1000, draft.endMs / 1000))
                // Move the start while preserving an interval within the maximum duration.
                ApuPremiumSlider(value = draft.startMs.toFloat(), enabled = !busy,
                    valueRange = 0f..(source.durationMs - StoryVideoDraft.MIN_DURATION_MS).coerceAtLeast(1).toFloat(),
                    onValueChange = { value ->
                        val start = value.toLong().coerceAtMost(source.durationMs - StoryVideoDraft.MIN_DURATION_MS)
                        val end = (start + draft.durationMs).coerceAtMost(source.durationMs)
                        onDraftChange(draft.trim(start, end))
                    })
                Text(stringResource(R.string.story_record_duration, draft.durationMs / 1000.0))
                val maxDuration = minOf(StoryVideoDraft.MAX_DURATION_MS, source.durationMs - draft.startMs)
                if (maxDuration > StoryVideoDraft.MIN_DURATION_MS) {
                    ApuPremiumSlider(value = draft.durationMs.toFloat(), enabled = !busy,
                        valueRange = StoryVideoDraft.MIN_DURATION_MS.toFloat()..maxDuration.toFloat(),
                        onValueChange = { onDraftChange(draft.trim(draft.startMs, draft.startMs + it.toLong())) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.story_record_muted))
                    ApuPremiumSwitch(checked = draft.muted, enabled = !busy,
                        onCheckedChange = { onDraftChange(draft.copy(muted = it)) })
                }
                Text(stringResource(R.string.story_record_cover, (draft.coverMs - draft.startMs) / 1000.0))
                ApuPremiumSlider(value = draft.coverMs.toFloat(), enabled = !busy,
                    valueRange = draft.startMs.toFloat()..(draft.endMs - 1).toFloat(),
                    onValueChange = {
                        val changed = draft.copy(coverMs = it.toLong().coerceIn(draft.startMs, draft.endMs - 1))
                        onDraftChange(changed)
                        player.pause()
                        player.seekTo(changed.exportedCoverMs)
                    })
                ApuFormTextField(value = draft.caption, label = stringResource(R.string.story_record_caption_label),
                    singleLine = false, enabled = !busy,
                    onValueChange = { onDraftChange(draft.copy(caption = it.take(StoryVideoDraft.MAX_CAPTION_CHARS))) })
                ApuTextAction(label = stringResource(R.string.story_video_reset),
                    enabled = !busy, onClick = { player.pause(); resetDialog = true })
                error?.let { Text(it) }
                if (busy) Text(stringResource(R.string.story_record_preparing_progress, (progress * 100).toInt()))
                ApuPremiumButton(label = if (busy) stringResource(R.string.story_record_preparing_short) else stringResource(R.string.story_record_prepare),
                    enabled = !busy, onClick = {
                        player.pause()
                        busy = true
                        error = null
                        progress = 0f
                        val snapshot = draft
                        exportJob = scope.launch {
                            var result: StoryVideoExporter.Exported? = null
                            try {
                                result = StoryVideoExporter.export(context, source, snapshot) { progress = it }
                                check(result.matches(source.file, currentDraft)) { context.getString(R.string.story_record_edit_changed) }
                                onReady(result)
                                result = null // Ownership transfers to caller only after successful handoff.
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                error = failure.message ?: context.getString(R.string.story_record_prepare_failed)
                            } finally {
                                result?.delete()
                                busy = false
                            }
                        }
                    })
                ApuTextAction(label = if (busy) stringResource(R.string.story_record_cancel_prepare) else stringResource(R.string.action_close),
                    onClick = { if (busy) exportJob?.cancel() else onDismiss() })
            }
        }
    }
    if (resetDialog) StoryVideoResetDialog(onDismiss = { resetDialog = false }, onReset = { setting ->
        val changed = draft.reset(setting)
        onDraftChange(changed)
        error = null
        progress = 0f
        player.pause()
        if (setting == StoryVideoDraft.Reset.COVER) player.seekTo(0)
        resetDialog = false
        showResetDone = true
    })
    if (showResetDone) {
        ApuPremiumNoticeDialog(
            title = stringResource(R.string.story_video_reset_done_title),
            message = stringResource(R.string.story_video_reset_done_body),
            onDismiss = { showResetDone = false },
            icon = androidx.compose.material.icons.Icons.Default.CheckCircle,
        )
    }

}
