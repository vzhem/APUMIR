package com.vladimir.messenger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Переключатель «один из нескольких» в премиальном стиле APU: выбранный вариант —
 * золотая плитка, остальные — белое стекло. Построен на тех же чипах, что и
 * остальные фильтры приложения, поэтому выглядит одинаково везде.
 */
@Composable
fun <T> ApuSegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    icon: (T) -> ImageVector? = { null },
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            ApuPremiumChip(
                label = label(option),
                selected = option == selected,
                onClick = { onSelect(option) },
                enabled = enabled,
                icon = icon(option),
                modifier = Modifier.weight(1f),
            )
        }
    }
}
