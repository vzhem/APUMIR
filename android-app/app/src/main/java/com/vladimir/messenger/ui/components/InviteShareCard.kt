package com.vladimir.messenger.ui.components

// =============================================================================
// INVITESHARECARD.KT — один и тот же блок «поделиться приглашением» везде
// =============================================================================
// QR-код, выделяемая ссылка и две кнопки: «Копировать» и «Поделиться».
// Одинаковый вид во всех экранах, чтобы не искать каждый раз новое место.
// =============================================================================

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.util.AppShare
import com.vladimir.messenger.util.QrCodeGenerator

@Composable
fun InviteShareCard(
    link: String,
    displayName: String,
    modifier: Modifier = Modifier,
    qrSizeDp: Int = 200,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val qrBitmap = remember(link) { QrCodeGenerator.generateQrCode(link, 512) }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (qrBitmap != null) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = "QR-код приглашения",
                modifier = Modifier.size(qrSizeDp.dp),
            )
        } else {
            Text("QR недоступен", style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(12.dp))

        // SelectionContainer: ссылку можно выделить пальцем и скопировать.
        SelectionContainer {
            Text(link, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(link)) }) {
                Text("Копировать")
            }
            Button(onClick = { AppShare.shareInvite(context, displayName, link) }) {
                Text("Поделиться")
            }
        }
    }
}
