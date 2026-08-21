package com.vladimir.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vladimir.messenger.data.file.FileTransferRouter
import com.vladimir.messenger.data.local.entity.FileTransferEntity

/** Chat bubble for a direct file transfer with honest state and progress. */
@Composable
fun FileTransferBubble(
    transfer: FileTransferEntity,
    isFromMe: Boolean,
    modifier: Modifier = Modifier,
) {
    val background = if (isFromMe) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isFromMe) {
        Color.White
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isFromMe) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .background(background, RoundedCornerShape(16.dp))
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = iconFor(transfer.mediaType),
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        transfer.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = contentColor,
                    )
                    Text(
                        FileTransferRouter.formatSize(transfer.totalBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.8f),
                    )
                }
            }
            Spacer(modifier = Modifier.padding(2.dp))
            Text(
                stateLabel(transfer),
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.9f),
            )
            val progress = transferProgress(transfer)
            if (progress != null && transfer.state != "COMPLETE" && transfer.state != "FAILED") {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
            }
        }
    }
}

private fun iconFor(mediaType: String): ImageVector = when {
    mediaType.startsWith("image/") -> Icons.Default.Image
    mediaType.startsWith("video/") -> Icons.Default.Movie
    mediaType.startsWith("audio/") -> Icons.Default.MusicNote
    mediaType == "application/pdf" -> Icons.Default.Description
    else -> Icons.Default.InsertDriveFile
}

private fun stateLabel(transfer: FileTransferEntity): String {
    val ofChunks = if (transfer.chunkCount > 0) {
        " ${transfer.completedChunks}/${transfer.chunkCount}"
    } else {
        ""
    }
    return when (transfer.state) {
        "PREPARING" -> "Подготовка…"
        "PREPARED" -> "В очереди (дождётся получателя)"
        "OFFERED" -> "Входящий файл…"
        "TRANSFERRING" -> if (transfer.direction == "OUTGOING") {
            "Передача…$ofChunks"
        } else {
            "Приём…$ofChunks"
        }
        "VERIFYING" -> "Проверка…"
        "SENT" -> "Отправлено, ждём подтверждение"
        "COMPLETE" -> if (transfer.direction == "OUTGOING") "Доставлено ✓" else "Сохранено ✓"
        "FAILED" -> "Ошибка (${transfer.errorCode ?: "неизвестно"})"
        "EXPIRED" -> "Срок истёк"
        else -> transfer.state
    }
}

private fun transferProgress(transfer: FileTransferEntity): Float? = when {
    transfer.chunkCount <= 0 -> null
    transfer.state == "COMPLETE" -> 1f
    transfer.totalBytes <= 0 -> null
    else -> (transfer.transferredBytes.toFloat() / transfer.totalBytes.toFloat())
        .coerceIn(0f, 1f)
}
