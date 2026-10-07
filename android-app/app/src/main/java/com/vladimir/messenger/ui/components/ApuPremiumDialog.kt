package com.vladimir.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Диалог в премиальном стиле «как в Логах» — ОДИН вид на всё приложение.
 *
 * Зачем отдельный компонент, а не stock `AlertDialog`: у Material диалог и его
 * кнопки выглядят чужеродно рядом с золотыми пузырями (владелец 2026-10-07:
 * «теперь в таком стиле нужно переделать всё приложение»). Здесь:
 *
 * - заголовок — золотая капсула с тёмными чернилами и нитью-продолжением;
 * - тело — светлая подложка house style с тонкой золотой кромкой;
 * - кнопки — те же, что в окне «Логи» (`ApuPremiumButton`): золотая главная,
 *   стеклянная и тихая для отказа.
 *
 * `properties` пробрасываем наружу: некоторые диалоги (выбор контактов, QR)
 * не должны закрываться тапом мимо.
 */
@Composable
fun ApuPremiumDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
    confirmEnabled: Boolean = true,
    confirmStyle: DiagnosticsActionStyle = DiagnosticsActionStyle.PRIMARY,
    dismissLabel: String? = "Отмена",
    onDismissClick: (() -> Unit)? = null,
    dismissEnabled: Boolean = true,
    properties: DialogProperties = DialogProperties(),
) {
    Dialog(onDismissRequest = onDismiss, properties = properties) {
        val shape = RoundedCornerShape(22.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Мягкая тень + глянец ПОД содержимым: текст и значки чёткие.
                .apuPremiumLift(14.dp, shape, ApuPremiumShadowColor)
                .clip(shape)
                .background(Color(0xFFF7F9FC).copy(alpha = 0.98f))
                .border(1.dp, ApuGold.copy(alpha = 0.45f), shape)
                .apuPremiumGloss(shape, intensity = 0.5f, topFraction = 0.35f)
                .padding(16.dp),
        ) {
            // Заголовок — золотая капсула, как у заголовков секций настроек.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = ApuGoldInk,
                    modifier = Modifier
                        .clip(RoundedCornerShape(11.dp))
                        .background(apuGoldBrush())
                        .border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(11.dp))
                        .apuPremiumGloss(RoundedCornerShape(11.dp), intensity = 0.85f, topFraction = 0.7f)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
                if (dismissLabel != null || confirmLabel != null) {
                    Spacer(Modifier.width(10.dp))
                    // Нить-продолжение уводит взгляд от заголовка к телу диалога.
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(1.5.dp)
                            .background(apuGoldBrush()),
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(content = content)
            if (confirmLabel != null || dismissLabel != null) {
                Spacer(Modifier.size(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (dismissLabel != null) {
                        ApuPremiumButton(
                            label = dismissLabel,
                            onClick = onDismissClick ?: onDismiss,
                            style = DiagnosticsActionStyle.QUIET,
                            enabled = dismissEnabled,
                        )
                        if (confirmLabel != null) Spacer(Modifier.width(8.dp))
                    }
                    if (confirmLabel != null && onConfirm != null) {
                        ApuPremiumButton(
                            label = confirmLabel,
                            onClick = onConfirm,
                            style = confirmStyle,
                            enabled = confirmEnabled,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Простое объяснение с одной кнопкой «Понятно» — для сообщений вида
 * «получилось / не получилось», где от человека ничего не требуется.
 */
@Composable
fun ApuPremiumNoticeDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    confirmLabel: String = "Понятно",
    icon: ImageVector? = null,
) {
    ApuPremiumDialog(
        title = title,
        onDismiss = onDismiss,
        confirmLabel = confirmLabel,
        onConfirm = onDismiss,
        confirmStyle = DiagnosticsActionStyle.GLASS,
        dismissLabel = null,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (icon != null) {
                ApuPremiumIconTile(icon, size = 34.dp, corner = 10.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(
                message,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = ApuBubbleTextColor,
                textAlign = TextAlign.Start,
            )
        }
    }
}

/**
 * Пункт выбора внутри премиального диалога: золотая плитка со значком,
 * заголовок и пояснение. Используется в списках выбора («Кому отправить»,
 * быстрые действия) вместо серых строк-заглушек.
 */
@Composable
fun ApuPremiumChoiceRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) {
                    Modifier
                        .apuPremiumLift(6.dp, shape, ApuGold.copy(alpha = 0.45f))
                        .clip(shape)
                        .background(apuGoldBrush())
                        .border(1.dp, Color.White.copy(alpha = 0.45f), shape)
                        .apuPremiumGloss(shape, intensity = 0.85f, topFraction = 0.7f)
                } else {
                    Modifier
                        .clip(shape)
                        .background(ApuBubbleSurfaceColor)
                        .border(1.dp, ApuBubbleAccentColor.copy(alpha = 0.25f), shape)
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            ApuPremiumIconTile(
                icon,
                size = 30.dp,
                corner = 9.dp,
                background = apuGoldBrush(),
            )
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (selected) ApuGoldInk else ApuBubbleTextColor,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = if (selected) ApuGoldInk.copy(alpha = 0.8f) else ApuBubbleMutedColor,
                )
            }
        }
    }
}
