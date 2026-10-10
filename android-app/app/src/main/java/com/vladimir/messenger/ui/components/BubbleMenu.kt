package com.vladimir.messenger.ui.components

import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.R
// =============================================================================
// BUBBLEMENU.KT — меню «три точки» внутри пузыря списка
// =============================================================================
// Один и тот же вид меню используется в пузырях главного экрана (личные чаты,
// группы, каналы) и в пузырях списка контактов: списки обязаны выглядеть
// одинаково, поэтому кнопка и стиль живут здесь, а не в каждом экране.
//
// Стиль APU: скруглённая иконка, серый глиф на светлом пузыре, опасное
// действие (удалить) — красным.
// =============================================================================

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Цвет опасного действия в меню пузыря (тот же, что у красных кнопок APU). */
private val BubbleMenuDangerInk = Color(0xFFA12D3A)

/** Тип пузыря: подпись под именем и логика меню. */
enum class BubbleKind(@androidx.annotation.StringRes val labelRes: Int) {
    Personal(R.string.bubble_kind_personal),
    Group(R.string.bubble_kind_group),
    Channel(R.string.bubble_kind_channel),
}

/** Пункт меню «⋮» в пузыре. */
data class BubbleMenuAction(
    val title: String,
    val icon: ImageVector? = null,
    /** Опасное действие подсвечивается красным (удалить чат, удалить контакт). */
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/** Кнопка «три вертикальные точки» справа в пузыре с выпадающим меню. */
@Composable
fun BubbleOverflowMenu(
    actions: List<BubbleMenuAction>,
    modifier: Modifier = Modifier,
) {
    if (actions.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    IconButton(
        onClick = { expanded = true },
        modifier = modifier.size(32.dp),
    ) {
        Icon(
            Icons.Default.MoreVert,
            contentDescription = stringResource(R.string.bm_menu),
            tint = Color(0xFF5A6472),
            modifier = Modifier.size(20.dp),
        )
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        // A title from a server or a contact must not make an overflow menu
        // wider than the viewport.
        modifier = Modifier.widthIn(min = 220.dp, max = 320.dp),
        // Премиальная карточка APU вместо лавандовой подложки Material:
        // та же светлая поверхность, что у пузырей, и мягкая тень.
        containerColor = ApuBubbleSurfaceColor,
        shape = RoundedCornerShape(22.dp),
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
    ) {
        actions.forEach { action ->
            DropdownMenuItem(
                text = {
                    Text(
                        action.title,
                        color = if (action.destructive) BubbleMenuDangerInk else ApuBubbleTextColor,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = action.icon?.let { icon ->
                    {
                        // Золотая плитка, как в настройках; опасное действие — розовая.
                        ApuPremiumIconTile(
                            icon = icon,
                            size = 32.dp,
                            corner = 10.dp,
                            lift = 3.dp,
                            background = if (action.destructive) {
                                Brush.linearGradient(listOf(Color(0xFFFDF1F2), Color(0xFFF8E4E7)))
                            } else {
                                apuGoldBrush()
                            },
                            tint = if (action.destructive) BubbleMenuDangerInk else ApuGoldInk,
                        )
                    }
                },
                onClick = {
                    expanded = false
                    action.onClick()
                },
            )
        }
    }
}
