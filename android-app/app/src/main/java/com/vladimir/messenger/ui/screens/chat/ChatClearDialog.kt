package com.vladimir.messenger.ui.screens.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.R
import com.vladimir.messenger.domain.model.Chat
import com.vladimir.messenger.ui.components.ApuBubbleTextColor
import com.vladimir.messenger.ui.components.ApuPremiumDialog

/** A deliberate opt-in: a remote clear never occurs merely by opening the dialog. */
@Composable
internal fun ChatClearDialog(chat: Chat, onDismiss: () -> Unit, onConfirm: (Boolean) -> Unit) {
    var both by remember(chat.id) { mutableStateOf(false) }
    ApuPremiumDialog(
        title = stringResource(R.string.chat_clear_q),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.chat_clear),
        onConfirm = { onConfirm(both) },
    ) {
        Text(stringResource(R.string.chat_clear_body, chat.contactName), style = MaterialTheme.typography.bodyMedium, color = ApuBubbleTextColor)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = both, onCheckedChange = { both = it })
            Text(stringResource(R.string.chat_clear_also_peer), color = ApuBubbleTextColor)
        }
        if (both) Text(stringResource(R.string.chat_clear_peer_limit), style = MaterialTheme.typography.bodySmall, color = ApuBubbleTextColor)
    }
}
