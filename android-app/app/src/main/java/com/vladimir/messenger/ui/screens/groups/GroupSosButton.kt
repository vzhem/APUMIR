package com.vladimir.messenger.ui.screens.groups

import com.vladimir.messenger.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Красная кнопка SOS над полем ввода: сплошной красный цвет и крупная
 * подпись - кнопку должно быть видно и понятно сразу, без чтения.
 * Пока идёт пауза от повторного нажатия, кнопка серая и показывает,
 * сколько секунд осталось; во время отправки - что сигнал уже уходит.
 *
 * Вынесена из `GroupChatScreen.kt` при добавлении опросов (р252): экран
 * упирается в потолок god-файлов, а кнопка — самодостаточный кусок.
 */
@Composable
internal fun SosButton(
    sending: Boolean,
    waitSeconds: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = !sending && waitSeconds <= 0L
    val label = when {
        sending -> stringResource(R.string.sos_sending)
        waitSeconds > 0L -> stringResource(R.string.sos_wait_seconds, waitSeconds)
        else -> stringResource(R.string.sos_button)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (enabled) Color(0xFFC62828) else Color(0xFF8E8E93),
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
