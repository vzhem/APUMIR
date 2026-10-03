package com.vladimir.messenger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Раунд 200: единый вопрос «прикладывать ли установочный APK» перед
 * отправкой приглашения наружу. Владелец: «на случай, у кого мало
 * интернета трафика» - файл весит около 40 МБ, текст со ссылкой -
 * копейки. По умолчанию галочка ВКЛ: получатель извне ставит приложение
 * прямо из входящего сообщения (решение владельца, р197-198).
 */
@Composable
fun InviteAttachDialog(
    title: String,
    onDismiss: () -> Unit,
    onShare: (attachApk: Boolean) -> Unit,
) {
    var attachApk by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    "К сообщению можно приложить установочный файл APU " +
                        "(около 40 МБ): получатель поставит приложение прямо " +
                        "из входящего сообщения. Если у него мало трафика - " +
                        "снимите галочку, уйдёт только текст со ссылкой.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { attachApk = !attachApk }
                        .padding(horizontal = 4.dp),
                ) {
                    Checkbox(checked = attachApk, onCheckedChange = { attachApk = it })
                    Text(
                        "Приложить установочный файл (APK)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onShare(attachApk) }) { Text("Поделиться") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
