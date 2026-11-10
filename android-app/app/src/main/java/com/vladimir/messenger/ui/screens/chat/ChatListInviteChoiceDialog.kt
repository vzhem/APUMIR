package com.vladimir.messenger.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.vladimir.messenger.R
import com.vladimir.messenger.ui.components.ApuPremiumDialog

/** The three invite actions must not be the only way out of this menu. */
@Composable
internal fun ChatListInviteChoiceDialog(
    isChannel: Boolean,
    onDismiss: () -> Unit,
    onShowQr: () -> Unit,
    onSendLink: () -> Unit,
    onSendInApu: () -> Unit,
) {
    val what = stringResource(if (isChannel) R.string.word_channel_acc else R.string.word_group_acc)
    ApuPremiumDialog(
        title = stringResource(R.string.invite_title, what),
        onDismiss = onDismiss,
        dismissLabel = stringResource(R.string.action_cancel),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.grp_qr_hint))
            Spacer(Modifier.height(14.dp))
            InviteActionBubble(stringResource(R.string.groups_show_qr), onClick = onShowQr)
            Spacer(Modifier.height(8.dp))
            InviteActionBubble(stringResource(R.string.groups_send_link), onClick = onSendLink)
            Spacer(Modifier.height(8.dp))
            InviteActionBubble(stringResource(R.string.contacts_send_in_apu), onClick = onSendInApu)
        }
    }
}

@Composable
private fun InviteActionBubble(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.primary)
            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontWeight = FontWeight.Bold, color = Color.White)
    }
}
