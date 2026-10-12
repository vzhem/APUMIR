package com.vladimir.messenger.ui.components

import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.R
// =============================================================================
// APUACTIONMENU.KT — единое меню «три точки» (раунд 211)
// =============================================================================
// Владелец: во всех «трёх точках» меню должно быть ПОЛНЫМ и в ПУЗЫРЯХ
// нашего стиля — золотые кнопки-ряды в тёмной подложке (как у карточек
// файлов, раунд 168). Компонент общий для группы, канала, избранного и
// диалогов личного чата, чтобы стиль больше не расходился.
//
// Опасные действия (удалить) — красный пузырь.
// =============================================================================

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Один пункт меню «три точки»: подпись, значок, опасность, действие. */
data class ApuAction(
    val title: String,
    val icon: ImageVector,
    /** Недоступное действие остаётся видимым, но не принимает повторный тап. */
    val enabled: Boolean = true,
    /** Опасное действие рисуется красным пузырём (удалить). */
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Пункт действия в стиле APU (раунд 168, теперь на общей объёмной кнопке):
 * золотая кнопка с подъёмом, глянцем и переливом на нажатие. Опасное действие —
 * красная. Все прежние вызовы работают без изменений; [maxLines] оставлен для
 * совместимости (подпись в объёмной кнопке и так переносится по ширине).
 */
@Composable
fun ApuActionBubble(
    label: String,
    icon: ImageVector,
    enabled: Boolean = true,
    destructive: Boolean = false,
    @Suppress("UNUSED_PARAMETER") maxLines: Int = 2,
    onClick: () -> Unit,
) {
    ApuDiagnosticsActionButton(
        label = label,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        icon = icon,
        style = if (destructive) DiagnosticsActionStyle.DANGER else DiagnosticsActionStyle.PRIMARY,
        enabled = enabled,
    )
    Spacer(Modifier.height(6.dp))
}

/**
 * Меню «три точки» в стиле APU: системная подложка-карточка, внутри —
 * золотые пузыри действий. Показывать так же, как DropdownMenu: рядом
 * с якорем (кнопкой точек).
 */
@Composable
fun ApuActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    actions: List<ApuAction?>,
    modifier: Modifier = Modifier,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                // The menu never grows beyond a phone-friendly width because
                // labels are deliberately single-line ellipsized bubbles.
                .widthIn(min = 210.dp, max = 320.dp),
        ) {
            actions.filterNotNull().forEach { action ->
                ApuActionBubble(
                    label = action.title,
                    icon = action.icon,
                    enabled = action.enabled,
                    destructive = action.destructive,
                    maxLines = 1,
                    onClick = {
                        onDismiss()
                        action.onClick()
                    },
                )
            }
        }
    }
}

/**
 * Кнопка «три точки» для текстовых пузырей (раунд 211): маленький тёмный
 * кружок с белым глифом, как у картинок (раунд 44) и гифок (раунд 210).
 */
@Composable
fun ApuMenuDots(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.MoreVert,
            contentDescription = stringResource(R.string.sv_actions),
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
    }
}
