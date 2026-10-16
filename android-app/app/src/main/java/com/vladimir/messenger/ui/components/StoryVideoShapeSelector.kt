package com.vladimir.messenger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.R

@Composable
internal fun StoryVideoShapeSelector(circle: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.story_video_shape))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ApuPremiumChip(label = stringResource(R.string.story_photo_rectangle), selected = !circle,
                enabled = enabled, onClick = { onChange(false) })
            ApuPremiumChip(label = stringResource(R.string.story_photo_circle), selected = circle,
                enabled = enabled, onClick = { onChange(true) })
        }
        Text(stringResource(R.string.story_media_circle_hint))
    }
}
