package com.vladimir.messenger.ui.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.data.group.GroupPermissions

/**
 * Итог выбора в диалоге удаления и модерации сообщения/поста.
 * Несколько пунктов из блока «Ещё действия» могут быть выбраны одновременно.
 */
data class ApuModerationResult(
    val deleteForAll: Boolean,
    val giveAntiRating: Boolean,
    val deleteAllFromAuthor: Boolean,
    val blockAuthor: Boolean,
    val deleteAllInGroup: Boolean,
    val restrictAuthorPermissions: Boolean,
    val allowedMemberMask: Long,
)

/**
 * Плашка анти-рейтинга рядом с именем автора в ленте сообщений или карточке участника.
 *
 * [isWarning] приходит от временного «всплеска» жалоб: одиночные отметки 👎
 * показываются спокойным цветом и приоритет узла не понижают.
 */
@Composable
fun ApuAntiRatingInlineBadge(
    antiCount: Int,
    modifier: Modifier = Modifier,
    isWarning: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    if (antiCount <= 0) return
    val badgeColor = if (isWarning) ApuSettingsDangerColor else Color(0xFF9A5B13)
    val bgColor = if (isWarning) {
        ApuSettingsDangerColor.copy(alpha = 0.14f)
    } else {
        Color(0xFF9A5B13).copy(alpha = 0.12f)
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor, RoundedCornerShape(10.dp))
            .border(0.75.dp, badgeColor.copy(alpha = 0.40f), RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = if (isWarning) "⚠️ \uD83D\uDC4E $antiCount" else "\uD83D\uDC4E $antiCount",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = badgeColor,
        )
    }
}

/**
 * Круглый индикатор множественного выбора в фирменном стиле APU (как в современных мессенджерах).
 */
@Composable
fun ApuCircleCheckIndicator(
    checked: Boolean,
    danger: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val activeColor = if (danger) ApuSettingsDangerColor else ApuBubbleAccentColor
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(
                if (checked) activeColor else Color.Transparent,
                CircleShape,
            )
            .border(
                width = 1.8.dp,
                color = if (checked) activeColor else ApuBubbleMutedColor.copy(alpha = 0.55f),
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Color(0xFFFFF8E6),
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/**
 * Строка множественного выбора с круглым чекбоксом слева и необязательным бейджем справа.
 */
@Composable
private fun ApuModerationOptionRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    danger: Boolean = false,
    badgeText: String? = null,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Checkbox, onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ApuCircleCheckIndicator(checked = checked, danger = danger)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (danger && checked) ApuSettingsDangerColor else ApuBubbleTextColor,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = ApuBubbleMutedColor,
                )
            }
        }
        if (!badgeText.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .background(
                        ApuBubbleAccentColor.copy(alpha = 0.12f),
                        RoundedCornerShape(10.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = ApuBubbleAccentColor,
                )
            }
        }
    }
}

/**
 * Модальное окно удаления сообщений и модерации участника в группах и каналах
 * в фирменном стиле APU:
 *  - заголовок «Удалить N сообщение?» и визитка автора с репутацией;
 *  - блок «Ещё действия» с одновременным выбором нескольких пунктов:
 *      • Понизить рейтинг профиля (анти-рейтинг 👎 / спам);
 *      • Удалить всё от автора в группе или канале;
 *      • Заблокировать автора;
 *      • Удалить все сообщения в группе или канале;
 *  - раскрывающийся блок «Ограничить права пользователя ⌄» с переключателями разрешений;
 *  - кнопка «Продолжить».
 */
@Composable
fun ApuMessageModerationDialog(
    selectedMessageCount: Int = 1,
    isChannel: Boolean = false,
    isChannelPost: Boolean = false,
    authorId: String = "",
    authorName: String = "",
    isAuthorMe: Boolean = false,
    isAuthorOwner: Boolean = false,
    canModerate: Boolean = false,
    canDeleteForAll: Boolean = false,
    authorMessageCount: Int = 1,
    authorAntiCount: Int = 0,
    /** Есть ли у автора сейчас временное предупреждение о всплеске жалоб. */
    authorAntiWarning: Boolean = false,
    alreadyHasMyAnti: Boolean = false,
    initialMemberPermissionsMask: Long = GroupPermissions.Member.DEFAULT,
    onOpenAuthorProfile: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: (ApuModerationResult) -> Unit,
) {
    val displayAuthor = authorName.trim().ifBlank {
        if (authorId.isNotBlank()) "*${authorId.takeLast(4)}" else "Участник"
    }
    var deleteForAll by remember(canDeleteForAll, isAuthorMe) {
        mutableStateOf(canDeleteForAll || isAuthorMe)
    }
    var giveAntiRating by remember(authorId) { mutableStateOf(false) }
    var deleteAllFromAuthor by remember(authorId) { mutableStateOf(false) }
    var blockAuthor by remember(authorId) { mutableStateOf(false) }
    var deleteAllInGroup by remember { mutableStateOf(false) }
    var restrictionsExpanded by remember { mutableStateOf(false) }
    var restrictAuthorPermissions by remember { mutableStateOf(false) }
    var allowedMemberMask by remember(initialMemberPermissionsMask) {
        mutableLongStateOf(initialMemberPermissionsMask and GroupPermissions.Member.ALL)
    }

    val titleText = when {
        selectedMessageCount > 1 -> "Удалить $selectedMessageCount сообщ.?"
        isChannelPost -> "Удалить публикацию?"
        else -> "Удалить 1 сообщение?"
    }
    val communityWord = if (isChannel) "канале" else "группе"
    val showAuthorActions = authorId.isNotBlank() && !isAuthorMe
    val showMoreActions = showAuthorActions || canModerate

    val totalEntries = GroupPermissions.Member.entries.size
    val enabledPermCount = GroupPermissions.Member.entries.count {
        GroupPermissions.has(allowedMemberMask, it.flag)
    }

    ApuSettingsDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = ApuBubbleTextColor,
                )
                if (authorId.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(ApuBubbleAccentColor.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                            .then(
                                if (onOpenAuthorProfile != null) {
                                    Modifier.clickable(role = Role.Button, onClick = onOpenAuthorProfile)
                                } else {
                                    Modifier
                                }
                            )
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(name = displayAuthor, size = 30)
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = displayAuthor,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ApuBubbleTextColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                ApuAntiRatingInlineBadge(
                                    antiCount = authorAntiCount,
                                    isWarning = authorAntiWarning,
                                )
                            }
                            Text(
                                text = if (isAuthorMe) "Ваше сообщение" else "Автор • *${authorId.takeLast(6)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = ApuBubbleMutedColor,
                            )
                        }
                        if (onOpenAuthorProfile != null) {
                            Text(
                                text = "Профиль",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = ApuBubbleLinkColor,
                            )
                        }
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Основной режим удаления: у всех или только на этом устройстве
                if (canDeleteForAll || isAuthorMe) {
                    ApuSettingsCard {
                        ApuModerationOptionRow(
                            title = "Удалить у всех участников",
                            subtitle = "Сообщение исчезнет на всех устройствах в $communityWord",
                            checked = deleteForAll,
                            onToggle = { deleteForAll = !deleteForAll },
                        )
                    }
                } else {
                    Text(
                        text = "Сообщение будет удалено только на этом устройстве.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ApuBubbleMutedColor,
                    )
                }

                if (showMoreActions) {
                    ApuSettingsSectionTitle("Ещё действия")
                    ApuSettingsCard {
                        var hasPreviousRow = false

                        // 1. Анти-рейтинг профилю (жалоба / спам)
                        if (showAuthorActions) {
                            ApuModerationOptionRow(
                                title = "Анти-рейтинг профилю (спам)",
                                subtitle = "Жалоба: просядет несколько жалоб сразу — репутация и приоритет в рое временно понизятся",
                                checked = giveAntiRating,
                                danger = true,
                                badgeText = if (alreadyHasMyAnti) "Уже \uD83D\uDC4E" else "\uD83D\uDC4E +1",
                                onToggle = { giveAntiRating = !giveAntiRating },
                            )
                            hasPreviousRow = true
                        }

                        // 2. Удалить все сообщения от этого автора в группе/канале
                        if (canModerate && authorId.isNotBlank()) {
                            if (hasPreviousRow) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 14.dp),
                                    thickness = 0.5.dp,
                                    color = ApuBubbleAccentColor.copy(alpha = 0.16f),
                                )
                            }
                            ApuModerationOptionRow(
                                title = "Удалить всё от $displayAuthor",
                                subtitle = "Стереть все сообщения автора в $communityWord у всех",
                                checked = deleteAllFromAuthor,
                                badgeText = "${authorMessageCount.coerceAtLeast(1)} сообщ.",
                                onToggle = {
                                    deleteAllFromAuthor = !deleteAllFromAuthor
                                    if (deleteAllFromAuthor) deleteForAll = true
                                },
                            )
                            hasPreviousRow = true
                        }

                        // 3. Заблокировать автора в группе/канале
                        if (canModerate && showAuthorActions && !isAuthorOwner) {
                            if (hasPreviousRow) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 14.dp),
                                    thickness = 0.5.dp,
                                    color = ApuBubbleAccentColor.copy(alpha = 0.16f),
                                )
                            }
                            ApuModerationOptionRow(
                                title = "Заблокировать $displayAuthor",
                                subtitle = "Исключить из ${if (isChannel) "канала" else "группы"} и запретить отправку",
                                checked = blockAuthor,
                                danger = true,
                                onToggle = { blockAuthor = !blockAuthor },
                            )
                            hasPreviousRow = true
                        }

                        // 4. Удалить ВСЕ сообщения в группе или канале
                        if (canModerate) {
                            if (hasPreviousRow) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 14.dp),
                                    thickness = 0.5.dp,
                                    color = ApuBubbleAccentColor.copy(alpha = 0.16f),
                                )
                            }
                            ApuModerationOptionRow(
                                title = "Удалить все сообщения в $communityWord",
                                subtitle = "Полная очистка всей истории в $communityWord у всех",
                                checked = deleteAllInGroup,
                                danger = true,
                                onToggle = {
                                    deleteAllInGroup = !deleteAllInGroup
                                    if (deleteAllInGroup) deleteForAll = true
                                },
                            )
                        }
                    }
                }

                // Раскрывающийся блок «Ограничить права пользователя ⌄»
                if (canModerate && showAuthorActions && !isAuthorOwner) {
                    ApuSettingsCard(highlighted = restrictAuthorPermissions) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) {
                                    restrictionsExpanded = !restrictionsExpanded
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ApuCircleCheckIndicator(
                                checked = restrictAuthorPermissions,
                                danger = false,
                                modifier = Modifier.clickable(role = Role.Checkbox) {
                                    restrictAuthorPermissions = !restrictAuthorPermissions
                                    if (restrictAuthorPermissions && !restrictionsExpanded) {
                                        restrictionsExpanded = true
                                    }
                                },
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Ограничить права пользователя",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ApuBubbleTextColor,
                                )
                                Text(
                                    text = "Разрешено: $enabledPermCount из $totalEntries",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ApuBubbleMutedColor,
                                )
                            }
                            Icon(
                                imageVector = if (restrictionsExpanded) {
                                    Icons.Default.KeyboardArrowUp
                                } else {
                                    Icons.Default.KeyboardArrowDown
                                },
                                contentDescription = null,
                                tint = ApuBubbleAccentColor,
                            )
                        }

                        AnimatedVisibility(visible = restrictionsExpanded) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 14.dp),
                                    thickness = 0.5.dp,
                                    color = ApuBubbleAccentColor.copy(alpha = 0.16f),
                                )
                                GroupPermissions.Member.entries.forEachIndexed { index, entry ->
                                    if (index > 0) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(horizontal = 14.dp),
                                            thickness = 0.5.dp,
                                            color = ApuBubbleAccentColor.copy(alpha = 0.12f),
                                        )
                                    }
                                    val enabled = GroupPermissions.has(allowedMemberMask, entry.flag)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(role = Role.Switch) {
                                                allowedMemberMask = GroupPermissions.withFlag(
                                                    allowedMemberMask,
                                                    entry.flag,
                                                    !enabled,
                                                )
                                                restrictAuthorPermissions = true
                                            }
                                            .padding(horizontal = 14.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = entry.title,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Medium,
                                                color = ApuBubbleTextColor,
                                            )
                                            Text(
                                                text = entry.hint,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = ApuBubbleMutedColor,
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        ApuPremiumSwitch(
                                            checked = enabled,
                                            onCheckedChange = { checked ->
                                                allowedMemberMask = GroupPermissions.withFlag(
                                                    allowedMemberMask,
                                                    entry.flag,
                                                    checked,
                                                )
                                                restrictAuthorPermissions = true
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val selectedExtras = listOf(
                giveAntiRating,
                deleteAllFromAuthor,
                blockAuthor,
                deleteAllInGroup,
                restrictAuthorPermissions,
            ).count { it }
            ApuPremiumContentButton(
                onClick = {
                    onConfirm(
                        ApuModerationResult(
                            deleteForAll = deleteForAll,
                            giveAntiRating = giveAntiRating,
                            deleteAllFromAuthor = deleteAllFromAuthor,
                            blockAuthor = blockAuthor,
                            deleteAllInGroup = deleteAllInGroup,
                            restrictAuthorPermissions = restrictAuthorPermissions,
                            allowedMemberMask = allowedMemberMask,
                        ),
                    )
                },
                style = if (blockAuthor || deleteAllInGroup || giveAntiRating) DiagnosticsActionStyle.DANGER else DiagnosticsActionStyle.PRIMARY,
            ) {
                Text(
                    text = if (selectedExtras > 0) {
                        "Продолжить (+$selectedExtras)"
                    } else {
                        "Продолжить"
                    },
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            ApuTextAction(label = "Отмена", onClick = onDismiss)
        },
    )
}
