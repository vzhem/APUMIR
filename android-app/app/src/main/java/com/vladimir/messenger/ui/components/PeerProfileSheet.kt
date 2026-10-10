package com.vladimir.messenger.ui.components

import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import com.vladimir.messenger.ui.theme.AvatarStore

/** Тёплый красный: сердечко должно быть заметно и на светлой карточке. */
private val HeartColor = Color(0xFFE0245E)
/** Цвет анти-рейтинга (жалобы на профиль). */
private val AntiRatingColor = ApuSettingsDangerColor

/**
 * Карточка профиля собеседника — открывается тапом по его имени в переписке.
 *
 * Показывает то, что телефон уже знает о человеке: аватар, имя, @никнейм,
 * в сети он или нет, сердечки и анти-рейтинг, и его узел в сети. Ничего не
 * запрашивает по сети — только то, что уже лежит рядом с чатом, поэтому
 * карточка открывается мгновенно.
 *
 * Идентификатор узла можно скопировать: он нужен, чтобы позвать человека в
 * группу или разобраться, почему сообщения не идут.
 */
@Composable
fun PeerProfileSheet(
    name: String,
    contactId: String,
    isOnline: Boolean,
    onDismiss: () -> Unit,
    /** @никнейм без собаки; пусто - строки не будет. */
    username: String = "",
    /** Сколько человек отметили профиль сердечком. */
    heartCount: Int = 0,
    /** Стоит ли МОЁ сердечко: значок закрашен. */
    heartMine: Boolean = false,
    /** Нажатие на сердечко; null - показываем только счётчик. */
    onHeartClick: (() -> Unit)? = null,
    /** Сколько человек поставили профилю анти-рейтинг (жалобу). */
    antiRatingCount: Int = 0,
    /**
     * Действует ли временное предупреждение: жалоб пришло много и сразу за
     * короткое время. Одиночные отметки 👎 его не включают.
     */
    antiRatingWarning: Boolean = false,
    /** До какого времени идёт предупреждение (0 - не показываем срок). */
    antiRatingUntilMs: Long = 0,
    /** Стоит ли МОЙ анти-рейтинг этому профилю. */
    antiRatingMine: Boolean = false,
    /** Собеседник из элиты: знак VIP у имени и золотое кольцо у аватарки. */
    vip: Boolean = false,
    /** Нажатие на кнопку анти-рейтинга; null - показываем только счётчик. */
    onAntiRatingClick: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onCall: (() -> Unit)? = null,
    onCopyId: (() -> Unit)? = null,
) {
    val avatars by AvatarStore.avatars.collectAsState()

    ApuSettingsDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            ApuTextAction(label = stringResource(R.string.action_close), onClick = onDismiss)
        },
        title = null,
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Аватар: присланная картинка, иначе буквы имени на круге.
                // У элиты вокруг него объёмное золотое кольцо — то же, что в
                // списке чатов и в сообщениях групп.
                PeerAvatar(
                    name = name,
                    avatarB64 = avatars[contactId],
                    vip = vip,
                    size = 88.dp,
                    modifier = Modifier.then(
                        if (vip) {
                            Modifier
                        } else {
                            Modifier.border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                                shape = CircleShape,
                            )
                        },
                    ),
                )

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = ApuBubbleTextColor,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (vip) {
                        Spacer(Modifier.width(6.dp))
                        ApuVipBadge()
                    }
                }
                if (username.isNotBlank()) {
                    Text(
                        "@" + username.removePrefix("@"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    stringResource(if (isOnline) R.string.pps_online_short else R.string.pps_offline_short),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isOnline) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        ApuBubbleMutedColor
                    },
                )

                Spacer(Modifier.height(12.dp))

                // Сердечки (❤️) и анти-рейтинг (👎): один человек = один голос.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                if (heartMine) HeartColor.copy(alpha = 0.14f) else Color(0xFFF0EFEA),
                                RoundedCornerShape(20.dp),
                            )
                            .border(
                                width = 1.dp,
                                color = if (heartMine) HeartColor.copy(alpha = 0.45f) else ApuBubbleAccentColor.copy(alpha = 0.25f),
                                shape = RoundedCornerShape(20.dp),
                            )
                            .then(
                                if (onHeartClick != null) {
                                    Modifier.clickable(onClick = onHeartClick)
                                } else {
                                    Modifier
                                }
                            )
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            imageVector = if (heartMine) {
                                Icons.Filled.Favorite
                            } else {
                                Icons.Filled.FavoriteBorder
                            },
                            contentDescription = stringResource(if (heartMine) R.string.pps_heart_remove else R.string.pps_heart_add),
                            tint = HeartColor,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            heartCount.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = ApuBubbleTextColor,
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                if (antiRatingMine || antiRatingWarning) {
                                    AntiRatingColor.copy(alpha = 0.14f)
                                } else {
                                    Color(0xFFF0EFEA)
                                },
                                RoundedCornerShape(20.dp),
                            )
                            .border(
                                width = 1.dp,
                                color = if (antiRatingMine || antiRatingCount > 0) {
                                    AntiRatingColor.copy(alpha = 0.45f)
                                } else {
                                    ApuBubbleAccentColor.copy(alpha = 0.25f)
                                },
                                shape = RoundedCornerShape(20.dp),
                            )
                            .then(
                                if (onAntiRatingClick != null) {
                                    Modifier.clickable(onClick = onAntiRatingClick)
                                } else {
                                    Modifier
                                }
                            )
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = "\uD83D\uDC4E",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = antiRatingCount.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (antiRatingMine || antiRatingCount > 0) {
                                AntiRatingColor
                            } else {
                                ApuBubbleTextColor
                            },
                        )
                    }
                }

                if (antiRatingCount > 0) {
                    Spacer(Modifier.height(10.dp))
                    val isWarning = antiRatingWarning
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isWarning) {
                                    AntiRatingColor.copy(alpha = 0.12f)
                                } else {
                                    Color(0xFF9A5B13).copy(alpha = 0.10f)
                                },
                                RoundedCornerShape(12.dp),
                            )
                            .border(
                                width = 1.dp,
                                color = if (isWarning) {
                                    AntiRatingColor.copy(alpha = 0.38f)
                                } else {
                                    Color(0xFF9A5B13).copy(alpha = 0.30f)
                                },
                                shape = RoundedCornerShape(12.dp),
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = if (isWarning) {
                                val clock = if (antiRatingUntilMs > 0) {
                                    " " + stringResource(R.string.pps_warn_until, warningClockTime(antiRatingUntilMs))
                                } else {
                                    " " + stringResource(R.string.pps_warn_temp)
                                }
                                stringResource(R.string.pps_warn_head, antiRatingCount) +
                                    " " + stringResource(R.string.pps_warn_tail) + clock
                            } else {
                                stringResource(R.string.pps_anti_rating_note, antiRatingCount)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = if (isWarning) AntiRatingColor else Color(0xFF9A5B13),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Адрес узла спрятан за строкой «Показать».
                //
                // Человеку он не нужен: собеседника добавляют по QR или ссылке,
                // а не переписыванием сорока символов. При этом длинный набор
                // букв и цифр занимал половину карточки и выглядел как
                // техническая ошибка. Оставляем возможность раскрыть его -
                // адрес нужен, когда разбираются, почему сообщения не идут.
                if (contactId.isNotBlank()) {
                    var idShown by remember { mutableStateOf(false) }
                    if (!idShown) {
                        ApuTextAction(label = stringResource(R.string.pp_show_addr), onClick = { idShown = true })
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                // Владелец 2026-10-07: карточка профиля — та же
                                // премиальная поверхность, что и остальные разделы.
                                .apuPremiumLift(5.dp, RoundedCornerShape(14.dp))
                                .apuBubbleSurface(shape = RoundedCornerShape(14.dp))
                                .apuPremiumThread(shape = RoundedCornerShape(14.dp), inset = 14.dp)
                                .apuPremiumGloss(RoundedCornerShape(14.dp), intensity = 0.4f, topFraction = 0.55f)
                                .then(
                                    if (onCopyId != null) {
                                        Modifier.clickable(onClick = onCopyId)
                                    } else {
                                        Modifier
                                    }
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.pps_node_address_hint),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF5A6472),
                                    )
                                    Text(
                                        contactId,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF1E2430),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (onCopyId != null) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = stringResource(R.string.action_copy),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                if (onRename != null || onCall != null) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        if (onCall != null) {
                            ApuTextAction(
                                label = stringResource(R.string.menu_call),
                                onClick = onCall,
                                icon = Icons.Default.Call,
                            )
                        }
                        if (onRename != null) {
                            ApuTextAction(
                                label = stringResource(R.string.chat_rename),
                                onClick = onRename,
                                icon = Icons.Default.Edit,
                            )
                        }
                    }
                }
            }
        },
    )
}

/** Время окончания предупреждения по-человечески: «14:35». */
private fun warningClockTime(untilMs: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(untilMs))
