package com.vladimir.messenger.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Одна кнопка нижней панели. */
data class ApuBottomItem(
    val title: String,
    val icon: ImageVector,
    /** Число на значке: 0 - ничего не показывать. */
    val badge: Int = 0,
    /** Раздел, который открыт прямо сейчас: подсвечиваем кнопку. */
    val selected: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Нижняя панель главного экрана в стиле APU.
 *
 * Такой же светлый пузырь со скруглениями и тонкой золотой рамкой, как полоска
 * разделов сверху, — экран получает одинаковую рамку сверху и снизу. Это НЕ
 * `NavigationBar` из Material: та рисует сплошную плашку во всю ширину и
 * закрывает обои, ради которых весь экран и сделан прозрачным.
 *
 * Панель поднята над системной полосой жестов (`navigationBarsPadding`), чтобы
 * нижняя кнопка не оказалась под чертой возврата.
 */
@Composable
fun ApuBottomBar(
    items: List<ApuBottomItem>,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFFF5F7FA).copy(alpha = 0.94f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                shape = RoundedCornerShape(22.dp),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (item in items) {
                BottomButton(item)
            }
        }
    }
}

/** Кнопка с подписью: при нажатии слегка проседает, как настоящая клавиша. */
@Composable
private fun BottomButton(item: ApuBottomItem) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(),
        label = "apu-bottom-press",
    )

    // Открытый раздел подсвечен заливкой: без неё в пузыре не видно, где ты.
    val highlight = if (item.selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    } else {
        Color.Transparent
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(highlight)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = item.onClick,
            )
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .scale(scale),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BadgedBox(
            badge = {
                if (item.badge > 0) {
                    Badge { Text(if (item.badge > 99) "99+" else item.badge.toString()) }
                }
            },
        ) {
            Icon(
                item.icon,
                contentDescription = item.title,
                tint = if (item.selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                },
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            item.title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF1E2430),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
