package com.vladimir.messenger.ui.screens.chat

import com.vladimir.messenger.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.ui.components.ApuBubbleCard
import com.vladimir.messenger.ui.components.ApuBubbleMutedColor
import com.vladimir.messenger.ui.components.ApuBubbleTextColor
import com.vladimir.messenger.ui.components.ApuTextAction

/**
 * Плашка «история чата не загрузилась» с кнопкой повтора.
 *
 * Вынесена из `ChatDetailScreen.kt` (потолок god-файлов `check-god-files.py`):
 * в экране остались лента, панель ввода и диалоги.
 */
@Composable
internal fun ChatHistoryError(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    ApuBubbleCard(modifier = modifier.padding(16.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(message, color = ApuBubbleTextColor, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.chat_history_retry_hint),
                color = ApuBubbleMutedColor,
                style = MaterialTheme.typography.bodySmall,
            )
            ApuTextAction(label = stringResource(R.string.action_retry), onClick = onRetry)
        }
    }
}
