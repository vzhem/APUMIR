package com.vladimir.messenger.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.service.UpdateChecker
import com.vladimir.messenger.ui.components.ApuBubbleMutedColor
import com.vladimir.messenger.ui.components.ApuPremiumButton
import com.vladimir.messenger.ui.components.ApuPremiumIconTile
import com.vladimir.messenger.ui.components.ApuSettingsDialog
import com.vladimir.messenger.ui.components.ApuTextAction
import com.vladimir.messenger.ui.components.DiagnosticsActionStyle

@Composable
fun UpdateDialog(
    releaseInfo: UpdateChecker.ReleaseInfo,
    isDownloading: Boolean,
    onDownloadClick: () -> Unit,
    onDismissClick: () -> Unit,
) {
    ApuSettingsDialog(
        onDismissRequest = { if (!isDownloading) onDismissClick() },
        icon = {
            ApuPremiumIconTile(
                icon = Icons.Default.SystemUpdate,
                size = 34.dp,
                corner = 10.dp,
            )
        },
        title = {
            Text(
                "Доступно обновление",
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
                    "Версия: ${releaseInfo.version}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                if (releaseInfo.releaseNotes.isNotBlank()) {
                    Text(
                        "Что нового:",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        releaseInfo.releaseNotes,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                if (isDownloading) {
                    Text(
                        "Скачивание обновления…",
                        style = MaterialTheme.typography.bodySmall,
                        color = ApuBubbleMutedColor,
                    )
                }
            }
        },
        dismissButton = if (isDownloading) {
            null
        } else ({
            ApuTextAction(
                label = "Позже",
                onClick = onDismissClick,
            )
        }),
        confirmButton = {
            ApuPremiumButton(
                label = if (isDownloading) "Скачивается…" else "Обновить",
                onClick = onDownloadClick,
                style = DiagnosticsActionStyle.PRIMARY,
                enabled = !isDownloading,
                progress = isDownloading,
            )
        },
    )
}
