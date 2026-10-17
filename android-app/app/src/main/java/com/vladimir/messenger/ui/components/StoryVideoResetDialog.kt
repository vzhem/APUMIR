package com.vladimir.messenger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.R
import com.vladimir.messenger.data.story.StoryVideoDraft

@Composable
internal fun StoryVideoResetDialog(onDismiss: () -> Unit, onReset: (StoryVideoDraft.Reset) -> Unit) {
    ApuPremiumDialog(
        title = stringResource(R.string.story_video_reset),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.story_video_reset_all),
        confirmStyle = DiagnosticsActionStyle.DANGER,
        onConfirm = { onReset(StoryVideoDraft.Reset.ALL) },
    ) {
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.story_video_reset_hint))
            val options = listOf(
                StoryVideoDraft.Reset.TRIM to R.string.story_video_reset_trim,
                StoryVideoDraft.Reset.COVER to R.string.story_video_reset_cover,
                StoryVideoDraft.Reset.AUDIO to R.string.story_video_reset_audio,
                StoryVideoDraft.Reset.CAPTION to R.string.story_video_reset_caption,
                StoryVideoDraft.Reset.SHAPE to R.string.story_video_reset_shape,
            )
            options.forEach { (setting, label) ->
                ApuTextAction(label = stringResource(label), modifier = Modifier.fillMaxWidth(),
                    onClick = { onReset(setting) })
            }
        }
    }
}
