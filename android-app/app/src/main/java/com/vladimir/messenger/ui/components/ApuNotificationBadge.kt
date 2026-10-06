package com.vladimir.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Единый счётчик уведомлений APU: новые сообщения, комментарии и заявки.
 *
 * Контейнер всегда берёт основной цвет темы, а цифры — контрастный
 * [MaterialTheme.colorScheme.onPrimary]. Поэтому на дневной тёмной бронзе
 * цифры светлые, а в ночной теме на светлом золоте они остаются читаемыми.
 */
@Composable
fun ApuNotificationBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    if (count <= 0) return

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}
