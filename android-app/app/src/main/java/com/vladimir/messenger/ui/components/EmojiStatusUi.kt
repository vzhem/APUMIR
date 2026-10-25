package com.vladimir.messenger.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vladimir.messenger.R
import com.vladimir.messenger.data.profile.EmojiStatusStore

/**
 * Значок эмодзи-статуса рядом с именем собеседника. Нет статуса — ничего не
 * рисуем: пустое место рядом с профилем хуже, чем отсутствие значка.
 */
@Composable
fun EmojiStatusBadge(nodeId: String, modifier: Modifier = Modifier) {
    val peers by EmojiStatusStore.peers.collectAsState()
    val emoji = peers[nodeId].orEmpty()
    if (emoji.isBlank()) return
    Text(
        emoji,
        fontSize = 18.sp,
        modifier = modifier,
        color = Color.Unspecified,
    )
}

/**
 * Выбор своего эмодзи-статуса: палитра из двадцати значков, свой обведён
 * золотом, снизу — убрать статус совсем.
 */
@Composable
fun EmojiStatusPickerDialog(
    current: String,
    onPick: (String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showRemoveDone by remember { mutableStateOf(false) }
    ApuSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.emoji_status_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.emoji_status_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = ApuBubbleMutedColor,
                )
                EmojiStatusStore.PALETTE.chunked(5).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        line.forEach { emoji ->
                            val isMine = emoji == current
                            Text(
                                emoji,
                                fontSize = 26.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(
                                        width = if (isMine) 1.5.dp else 0.dp,
                                        color = if (isMine) Color(0xFFD4AF37) else Color.Transparent,
                                        shape = RoundedCornerShape(12.dp),
                                    )
                                    .clickable { onPick(emoji) }
                                    .padding(horizontal = 6.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            ApuTextAction(label = stringResource(R.string.action_close), onClick = onDismiss)
        },
        dismissButton = if (current.isNotBlank()) {
            {
                ApuTextAction(
                    label = stringResource(R.string.emoji_status_remove),
                    onClick = {
                        onRemove()
                        showRemoveDone = true
                    },
                )
            }
        } else {
            null
        },
    )
    if (showRemoveDone) {
        ApuPremiumNoticeDialog(
            title = stringResource(R.string.emoji_status_default_done_title),
            message = stringResource(R.string.emoji_status_default_done_body),
            onDismiss = {
                showRemoveDone = false
                onDismiss()
            },
            icon = androidx.compose.material.icons.Icons.Default.CheckCircle,
        )
    }
}
