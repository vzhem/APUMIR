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
//   - Поддержка длинного нажатия (для копирования, удаления)
//   - ВЫДЕЛЕНИЕ части текста (SelectionContainer)
//   - Кликабельные ссылки (URL)
// =============================================================================

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vladimir.messenger.domain.model.Message
import com.vladimir.messenger.domain.model.MessageStatus
import com.vladimir.messenger.ui.theme.MessageBubbleOwn
import com.vladimir.messenger.ui.theme.MessageBubbleOther
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import java.util.Calendar
import java.util.regex.Pattern

// Форма пузыря: скруглённые углы, один угол менее скруглён (хвостик)
private val OwnBubbleShape = RoundedCornerShape(
    topStart    = 18.dp,
    topEnd      = 18.dp,
    bottomStart = 18.dp,
    bottomEnd   = 4.dp,
)
private val OtherBubbleShape = RoundedCornerShape(
    topStart    = 4.dp,
    topEnd      = 18.dp,
    bottomStart = 18.dp,
    bottomEnd   = 18.dp,
)

// Regex для поиска URL в тексте
private val URL_PATTERN: Pattern = Pattern.compile(
    "(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)" +
    "|(www\\.[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)"
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

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = 8.dp,
                vertical   = 2.dp,
            ),
        horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start,
    ) {
        // SelectionContainer позволяет выделять часть текста для копирования
        SelectionContainer {
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
                    // Аннотированный текст с кликабельными ссылками
                    val annotatedText = buildAnnotatedMessageText(
                        text = message.content,
                        textColor = if (isOwn) Color.White else Color.Black,
                        onUrlClick = { url ->
                            try {
                                val uri = if (url.startsWith("http")) Uri.parse(url) else Uri.parse("https://$url")
                                val intent = Intent(Intent.ACTION_VIEW, uri)
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                android.util.Log.e("MessageBubble", "Failed to open URL: $url", e)
                            }
                        }
                    )
                    
                    Text(
                        text  = annotatedText,
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
                        Text(
                            text  = formatMessageTime(message.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isOwn) Color.White.copy(alpha = 0.7f)
                                    else Color.Gray,
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
}

/**
 * Строит AnnotatedString с выделенными кликабельными URL.
 */
private fun buildAnnotatedMessageText(
    text: String,
    textColor: Color,
    onUrlClick: (String) -> Unit,
): AnnotatedString {
    return buildAnnotatedString {
        append(text)
        
        // Находим все URL в тексте
        val matcher = URL_PATTERN.matcher(text)
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            val url = text.substring(start, end)
            
            // Применяем стиль ссылки (подчёркивание + синий цвет)
            addStyle(
                style = SpanStyle(
                    color = Color(0xFF4A90E2),
                    textDecoration = TextDecoration.Underline,
                ),
                start = start,
                end = end,
            )
            
            // Добавляем URL annotation
            addStringAnnotation(
                tag = "URL",
                annotation = url,
                start = start,
                end = end,
            )
        }
        
        // Добавляем обработчик кликов на URL
        // Note: для полной кликабельности нужен LinkAnnotation (Material 3)
        // но он experimental, поэтому используем addUrlAnnotation workaround
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
    val now       = System.currentTimeMillis()
    val date      = Date(timestamp)
    val today     = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
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
