package com.vladimir.messenger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.R
import com.vladimir.messenger.data.profile.InvitePrivacy

/**
 * Выбор «Кто может приглашать в группы и каналы»: все, только контакты из
 * адресной книги или никто. Выбранный пункт подсвечен золотом, как остальные
 * выборы приложения; нажатие сразу применяет и закрывает окно.
 */
@Composable
fun InvitePrivacyDialog(
    current: InvitePrivacy,
    onPick: (InvitePrivacy) -> Unit,
    onDismiss: () -> Unit,
) {
    ApuSettingsDialog(
        onDismissRequest = onDismiss,
        icon = {
            ApuPremiumIconTile(icon = Icons.Default.GroupAdd, size = 34.dp, corner = 10.dp)
        },
        title = {
            Text(
                stringResource(R.string.invite_privacy_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.invite_privacy_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = ApuBubbleMutedColor,
                )
                ApuPremiumChoiceRow(
                    title = stringResource(R.string.invite_privacy_all),
                    subtitle = stringResource(R.string.invite_privacy_all_hint),
                    selected = current == InvitePrivacy.ALL,
                    icon = Icons.Default.Public,
                    onClick = { onPick(InvitePrivacy.ALL) },
                )
                ApuPremiumChoiceRow(
                    title = stringResource(R.string.invite_privacy_contacts),
                    subtitle = stringResource(R.string.invite_privacy_contacts_hint),
                    selected = current == InvitePrivacy.CONTACTS,
                    icon = Icons.Default.People,
                    onClick = { onPick(InvitePrivacy.CONTACTS) },
                )
                ApuPremiumChoiceRow(
                    title = stringResource(R.string.invite_privacy_nobody),
                    subtitle = stringResource(R.string.invite_privacy_nobody_hint),
                    selected = current == InvitePrivacy.NOBODY,
                    icon = Icons.Default.Block,
                    onClick = { onPick(InvitePrivacy.NOBODY) },
                )
            }
        },
        confirmButton = {
            ApuTextAction(label = stringResource(R.string.action_close), onClick = onDismiss)
        },
    )
}
