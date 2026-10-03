package com.vladimir.messenger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.domain.model.Contact

/**
 * Раунд 175: выбор адресата внутри APU для «Поделиться контактом»
 * (из «Контактов» и из списка чатов). Тап по абоненту - ему сразу
 * уходит ссылка контакта; он открывает её и добавляет человека одним тапом.
 */
@Composable
fun ShareContactChooserDialog(
    sharedName: String,
    contacts: List<Contact>,
    onDismiss: () -> Unit,
    onPick: (Contact) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Кому отправить") },
        text = {
            Column {
                Text(
                    "Ссылку контакта «$sharedName» получит выбранный абонент - он сможет добавить его в один тап.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (contacts.isEmpty()) {
                    Text(
                        "Пока нет других абонентов - добавьте ещё кого-нибудь и попробуйте снова.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp),
                    ) {
                        items(contacts, key = { it.id }) { to ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(to) }
                                    .padding(vertical = 10.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        to.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    if (to.username.isNotBlank()) {
                                        Text(
                                            "@" + to.username,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
