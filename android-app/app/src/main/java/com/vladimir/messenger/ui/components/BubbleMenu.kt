package com.vladimir.messenger.ui.components

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** Тип пузыря: подпись под именем и логика меню. */
enum class BubbleKind(val label: String) {
    Personal("личный чат"),
    Group("группа"),
    Channel("канал"),
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
            contentDescription = "Меню",
            tint = Color(0xFF5A6472),
            modifier = Modifier.size(20.dp),
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        actions.forEach { action ->
            DropdownMenuItem(
                text = {
                    Text(
                        action.title,
                        color = if (action.destructive) {
                            MaterialTheme.colorScheme.error
                        } else {
                            Color.Unspecified
                        },
                    )
                },
                leadingIcon = action.icon?.let { icon ->
                    {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = if (action.destructive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                Color(0xFF5A6472)
                            },
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
