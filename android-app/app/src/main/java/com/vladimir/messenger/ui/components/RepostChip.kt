package com.vladimir.messenger.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.R

/**
 * Счётчик репостов под сообщением: стрелка «поделиться» и число пересылок.
 * Показывается только когда пересылки были — пустой счётчик шумит.
 */
@Composable
fun RepostCountChip(
    count: Int,
    modifier: Modifier = Modifier,
) {
    if (count <= 0) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(top = 2.dp, end = 4.dp),
    ) {
        Icon(
            Icons.Filled.Share,
            contentDescription = stringResource(R.string.repost_count_label),
            tint = ApuBubbleMutedColor,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = ApuBubbleMutedColor,
        )
    }
}
