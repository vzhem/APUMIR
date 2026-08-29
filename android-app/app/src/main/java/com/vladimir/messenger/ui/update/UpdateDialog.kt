package com.vladimir.messenger.ui.update

// =============================================================================
// UPDATEDIALOG.KT
// =============================================================================
// Окно обновления в едином стиле APU: наша подложка внутри, золотая рамка
// по краю, скруглённые углы и кнопки.
// =============================================================================

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.vladimir.messenger.ui.components.ChatWallpaper

@Composable
fun UpdateDialog(
    releaseInfo: com.vladimir.messenger.service.UpdateChecker.ReleaseInfo,
    isDownloading: Boolean,
    onDownloadClick: () -> Unit,
    onDismissClick: () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    val gold = MaterialTheme.colorScheme.primary

    Dialog(onDismissRequest = { if (!isDownloading) onDismissClick() }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .border(
                    width = 2.dp,
                    brush = Brush.verticalGradient(
                        listOf(gold, gold.copy(alpha = 0.35f))
                    ),
                    shape = shape,
                )
                .background(MaterialTheme.colorScheme.surface),
        ) {
            // Наша подложка внутри окна + лёгкая вуаль для читаемости.
            Box(modifier = Modifier.matchParentSize()) {
                ChatWallpaper()
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
                        )
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Доступно обновление",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = gold,
                )
                Text(
                    "Версия: ${releaseInfo.version}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                if (releaseInfo.releaseNotes.isNotBlank()) {
                    Text(
                        "Что нового:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        releaseInfo.releaseNotes,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                if (isDownloading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        "Скачивание...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = onDismissClick,
                        enabled = !isDownloading,
                    ) {
                        Text("Позже")
                    }
                    Spacer(modifier = Modifier.height(0.dp))
                    Button(
                        onClick = onDownloadClick,
                        enabled = !isDownloading,
                    ) {
                        Text(if (isDownloading) "Скачивается..." else "Обновить")
                    }
                }
            }
        }
    }
}
