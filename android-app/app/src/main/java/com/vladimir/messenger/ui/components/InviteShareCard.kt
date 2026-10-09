package com.vladimir.messenger.ui.components

import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.R
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.data.link.ShortShare
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
    val qrBitmap = remember(link) { QrCodeGenerator.generateQrCode(link) }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (qrBitmap != null) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.admin_qr_desc),
                modifier = Modifier.size(qrSizeDp.dp),
            )
        } else {
            Text(stringResource(R.string.admin_qr_unavailable), style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(12.dp))

        // SelectionContainer: ссылку можно выделить пальцем и скопировать.
        SelectionContainer {
            Text(link, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(12.dp))

        // Раунд 200: галочка «приложить APK» - владелец: «на случай, у кого
        // мало интернета трафика». По умолчанию ВКЛ.
        var attachApk by remember { mutableStateOf(true) }
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier
                .clickable { attachApk = !attachApk }
                .padding(horizontal = 4.dp),
        ) {
            ApuPremiumCheckbox(checked = attachApk, onCheckedChange = { attachApk = it })
            Text(
                stringResource(R.string.contacts_attach_apk),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ApuPremiumContentButton(
                onClick = { clipboard.setText(AnnotatedString(link)) },
                style = DiagnosticsActionStyle.QUIET,
            ) {
                Text(stringResource(R.string.share_copy_btn))
            }
            // Наружу - короткой https-ссылкой (кликабельна везде); QR выше
            // остаётся прежней ссылкой, сканер разбирает её без сети.
            ApuPremiumContentButton(
                onClick = { ShortShare.shareInvite(context, displayName, link, attachApk) },
                style = DiagnosticsActionStyle.PRIMARY,
            ) {
                Text(stringResource(R.string.admin_share))
            }
        }
    }
}
