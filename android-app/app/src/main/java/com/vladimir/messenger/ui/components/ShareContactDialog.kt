package com.vladimir.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.R
import com.vladimir.messenger.domain.model.Contact
import com.vladimir.messenger.ui.theme.AvatarStore

/**
 * «Поделиться контактом»: адресная книга с поиском и мультивыбором.
 * Выбранным абонентам отдельно отправляется одна и та же ссылка; ограничение
 * в 100 получателей совпадает с остальными адресными рассылками приложения.
 */
@Composable
fun ShareContactChooserDialog(
    sharedName: String,
    contacts: List<Contact>,
    onDismiss: () -> Unit,
    onSend: (List<Contact>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val selectedIds = remember { mutableStateListOf<String>() }
    val avatars by AvatarStore.avatars.collectAsState()
    val listState = rememberLazyListState()
    val maxRecipients = com.vladimir.messenger.util.ContactCardSender.MAX_RECIPIENTS

    LaunchedEffect(contacts) {
        val validIds = contacts.mapTo(HashSet<String>()) { it.id }
        selectedIds.removeAll { it !in validIds }
    }

    val selectedContacts = contacts.filter { it.id in selectedIds }.take(maxRecipients)
    val filteredContacts = remember(contacts, query) {
        val needle = query.trim().removePrefix("@").lowercase()
        if (needle.isEmpty()) {
            contacts
        } else {
            contacts.filter { contact ->
                contact.displayName.contains(needle, ignoreCase = true) ||
                    contact.username.removePrefix("@").contains(needle, ignoreCase = true)
            }
        }
    }

    ApuPremiumDialog(
        title = stringResource(R.string.chat_send_to),
        onDismiss = onDismiss,
        confirmLabel = if (selectedContacts.isEmpty()) {
            stringResource(R.string.action_send)
        } else {
            stringResource(R.string.share_contact_send_count, selectedContacts.size)
        },
        onConfirm = {
            val recipients = contacts.filter { it.id in selectedIds }.take(maxRecipients)
            if (recipients.isNotEmpty()) {
                onSend(recipients)
                onDismiss()
            }
        },
        confirmEnabled = selectedContacts.isNotEmpty(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.share_contact_hint, sharedName),
                style = MaterialTheme.typography.bodyMedium,
                color = ApuBubbleTextColor,
            )
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.82f))
                        .border(1.dp, ApuGold.copy(alpha = 0.42f), RoundedCornerShape(50))
                        .apuPremiumGloss(RoundedCornerShape(50), intensity = 0.35f)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text(
                        stringResource(
                            R.string.selected_of,
                            selectedContacts.size,
                            maxRecipients,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = ApuGoldInk,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (selectedContacts.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.share_contact_clear_selection),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { selectedIds.clear() }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = ApuBubbleAccentColor,
                    )
                }
            }
            if (selectedContacts.size >= maxRecipients) {
                Text(
                    stringResource(R.string.share_contact_limit_reached, maxRecipients),
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = ApuBubbleAccentColor,
                )
            }
            Spacer(Modifier.height(10.dp))

            ApuSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.contacts_search_placeholder),
                modifier = Modifier.fillMaxWidth(),
                trailing = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.action_close),
                                tint = ApuBubbleAccentColor,
                            )
                        }
                    }
                } else {
                    null
                },
            )
            Spacer(Modifier.height(8.dp))

            when {
                contacts.isEmpty() -> Text(
                    stringResource(R.string.scd_no_others),
                    modifier = Modifier.padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ApuBubbleMutedColor,
                )
                filteredContacts.isEmpty() -> Text(
                    stringResource(R.string.contacts_nothing_found, query),
                    modifier = Modifier.padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ApuBubbleMutedColor,
                )
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 330.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    items(items = filteredContacts, key = { it.id }) { contact ->
                        val checked = contact.id in selectedIds
                        val canSelect = checked || selectedContacts.size < maxRecipients
                        val rowShape = RoundedCornerShape(17.dp)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(if (canSelect) 1f else 0.52f)
                                .apuPremiumLift(
                                    elevation = if (checked) 4.dp else 1.dp,
                                    shape = rowShape,
                                    color = if (checked) ApuGold.copy(alpha = 0.22f) else ApuPremiumShadowColor,
                                )
                                .clip(rowShape)
                                .background(
                                    if (checked) Color(0xFFFFF1C9)
                                    else Color.White.copy(alpha = 0.88f),
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (checked) ApuGoldDeep.copy(alpha = 0.56f)
                                    else ApuBubbleAccentColor.copy(alpha = 0.16f),
                                    shape = rowShape,
                                )
                                .apuPremiumGloss(
                                    shape = rowShape,
                                    intensity = if (checked) 0.62f else 0.32f,
                                    topFraction = 0.52f,
                                )
                                .toggleable(
                                    value = checked,
                                    enabled = canSelect,
                                    role = Role.Checkbox,
                                    onValueChange = { next ->
                                        if (next) {
                                            if (selectedIds.size < maxRecipients && contact.id !in selectedIds) {
                                                selectedIds.add(contact.id)
                                            }
                                        } else {
                                            selectedIds.remove(contact.id)
                                        }
                                    },
                                )
                                .padding(horizontal = 11.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PeerAvatar(
                                name = contact.displayName.ifBlank {
                                    stringResource(R.string.chat_no_name)
                                },
                                avatarB64 = avatars[contact.id],
                                size = 44.dp,
                            )
                            Spacer(Modifier.width(11.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = contact.displayName.ifBlank {
                                        stringResource(R.string.chat_no_name)
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Medium,
                                    color = ApuBubbleTextColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (contact.username.isNotBlank()) {
                                    Text(
                                        text = "@" + contact.username.removePrefix("@"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ApuBubbleMutedColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            ContactSelectionMark(checked = checked)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactSelectionMark(checked: Boolean) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .size(25.dp)
            .clip(shape)
            .background(if (checked) apuGoldBrush() else Color.White.copy(alpha = 0.92f))
            .border(
                width = 1.2.dp,
                color = if (checked) ApuGoldDeep else ApuBubbleAccentColor.copy(alpha = 0.38f),
                shape = shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = ApuGoldInk,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}
