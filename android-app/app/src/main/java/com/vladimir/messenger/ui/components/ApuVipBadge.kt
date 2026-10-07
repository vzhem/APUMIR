package com.vladimir.messenger.ui.components

// =============================================================================
// APUVIPBADGE.KT — знак VIP: элита приложения
// =============================================================================
// Решение владельца от 2026-10-06: «все, кто выше 10 ранга, уже VIP, выделить
// их как элиту мессенджера, сделать как в топовых мессенджерах».
//
// Как выглядят топовые мессенджеры: рядом с именем стоит маленький, но
// безошибочно узнаваемый знак — звезда Telegram Premium, ромб и подобное. Он НЕ
// заменяет имя и не занимает полстроки: это ярлык-печать. Поэтому здесь
// компактная золотая плашка со звездой и словом VIP, а не ещё одна карточка.
//
// Рисуется вручную (градиент + ободок + тень), а не эмодзи: у эмодзи вид
// зависит от прошивки, и на части телефонов звезда выглядела бы чёрно-белой.
// Цвета те же, что у медали ранга: VIP читается как «следующая ступень» той же
// награды, а не как чужой значок.
// =============================================================================

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Тёмная бронза надписи на золоте: читается и на светлом, и на тёмном фоне. */
private val VipInkColor = Color(0xFF5A3E06)

/**
 * Знак VIP: золотая плашка со звездой и словом VIP.
 *
 * `compact = true` — только звезда, для строки, где и без того тесно (шапка
 * чатов, имя ранга). Полный вариант со словом — на экране рангов, там места
 * достаточно и важнее объяснить, что за знак.
 */
@Composable
fun ApuVipBadge(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /** Своя подпись: у наградного знака это всегда «VIP», но тест/экран может задать иную. */
    label: String = "VIP",
) {
    val shape = RoundedCornerShape(9.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFFFFE9A8),
                        Color(0xFFE7B93F),
                        Color(0xFFC08F1D),
                    ),
                ),
            )
            .border(1.dp, Color(0xFF8A6410).copy(alpha = 0.75f), shape)
            .padding(
                horizontal = if (compact) 5.dp else 8.dp,
                vertical = 3.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            Icons.Default.Star,
            contentDescription = if (compact) label else null,
            tint = VipInkColor,
            modifier = Modifier.size(if (compact) 12.dp else 14.dp),
        )
        if (!compact) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = VipInkColor,
                maxLines = 1,
            )
        }
    }
}
