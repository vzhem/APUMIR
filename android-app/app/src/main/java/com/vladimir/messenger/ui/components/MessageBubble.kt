package com.vladimir.messenger.ui.components

// =============================================================================
// MESSAGEBUBBLE.KT — Пузырь сообщения
// =============================================================================
// Визуализирует одно сообщение в чате.
//
// Особенности:
//   - Разный вид для своих/чужих сообщений
//   - Статус доставки (✓ / ✓✓)
//   - Временная метка
//   - Хвостик у пузыря (только у первого в группе от одного отправителя)
//   - Поддержка длинного нажатия (для копирования, удаления — Фаза 2)
// =============================================================================

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.domain.model.Message
import com.vladimir.messenger.domain.model.MessageChannel
import com.vladimir.messenger.domain.model.MessageStatus
import com.vladimir.messenger.ui.theme.MessageBubbleOwn
import com.vladimir.messenger.ui.theme.MessageBubbleOther
import java.text.SimpleDateFormat
import java.util.*

// Форма пузыря: скруглённые углы, один угол менее скруглён (хвостик)
private val OwnBubbleShape = RoundedCornerShape(
    topStart    = 18.dp,
    topEnd      = 18.dp,
    bottomStart = 18.dp,
    bottomEnd   = 4.dp,   // "хвостик" справа
)
private val OtherBubbleShape = RoundedCornerShape(
    topStart    = 4.dp,   // "хвостик" слева
    topEnd      = 18.dp,
    bottomStart = 18.dp,
    bottomEnd   = 18.dp,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    modifier: Modifier = Modifier,
    onLongClick: ((Message) -> Unit)? = null,
) {
    val isOwn = message.isFromMe

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = 8.dp,
                vertical   = 2.dp,
            ),
        // Свои сообщения — справа, чужие — слева
        horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(min = 80.dp, max = 280.dp)
                .clip(if (isOwn) OwnBubbleShape else OtherBubbleShape)
                .background(
                    if (isOwn) MessageBubbleOwn else MessageBubbleOther
                )
                .combinedClickable(
                    onClick      = {},
                    onLongClick  = { onLongClick?.invoke(message) }
                )
                .padding(
                    horizontal = 12.dp,
                    vertical   = 8.dp,
                )
        ) {
            Column {
                // Текст сообщения
                Text(
                    text  = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isOwn) Color.White else Color.Black,
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Нижняя строка: время + статус (только для своих)
                Row(
                    modifier              = Modifier.align(Alignment.End),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    // Временная метка
                    Text(
                        text  = formatMessageTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isOwn) Color.White.copy(alpha = 0.7f)
                                else Color.Gray,
                    )

                    // Статус доставки (только для своих сообщений)
                    if (isOwn) {
                        Spacer(modifier = Modifier.width(4.dp))
                        MessageStatusIcon(status = message.status)
                    }
                }
            }
        }
    }
}

// Иконка статуса сообщения
@Composable
private fun MessageStatusIcon(status: MessageStatus) {
    val (icon, tint) = when (status) {
        MessageStatus.PENDING   -> Pair(Icons.Default.Schedule, Color.White.copy(alpha = 0.7f))
        MessageStatus.SENT      -> Pair(Icons.Default.Done, Color.White.copy(alpha = 0.7f))
        MessageStatus.DELIVERED -> Pair(Icons.Default.DoneAll, Color.White)
        MessageStatus.FAILED    -> Pair(Icons.Default.Error, Color.Red.copy(alpha = 0.8f))
    }
    Icon(
        imageVector        = icon,
        contentDescription = status.name,
        tint               = tint,
        modifier           = Modifier.size(14.dp),
    )
}

// Форматирование времени: "14:35" или "Вчера" или "23 мая"
private fun formatMessageTime(timestamp: Long): String {
    val now     = System.currentTimeMillis()
    val date    = Date(timestamp)
    val today   = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }

    return when {
        timestamp > today.timeInMillis ->
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
        timestamp > yesterday.timeInMillis ->
            "Вчера"
        else ->
            SimpleDateFormat("d MMM", Locale("ru")).format(date)
    }
}