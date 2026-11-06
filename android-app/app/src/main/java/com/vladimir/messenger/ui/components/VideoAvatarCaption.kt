package com.vladimir.messenger.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.vladimir.messenger.data.profile.VideoAvatarStore

/**
 * Подпись видеоаватара под именем в профиле.
 *
 * Подпись приходит из того же редактора, что и у видеоисторий (до 200
 * символов), и остаётся на этом устройстве: собеседникам уходит только
 * кадр-постер обычным аватаром, как и раньше. Пока подписи нет — строки нет
 * вовсе, пустое место в профиле не появляется.
 */
@Composable
fun VideoAvatarCaptionLine(modifier: Modifier = Modifier) {
    val caption by VideoAvatarStore.caption.collectAsState()
    if (caption.isBlank()) return
    Text(
        text = caption,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth(),
    )
}
