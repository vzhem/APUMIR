package com.vladimir.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vladimir.messenger.data.reaction.ReactionPalette
import com.vladimir.messenger.data.reaction.ReactionSummary

private val GoldBorder = Color(0xFFD4AF37)

/**
 * Ряд поставленных реакций под сообщением или постом.
 *
 * Свои значки обведены золотом - так сразу видно, что уже поставлено. Нажатие
 * на значок снимает или ставит его повторно, без меню.
 */
@Composable
fun ReactionRow(
    reactions: List<ReactionSummary>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (reactions.isEmpty()) return
    Row(
        modifier = modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (item in reactions) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFF1F3F7))
                    .border(
                        width = if (item.mine) 1.5.dp else 0.dp,
                        color = if (item.mine) GoldBorder else Color.Transparent,
                        shape = RoundedCornerShape(14.dp),
                    )
                    .clickable { onToggle(item.emoji) }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(item.emoji, fontSize = 14.sp)
                if (item.count > 1) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        item.count.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF404550),
                    )
                }
            }
        }
    }
}

/**
 * Пузырь выбора значка: восемь привычных реакций в одну строку.
 *
 * Уже поставленный значок обведён золотом, и рядом появляется «Убрать
 * реакцию»: менять и снимать нужно там же, где ставил, - отдельного меню для
 * этого человек не ищет.
 */
@Composable
fun ReactionPickerDialog(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    /** Значок, который этот телефон уже поставил (null - реакции ещё нет). */
    myEmoji: String? = null,
    onRemove: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Реакция") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (emoji in ReactionPalette.EMOJI) {
                    Text(
                        emoji,
                        fontSize = 26.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .border(
                                width = if (emoji == myEmoji) 1.5.dp else 0.dp,
                                color = if (emoji == myEmoji) GoldBorder else Color.Transparent,
                                shape = RoundedCornerShape(12.dp),
                            )
                            .clickable { onPick(emoji) }
                            .padding(horizontal = 3.dp, vertical = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
        dismissButton = if (myEmoji != null && onRemove != null) {
            { TextButton(onClick = onRemove) { Text("Убрать реакцию") } }
        } else {
            null
        },
    )
}
