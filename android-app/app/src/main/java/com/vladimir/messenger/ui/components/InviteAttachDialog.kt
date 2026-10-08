package com.vladimir.messenger.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Вопрос о приложении APK к приглашению.
 *
 * Используется в контактах, QR, рейтинге и списке чатов, поэтому диалог сам
 * задаёт единый стиль: светлая APU-карточка, золотой акцент и заметный выбор,
 * а не стандартное системное окно поверх обоев.
 */
@Composable
fun InviteAttachDialog(
    title: String,
    onDismiss: () -> Unit,
    onShare: (attachApk: Boolean) -> Unit,
) {
    var attachApk by remember { mutableStateOf(true) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp)
                .padding(horizontal = 20.dp),
            shape = RoundedCornerShape(28.dp),
            color = ApuBubbleSurfaceColor,
            contentColor = ApuBubbleTextColor,
            tonalElevation = 0.dp,
            shadowElevation = 16.dp,
            border = BorderStroke(
                1.dp,
                ApuBubbleAccentColor.copy(alpha = 0.42f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .background(ApuBubbleAccentColor.copy(alpha = 0.14f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.PersonAdd,
                            contentDescription = null,
                            tint = ApuBubbleAccentColor,
                            modifier = Modifier.size(25.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = ApuBubbleTextColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "Отправьте приглашение удобным способом",
                            style = MaterialTheme.typography.bodySmall,
                            color = ApuBubbleMutedColor,
                        )
                    }
                }

                Text(
                    text = "Ссылка на приглашение будет добавлена к сообщению. При желании приложите APK, чтобы получатель мог установить APU сразу.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ApuBubbleTextColor,
                )

                // Переключатель сделан отдельным интерактивным пузырём: вся
                // строка нажимается, а состояние не теряется на светлом фоне.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            ApuBubbleAccentColor.copy(alpha = if (attachApk) 0.12f else 0.05f),
                            RoundedCornerShape(18.dp),
                        )
                        .border(
                            1.dp,
                            ApuBubbleAccentColor.copy(alpha = if (attachApk) 0.48f else 0.24f),
                            RoundedCornerShape(18.dp),
                        )
                        .clickable(role = Role.Checkbox) { attachApk = !attachApk }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ApuCircleCheckIndicator(checked = attachApk)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Приложить установочный файл",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = ApuBubbleTextColor,
                        )
                        Text(
                            text = "APK около 40 МБ — удобно, если у получателя ещё нет APU.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ApuBubbleMutedColor,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ApuPremiumContentButton(
                        onClick = onDismiss,
                        style = DiagnosticsActionStyle.QUIET,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Отмена", fontWeight = FontWeight.SemiBold)
                    }
                    ApuPremiumContentButton(
                        onClick = { onShare(attachApk) },
                        style = DiagnosticsActionStyle.PRIMARY,
                        modifier = Modifier.weight(1.2f),
                    ) {
                        Text("Поделиться", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
