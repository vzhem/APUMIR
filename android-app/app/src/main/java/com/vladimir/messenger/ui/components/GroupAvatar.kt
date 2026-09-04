package com.vladimir.messenger.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vladimir.messenger.ui.theme.AvatarStore
import kotlin.math.abs

/** Ключ группового аватара в общем хранилище картинок. */
private const val GROUP_AVATAR_PREFIX = "g:"

/**
 * Круглый аватар группы или канала.
 *
 * Картинку берём из общего хранилища (её присылает владелец группы). Если
 * картинки нет — рисуем первую букву названия на цветном круге, цвет
 * подбирается по идентификатору, поэтому у каждой группы он свой и постоянный.
 *
 * Разбор картинки идёт через [AvatarBitmaps] — в фоне и с общим кэшем. Делать
 * это в отрисовке нельзя: списки показывают десятки строк сразу.
 */
@Composable
fun GroupAvatar(
    groupId: String,
    title: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val avatars by AvatarStore.avatars.collectAsState()
    val bitmap = AvatarBitmaps.rememberAvatar(avatars[GROUP_AVATAR_PREFIX + groupId])

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (bitmap == null) colorFor(groupId) else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        val shown = bitmap
        if (shown != null) {
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(size).clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = initialsOf(title),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.38f).sp,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/** Одна-две первые буквы названия: «тест группа» -> «ТГ». */
private fun initialsOf(title: String): String {
    val words = title.trim().split(' ', '-', '_').filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(1).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

/** Постоянный цвет по идентификатору: одна и та же группа всегда одного цвета. */
private fun colorFor(id: String): Color {
    val palette = listOf(
        Color(0xFF6A4CC4),
        Color(0xFF2E7D32),
        Color(0xFF1565C0),
        Color(0xFFEF6C00),
        Color(0xFFAD1457),
        Color(0xFF00838F),
        Color(0xFF5D4037),
        Color(0xFF7B1FA2),
    )
    if (id.isEmpty()) return palette[0]
    return palette[abs(id.hashCode()) % palette.size]
}
