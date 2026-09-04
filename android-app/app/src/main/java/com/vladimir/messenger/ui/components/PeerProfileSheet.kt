package com.vladimir.messenger.ui.components

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.ui.theme.AvatarStore

/**
 * Карточка профиля собеседника — открывается тапом по его имени в переписке.
 *
 * Показывает то, что телефон уже знает о человеке: аватар, имя, @никнейм,
 * в сети он или нет, и его узел в сети. Ничего не запрашивает по сети — только
 * то, что уже лежит рядом с чатом, поэтому карточка открывается мгновенно.
 *
 * Идентификатор узла можно скопировать: он нужен, чтобы позвать человека в
 * группу или разобраться, почему сообщения не идут.
 */
@Composable
fun PeerProfileSheet(
    name: String,
    contactId: String,
    isOnline: Boolean,
    onDismiss: () -> Unit,
    /** @никнейм без собаки; пусто - строки не будет. */
    username: String = "",
    onRename: (() -> Unit)? = null,
    onCall: (() -> Unit)? = null,
    onCopyId: (() -> Unit)? = null,
) {
    val avatars by AvatarStore.avatars.collectAsState()
    val bitmap = AvatarBitmaps.rememberAvatar(avatars[contactId])

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
        title = null,
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Аватар: присланная картинка, иначе буквы имени на круге.
                val shown = bitmap
                if (shown != null) {
                    Image(
                        bitmap = shown.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(88.dp)
                            .clip(CircleShape)
                            .border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                                shape = CircleShape,
                            ),
                    )
                } else {
                    Avatar(name = name, size = 88)
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (username.isNotBlank()) {
                    Text(
                        "@" + username.removePrefix("@"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    if (isOnline) "в сети" else "не в сети",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isOnline) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )

                Spacer(Modifier.height(14.dp))

                // Узел в сети. Длинный, поэтому в пузыре и в две строки: его
                // копируют, когда разбираются с доставкой.
                if (contactId.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFFF5F7FA).copy(alpha = 0.92f))
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                                shape = RoundedCornerShape(14.dp),
                            )
                            .then(
                                if (onCopyId != null) {
                                    Modifier.clickable(onClick = onCopyId)
                                } else {
                                    Modifier
                                }
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Узел в сети",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF5A6472),
                                )
                                Text(
                                    contactId,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF1E2430),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (onCopyId != null) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = "Скопировать",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }

                if (onRename != null || onCall != null) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        if (onCall != null) {
                            TextButton(onClick = onCall) {
                                Icon(Icons.Default.Call, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Позвонить")
                            }
                        }
                        if (onRename != null) {
                            TextButton(onClick = onRename) {
                                Icon(Icons.Default.Edit, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Переименовать")
                            }
                        }
                    }
                }
            }
        },
    )
}
