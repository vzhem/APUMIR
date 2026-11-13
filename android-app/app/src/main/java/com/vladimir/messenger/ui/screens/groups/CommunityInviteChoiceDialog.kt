package com.vladimir.messenger.ui.screens.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.R
import com.vladimir.messenger.ui.components.ApuBubbleMutedColor
import com.vladimir.messenger.ui.components.ApuPremiumCheckbox
import com.vladimir.messenger.ui.components.ApuPremiumContentButton
import com.vladimir.messenger.ui.components.ApuPremiumDialog
import com.vladimir.messenger.ui.components.ApuTextAction
import com.vladimir.messenger.ui.components.DiagnosticsActionStyle

/** All invite choices plus an explicit cancel that never sends an invitation. */
@Composable
internal fun CommunityInviteChoiceDialog(
    isChannel: Boolean,
    onDismiss: () -> Unit,
    onSendInApu: () -> Unit,
    onShowQr: () -> Unit,
    onShareLink: (attachApk: Boolean) -> Unit,
) {
    val what = stringResource(if (isChannel) R.string.word_channel_acc else R.string.word_group_acc)
    var attachApk by remember { mutableStateOf(true) }
    ApuPremiumDialog(
        title = stringResource(R.string.invite_title, what),
        onDismiss = onDismiss,
        dismissLabel = stringResource(R.string.action_cancel),
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ApuPremiumContentButton(
                onClick = onSendInApu,
                modifier = Modifier.fillMaxWidth(),
                style = DiagnosticsActionStyle.PRIMARY,
            ) {
                Icon(Icons.Filled.PersonAdd, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.contacts_send_in_apu), fontWeight = FontWeight.SemiBold)
            }
            Text(stringResource(R.string.invite_send_apu_hint), style = MaterialTheme.typography.bodySmall, color = ApuBubbleMutedColor)
            Text(stringResource(R.string.grp_qr_hint))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { attachApk = !attachApk }
                    .padding(horizontal = 4.dp),
            ) {
                ApuPremiumCheckbox(checked = attachApk, onCheckedChange = { attachApk = it })
                Text(stringResource(R.string.contacts_attach_apk), style = MaterialTheme.typography.bodySmall)
            }
            ApuTextAction(
                label = stringResource(R.string.groups_show_qr),
                onClick = onShowQr,
                modifier = Modifier.fillMaxWidth(),
            )
            ApuTextAction(
                label = stringResource(R.string.groups_send_link),
                onClick = { onShareLink(attachApk) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
