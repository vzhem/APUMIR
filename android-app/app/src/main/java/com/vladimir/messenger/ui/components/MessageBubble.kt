package com.vladimir.messenger.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.domain.model.Message
import com.vladimir.messenger.domain.model.MessageStatus
import com.vladimir.messenger.ui.theme.MessageBubbleOwn
import com.vladimir.messenger.util.ImageLinkDetector
import com.vladimir.messenger.ui.theme.MessageBubbleOther
import java.text.SimpleDateFormat
import java.util.*

private val OwnBubbleShape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
private val OtherBubbleShape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp)

private val URL_REGEX = Regex(
    """(https?://[\w\-._~:/?#\[\]@!$&'()*+,;=%]+)|(www\.[\w\-._~:/?#\[\]@!$&'()*+,;=%]+)"""
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    linkColor: Color = Color(0xFF4A90E2),
    onTap: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    val isOwn = message.isFromMe
    val context = LocalContext.current
    val textColor = if (isOwn) Color.White else Color.Black

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
                    onClick = onTap,
                    onLongClick = { onLongClick?.invoke() }
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column {
                val annotatedText = remember(message.content, linkColor) {
                    buildAnnotatedMessageText(message.content, linkColor)
                }
                // Сообщение из одной ссылки на картинку или гифку показываем
                // картинкой: клавиатура вставляет гифки именно ссылкой, и в чате
                // вместо картинки висел текст.
                val imageUrl = remember(message.content) {
                    ImageLinkDetector.directImageUrl(message.content)
                }

                if (imageUrl != null) {
                    ImagePreview(
                        model = imageUrl,
                        contentDescription = "Картинка из сообщения",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                } else if (isSelected) {
                    // Режим выделения текста
                    SelectionContainer {
                        Text(
                            text = annotatedText,
                            style = MaterialTheme.typography.bodyMedium.copy(color = textColor),
                        )
                    }
                } else {
                    // Обычный режим — ClickableText для кликабельных URL
                    ClickableText(
                        text = annotatedText,
                        style = MaterialTheme.typography.bodyMedium.copy(color = textColor),
                        onClick = { offset ->
                            val annotations = annotatedText.getStringAnnotations("URL", offset, offset)
                            if (annotations.isNotEmpty()) {
                                val url = annotations.first().item
                                try {
                                    val uri = if (url.startsWith("http")) Uri.parse(url) else Uri.parse("https://$url")
                                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                    android.util.Log.i("MessageBubble", "Opening URL: $url")
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

private fun buildAnnotatedMessageText(text: String, linkColor: Color): AnnotatedString {
    return buildAnnotatedString {
        append(text)
        URL_REGEX.findAll(text).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            addStyle(
                style = SpanStyle(
                    color = linkColor,
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
        MessageStatus.PENDING        -> Pair(Icons.Default.Schedule, Color.White.copy(alpha = 0.7f))
        MessageStatus.QUEUED_OFFLINE -> Pair(Icons.Default.Schedule, Color.White.copy(alpha = 0.5f))
        MessageStatus.SENT           -> Pair(Icons.Default.Done, Color.White.copy(alpha = 0.7f))
        MessageStatus.DELIVERED      -> Pair(Icons.Default.DoneAll, Color.White)
        MessageStatus.READ           -> Pair(Icons.Default.DoneAll, Color(0xFF66D9EF))
        MessageStatus.FAILED         -> Pair(Icons.Default.Error, Color.Red.copy(alpha = 0.8f))
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
