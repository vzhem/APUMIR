package com.vladimir.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
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
import androidx.compose.material3.TextButton
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
    onSaveClick: (() -> Unit)? = null,
    /** Раунд 43: файл превью картинки - показываем фото прямо в пузыре. */
    previewFile: java.io.File? = null,
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
            // Превью картинки: исходящее доступно сразу, входящее - после
            // приёма (COMPLETE).
            val previewBitmap = androidx.compose.runtime.remember(previewFile) {
                previewFile?.let { f ->
                    runCatching {
                        val opts = android.graphics.BitmapFactory.Options().apply {
                            inSampleSize = 2
                        }
                        android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)
                    }.getOrNull()
                }
            }
            if (previewBitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = previewBitmap.asImageBitmap(),
                    contentDescription = transfer.displayName,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .padding(bottom = 8.dp),
                )
            }
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
            if (transfer.state == "TRANSFERRING" && transfer.transferredBytes > 0) {
                val elapsedMs = System.currentTimeMillis() - transfer.createdAtMs
                if (elapsedMs > 1000) {
                    val bytesPerSec = (
                        transfer.transferredBytes.toDouble() * 1_000.0 / elapsedMs.toDouble()
                    ).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()
                    if (bytesPerSec > 0) {
                        val remaining = transfer.totalBytes - transfer.transferredBytes
                        val etaSec = remaining / bytesPerSec
                        Text(
                            "${formatSpeed(bytesPerSec)} · осталось ~${formatEta(etaSec)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.7f),
                        )
                    }
                }
            }
            val progress = transferProgress(transfer)
            if (progress != null && transfer.state != "COMPLETE" && transfer.state != "FAILED") {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
            }
            if (transfer.state == "COMPLETE" && transfer.direction == "INCOMING" && onSaveClick != null) {
                TextButton(
                    onClick = onSaveClick,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 0.dp,
                        vertical = 0.dp,
                    ),
                ) {
                    Text("Сохранить в папку", style = MaterialTheme.typography.labelMedium)
                }
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
        "WAITING_RECIPIENT" -> "Ждём получателя онлайн"
        else -> transfer.state
    }
}

private fun formatSpeed(bytesPerSec: Long): String = when {
    bytesPerSec >= 1024 * 1024 -> "%.1f МБ/с".format(bytesPerSec / (1024.0 * 1024.0))
    bytesPerSec >= 1024 -> "%.0f КБ/с".format(bytesPerSec / 1024.0)
    else -> "$bytesPerSec Б/с"
}

private fun formatEta(seconds: Long): String = when {
    seconds >= 3600 -> "${seconds / 3600} ч ${seconds % 3600 / 60} мин"
    seconds >= 60 -> "${seconds / 60} мин ${seconds % 60} с"
    else -> "$seconds с"
}

private fun transferProgress(transfer: FileTransferEntity): Float? = when {
    transfer.chunkCount <= 0 -> null
    transfer.state == "COMPLETE" -> 1f
    transfer.totalBytes <= 0 -> null
    else -> (transfer.transferredBytes.toFloat() / transfer.totalBytes.toFloat())
        .coerceIn(0f, 1f)
}
