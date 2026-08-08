package com.vladimir.messenger.ui.update

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun UpdateDialog(
    releaseInfo: com.vladimir.messenger.service.UpdateChecker.ReleaseInfo,
    isDownloading: Boolean,
    onDownloadClick: () -> Unit,
    onDismissClick: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismissClick() },
        title = {
            Text(
                "Доступно обновление",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Версия: ${releaseInfo.version}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                
                if (releaseInfo.releaseNotes.isNotBlank()) {
                    Text(
                        "Что нового:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        releaseInfo.releaseNotes,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                
                if (isDownloading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Скачивание...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDownloadClick,
                enabled = !isDownloading
            ) {
                Text(if (isDownloading) "Скачивается..." else "Обновить")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissClick,
                enabled = !isDownloading
            ) {
                Text("Позже")
            }
        }
    )
}
