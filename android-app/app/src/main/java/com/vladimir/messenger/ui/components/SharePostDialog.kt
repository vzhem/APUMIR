package com.vladimir.messenger.ui.components

// =============================================================================
// SHAREPOSTDIALOG.KT — репост поста с фото в два шага: текст, потом фото
// =============================================================================
// Telegram, WhatsApp и почта при получении нескольких файлов выбрасывают
// подпись: получателю приходили одни фотографии, без текста и ссылки
// (владелец, 2026-09-09). Надёжный способ один - две отправки. Это окно ведёт
// человека по шагам и не даёт забыть вторую половину.
// =============================================================================

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * @param step что уже отправлено: 0 - ничего, 1 - текст, 2 - и фото.
 * @param photoCount сколько фотографий уйдёт вторым шагом.
 */
@Composable
fun SharePostDialog(
    step: Int,
    photoCount: Int,
    onShareText: () -> Unit,
    onSharePhotos: () -> Unit,
    onDismiss: () -> Unit,
) {
    val photosWord = when {
        photoCount % 10 == 1 && photoCount % 100 != 11 -> "фотографию"
        photoCount % 10 in 2..4 && photoCount % 100 !in 12..14 -> "фотографии"
        else -> "фотографий"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Поделиться постом") },
        text = {
            Column {
                Text(
                    "Мессенджеры не принимают текст вместе с несколькими фото, " +
                        "поэтому пост уходит в два сообщения: сначала текст со " +
                        "ссылкой, потом $photoCount $photosWord. Выберите один и " +
                        "тот же чат оба раза.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                if (step < 1) {
                    Button(onClick = onShareText, modifier = Modifier.fillMaxWidth()) {
                        Text("1. Отправить текст и ссылку")
                    }
                } else {
                    OutlinedButton(onClick = onShareText, modifier = Modifier.fillMaxWidth()) {
                        Text("1. Текст отправлен - повторить")
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (step < 2) {
                    Button(
                        onClick = onSharePhotos,
                        enabled = step >= 1,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("2. Отправить $photoCount $photosWord")
                    }
                } else {
                    OutlinedButton(onClick = onSharePhotos, modifier = Modifier.fillMaxWidth()) {
                        Text("2. Фото отправлены - повторить")
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Текст поста уже скопирован в буфер обмена - если мессенджер " +
                        "его потерял, просто вставьте.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(if (step >= 2) "Готово" else "Закрыть") }
        },
    )
}
