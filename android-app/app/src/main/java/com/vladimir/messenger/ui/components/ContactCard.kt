package com.vladimir.messenger.ui.components

import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.R
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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.remember
import com.vladimir.messenger.ui.theme.AvatarStore
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.PushPin
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
    /** Оригинальное имя через собаку, например @nickname. */
    username: String = "",
    /** Подпись под именем: личный чат / группа / канал. */
    kind: BubbleKind = BubbleKind.Personal,
    /** Статус присутствия для адресной книги, например «Был(а) сегодня в 10:30». */
    presenceLabel: String? = null,
    /** Пункты меню «⋮» справа в пузыре. Пусто - кнопки нет. */
    menuActions: List<BubbleMenuAction> = emptyList(),
    /** Main-inbox marker; ContactsScreen reuses this card without the marker. */
    showPinnedIndicator: Boolean = false,
    /**
     * Собеседник сообщил ранг VIP (конверт APURANK1): рядом с именем
     * ставим знак VIP — как знак элиты в топовых мессенджерах. По умолчанию нет:
     * контакты без сообщённого ранга выглядят как раньше, без ложных знаков.
     */
    peerVip: Boolean = false,
) {
    // Присланный аватар из роевого реестра (если есть) - иначе инициалы.
    // Разбор картинки - в фоне и один раз на строку base64 (AvatarBitmaps);
    // рисует её PeerAvatar, поэтому отдельная копия битмапа тут не нужна.
    val avatars by AvatarStore.avatars.collectAsState()
    // Раунд 255: недописанный текст поля ввода виден прямо в пузыре списка.
    val drafts by com.vladimir.messenger.data.draft.DraftStore.drafts.collectAsState()
    val draftText = drafts[
        com.vladimir.messenger.data.draft.DraftStore.dmKey(chat.contactId)
    ].orEmpty()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            // Владелец 2026-10-07: «в таком стиле нужно переделать всё приложение».
            // Строка списка — та же поверхность, что шапки и пузыри: подъём,
            // единая подложка house style, золотая нить по верхней кромке и
            // глянец ПОД содержимым (имя и превью остаются чёрными и чёткими).
            .apuPremiumLift(5.dp)
            .apuBubbleSurface()
            .apuPremiumThread(inset = 16.dp)
            .apuPremiumGloss(intensity = 0.45f, topFraction = 0.55f)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ------------------------------------------------------------------
        // АВАТАР с индикатором онлайн
        // ------------------------------------------------------------------
        // Аватар - картинка из сети либо круг с инициалами; у элиты вокруг него
        // объёмное золотое кольцо с редким блеском (см. ApuVipRing.kt).
        PeerAvatar(
            name = chat.contactName,
            avatarB64 = avatars[chat.contactId],
            vip = peerVip,
            size = 52.dp,
        ) {
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
        Column(modifier = Modifier.weight(1f)) {
            // Имя контакта. Знак VIP стоит рядом с именем, а не вместо него:
            // имя должно читаться в первую очередь.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text      = chat.contactName,
                    style     = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color     = Color(0xFF1E2430),
                    maxLines  = 1,
                    overflow  = TextOverflow.Ellipsis,
                    modifier  = Modifier.weight(1f, fill = false),
                )
                if (peerVip) {
                    Spacer(Modifier.width(6.dp))
                    ApuVipBadge(compact = true)
                }
            }

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

            // В адресной книге важнее статус присутствия, чем техническая
            // подпись «личный чат». В остальных списках остаётся тип пузыря.
            Text(
                text     = presenceLabel ?: stringResource(kind.labelRes),
                style    = MaterialTheme.typography.labelSmall,
                color    = if (presenceLabel != null && chat.isContactOnline) {
                    StatusOnline
                } else {
                    Color(0xFF8A93A2)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(2.dp))

            // Превью последнего сообщения; черновик (раунд 255) - красным,
            // как в больших мессенджерах: сразу видно, что текст не отправлен.
            if (draftText.isNotEmpty()) {
                Text(
                    text     = stringResource(R.string.contact_draft_prefix, draftText),
                    style    = MaterialTheme.typography.bodySmall,
                    color    = Color(0xFFC62828),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    // Раунд 155: служебные строки (гифки/стикеры) -
                    // человеческими подписями.
                    text      = com.vladimir.messenger.util.ChatPreviews.human(chat.lastMessage)
                        ?: "Нет сообщений",
                    style     = MaterialTheme.typography.bodySmall,
                    color     = Color(0xFF5A6472),
                    maxLines  = 1,
                    overflow  = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // ------------------------------------------------------------------
        // ПРАВАЯ КОЛОНКА: время + счётчик
        // ------------------------------------------------------------------
        if (onShareClick != null) {
            IconButton(
                onClick = onShareClick,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = stringResource(R.string.menu_share_contact),
                    tint = Color(0xFF5A6472),
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.End,
        ) {
            // Время и значок закрепа находятся вместе в правом верхнем углу.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (chat.lastMessageTime != null) {
                    Text(
                        // Раунд 184 (аудит-5): кэш вместо нового SimpleDateFormat
                        // на каждую перерисовку строки списка.
                        text = remember(chat.lastMessageTime, System.currentTimeMillis() / 3_600_000L) {
                            formatChatTime(chat.lastMessageTime)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (chat.unreadCount > 0)
                            MaterialTheme.colorScheme.primary
                        else
                            Color(0xFF5A6472),
                    )
                }
                if (showPinnedIndicator && chat.isPinned) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Filled.PushPin,
                        contentDescription = stringResource(R.string.chat_pinned_desc),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Счётчик непрочитанных общий для всех списков и тем.
            ApuNotificationBadge(chat.unreadCount)
        }

        // Меню «три вертикальные точки» — крайним справа в пузыре.
        BubbleOverflowMenu(actions = menuActions)
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