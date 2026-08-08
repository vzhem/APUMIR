package com.vladimir.messenger.ui.components

// =============================================================================
// MESSAGEBUBBLE.KT — Пузырь сообщения
// =============================================================================
// Особенности:
//   - Разный вид для своих/чужих сообщений
//   - Статус доставки (✓ / ✓✓)
//   - Временная метка
//   - Хвостик у пузыря
//   - Поддержка длинного нажатия
//   - ВЫДЕЛЕНИЕ части текста (SelectionContainer)
//   - Кликабельные ссылки (ClickableText + URL regex)
// =============================================================================

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.domain.model.Message
import com.vladimir.messenger.domain.model.MessageStatus
import com.vladimir.messenger.ui.theme.MessageBubbleOwn
import com.vladimir.messenger.ui.theme.MessageBubbleOther
import java.text.SimpleDateFormat
import java.util.*

private val OwnBubbleShape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
private val OtherBubbleShape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp)

// Regex для URL
private val URL_REGEX = Regex(
    """(https?://[\w\-._~:/?#\[\]@!$&'()*+,;=%]+)|(www\.[\w\-._~:/?#\[\]@!$&'()*+,;=%]+)"""
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    modifier: Modifier = Modifier,
    onLongClick: ((Message) -> Unit)? = null,
) {
    val isOwn = message.isFromMe
    val context = LocalContext.current
    val textColor = if (isOwn) Color.White else Color.Black
    
    // Состояние: показывать ли SelectionContainer (включается по long press)
    var isSelecting by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(min = 80.dp, max = 280.dp)
                .clip(if (isOwn) OwnBubbleShape else OtherBubbleShape)
                .background(if (isOwn) MessageBubbleOwn else MessageBubbleOther)
                .combinedClickable(
                    onClick = { 
                        // Сбросить выделение при обычном клике
                        if (isSelecting) isSelecting = false
                    },
                    onLongClick = {
                        if (isSelecting) {
                            // Если уже в режиме выделения — вызвать меню
                            onLongClick?.invoke(message)
                        } else {
                            // Первое long press — включить режим выделения
                            isSelecting = true
                        }
                    }
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column {
                // Аннотированный текст с URL
                val annotatedText = remember(message.content) {
                    buildAnnotatedMessageText(message.content, textColor)
                }
                
                if (isSelecting) {
                    // Режим выделения — стандартный SelectionContainer
                    SelectionContainer {
                        Text(
                            text = annotatedText,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    // Обычный режим — кликабельные URL
                    ClickableText(
                        text = annotatedText,
                        style = MaterialTheme.typography.bodyMedium.copy(color = textColor),
                        onClick = { offset ->
                            // Найти URL по позиции клика
                            val annotations = annotatedText.getStringAnnotations("URL", offset, offset)
                            if (annotations.isNotEmpty()) {
                                val url = annotations.first().item
                                try {
                                    val uri = if (url.startsWith("http")) Uri.parse(url) else Uri.parse("https://$url")
                                    val intent = Intent(Intent.ACTION_VIEW, uri)
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    android.util.Log.e("MessageBubble", "Failed to open URL: $url", e)
                                }
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        text = formatMessageTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isOwn) Color.White.copy(alpha = 0.7f) else Color.Gray,
                    )
                    if (isOwn) {
                        Spacer(modifier = Modifier.width(4.dp))
                        MessageStatusIcon(status = message.status)
                    }
                }
            }
        }
    }
}

private fun buildAnnotatedMessageText(text: String, textColor: Color): AnnotatedString {
    return buildAnnotatedString {
        append(text)
        URL_REGEX.findAll(text).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            addStyle(
                style = SpanStyle(
                    color = Color(0xFF4A90E2),
                    textDecoration = TextDecoration.Underline,
                ),
                start = start,
                end = end,
            )
            addStringAnnotation(
                tag = "URL",
                annotation = text.substring(start, end),
                start = start,
                end = end,
            )
        }
    }
}

@Composable
private fun MessageStatusIcon(status: MessageStatus) {
    val (icon, tint) = when (status) {
        MessageStatus.PENDING   -> Pair(Icons.Default.Schedule, Color.White.copy(alpha = 0.7f))
        MessageStatus.SENT      -> Pair(Icons.Default.Done, Color.White.copy(alpha = 0.7f))
        MessageStatus.DELIVERED -> Pair(Icons.Default.DoneAll, Color.White)
        MessageStatus.FAILED    -> Pair(Icons.Default.Error, Color.Red.copy(alpha = 0.8f))
    }
    Icon(
        imageVector = icon,
        contentDescription = status.name,
        tint = tint,
        modifier = Modifier.size(14.dp),
    )
}

private fun formatMessageTime(timestamp: Long): String {
    val date = Date(timestamp)
    val today = Calendar.getInstance().apply { 
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) 
    }
    val yesterday = Calendar.getInstance().apply { 
        add(Calendar.DAY_OF_YEAR, -1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) 
    }
    return when {
        timestamp > today.timeInMillis -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
        timestamp > yesterday.timeInMillis -> "Вчера"
        else -> SimpleDateFormat("d MMM", Locale("ru")).format(date)
    }
}
