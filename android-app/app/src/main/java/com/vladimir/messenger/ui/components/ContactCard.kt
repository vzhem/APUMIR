package com.vladimir.messenger.ui.components

// =============================================================================
// CONTACTCARD.KT — Карточка контакта / чата в списке
// =============================================================================
// Используется в:
//   - ChatListScreen (список чатов)
//   - ContactsScreen (список контактов)
//
// Показывает:
//   - Аватар (инициалы + цвет на основе имени)
//   - Имя
//   - Последнее сообщение (превью)
//   - Время последнего сообщения
//   - Счётчик непрочитанных
//   - Индикатор онлайн-статуса
// =============================================================================

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.remember
import com.vladimir.messenger.ui.theme.AvatarStore
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import com.vladimir.messenger.domain.model.Chat
import com.vladimir.messenger.ui.theme.StatusOnline
import com.vladimir.messenger.ui.theme.StatusOffline
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ContactCard(
    chat: Chat,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onShareClick: (() -> Unit)? = null,
    /** Оригинальное имя через собаку, например @evzhem. */
    username: String = "",
) {
    // Присланный аватар из роевого реестра (если есть) - иначе инициалы.
    val avatars by AvatarStore.avatars.collectAsState()
    val avatarBitmap = remember(avatars[chat.contactId]) {
        avatars[chat.contactId]?.let { b64 ->
            try {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                null
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFF5F7FA).copy(alpha = 0.92f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                shape = RoundedCornerShape(18.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ------------------------------------------------------------------
        // АВАТАР с индикатором онлайн
        // ------------------------------------------------------------------
        Box {
            // Аватар - картинка из сети либо круг с инициалами.
            if (avatarBitmap != null) {
                Image(
                    bitmap = avatarBitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(52.dp).clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Avatar(
                    name     = chat.contactName,
                    modifier = Modifier.size(52.dp),
                )
            }

            // Точка онлайн-статуса
            if (chat.isContactOnline) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .align(Alignment.BottomEnd)
                        .background(Color.White, CircleShape)
                        .padding(2.dp)
                        .background(StatusOnline, CircleShape)
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        // ------------------------------------------------------------------
        // ТЕКСТОВАЯ ИНФОРМАЦИЯ
        // ------------------------------------------------------------------
        if (onShareClick != null) {
            IconButton(
                onClick = onShareClick,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = "Поделиться контактом",
                    tint = Color(0xFF5A6472),
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            // Имя контакта
            Text(
                text      = chat.contactName,
                style     = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color     = Color(0xFF1E2430),
                maxLines  = 1,
                overflow  = TextOverflow.Ellipsis,
            )

            // Оригинальное имя через собаку - золотом, как акценты темы.
            if (username.isNotEmpty()) {
                Text(
                    text     = "@$username",
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Превью последнего сообщения
            Text(
                text      = chat.lastMessage ?: "Нет сообщений",
                style     = MaterialTheme.typography.bodySmall,
                color     = Color(0xFF5A6472),
                maxLines  = 1,
                overflow  = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // ------------------------------------------------------------------
        // ПРАВАЯ КОЛОНКА: время + счётчик
        // ------------------------------------------------------------------
        Column(
            horizontalAlignment = Alignment.End,
        ) {
            // Время последнего сообщения
            if (chat.lastMessageTime != null) {
                Text(
                    text  = formatChatTime(chat.lastMessageTime),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (chat.unreadCount > 0)
                        MaterialTheme.colorScheme.primary
                    else
                        Color(0xFF5A6472),
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Счётчик непрочитанных
            if (chat.unreadCount > 0) {
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
                        .background(
                            color  = MaterialTheme.colorScheme.primary,
                            shape  = CircleShape,
                        )
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text  = if (chat.unreadCount > 99) "99+" else chat.unreadCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF3A2A05),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------
// АВАТАР (инициалы + детерминированный цвет по имени)
// ------------------------------------------------------------------
@Composable
fun Avatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Int = 52,
) {
    // Детерминированный цвет на основе хэша имени
    // Один и тот же контакт всегда одного цвета
    val avatarColors = listOf(
        Color(0xFF1565C0), // синий
        Color(0xFF2E7D32), // зелёный
        Color(0xFF6A1B9A), // фиолетовый
        Color(0xFFE65100), // оранжевый
        Color(0xFF00695C), // бирюзовый
        Color(0xFFC62828), // красный
        Color(0xFF4527A0), // индиго
        Color(0xFF00838F), // циан
    )
    val colorIndex = (name.hashCode() and 0x7FFFFFFF) % avatarColors.size
    val backgroundColor = avatarColors[colorIndex]

    // Инициалы: первые буквы первого и второго слова
    val initials = name.trim().split(" ")
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString("")
        .take(2)
        .ifEmpty { "?" }

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text      = initials,
            color     = Color.White,
            fontSize  = (size * 0.35).sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// Форматирование времени для списка чатов
private fun formatChatTime(timestamp: Long): String {
    val now   = System.currentTimeMillis()
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    return when {
        timestamp > today.timeInMillis ->
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        now - timestamp < 7 * 24 * 60 * 60 * 1000L ->
            SimpleDateFormat("EEE", Locale("ru")).format(Date(timestamp))
        else ->
            SimpleDateFormat("dd.MM.yy", Locale.getDefault()).format(Date(timestamp))
    }
}