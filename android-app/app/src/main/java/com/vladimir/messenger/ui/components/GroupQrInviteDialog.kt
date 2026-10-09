package com.vladimir.messenger.ui.components

import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.R
// =============================================================================
// GROUPQRINVITEDIALOG.KT — «Пригласить по QR коду» (раунд 213)
// =============================================================================
// Владелец: в шапке группы и канала — «три точки», в них пункт
// «Пригласить по QR коду». QR несёт КОРОТКУЮ https-ссылку /s/<код>
// (тот же механизм, что у QR «Мой код», раунд 208), поэтому:
//  - с APU — камера/сканер открывает приложение сразу на вступлении
//    (App Links autoVerify, путь /s/);
//  - без APU — страница сервиса: «Группа в APU» / «Канал в APU»,
//    кнопки «Открыть в APU» и «Установить APU».
// Ссылку строит GroupRepository.inviteQrLink (бессрочное приглашение).
// =============================================================================

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.util.QrCodeGenerator

@Composable
fun GroupQrInviteDialog(
    /** Честная подпись: канал приглашает подписаться, группа — войти. */
    isChannel: Boolean,
    /** Короткая ссылка-приглашение; null — бессрочной ссылки ещё нет. */
    link: String?,
    loading: Boolean,
    onDismiss: () -> Unit,
) {
    ApuSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.channel_invite_qr)) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                when {
                    loading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 3.dp,
                        )
                    }
                    link == null -> {
                        Text(
                            if (isChannel) {
                                stringResource(R.string.gqr_channel_no_link)
                            } else {
                                stringResource(R.string.gqr_group_no_link)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    else -> {
                        // Код на белом поле: чем крупнее модули, тем быстрее
                        // его ловит камера другого телефона.
                        val bitmap = remember(link) { QrCodeGenerator.generateQrCode(link) }
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.White)
                                .padding(10.dp),
                        ) {
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = stringResource(R.string.admin_qr_desc),
                                    modifier = Modifier.size(250.dp),
                                )
                            } else {
                                Text(
                                    stringResource(R.string.admin_qr_unavailable),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF1E2430),
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (isChannel) {
                                stringResource(R.string.gqr_channel_hint)
                            } else {
                                stringResource(R.string.gqr_group_hint)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(6.dp))
                        // Ссылка видна и копируется: QR ловит не всякая камера.
                        SelectionContainer {
                            Text(
                                link,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            ApuTextAction(label = stringResource(R.string.action_close), onClick = onDismiss)
        },
    )
}
