package com.vladimir.messenger.ui.screens.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.R
import com.vladimir.messenger.ui.components.ApuActionBubble

/**
 * Пункт «Ответить» в меню действий над сообщением личного чата.
 *
 * Владелец 2026-11-17: «нужно, чтобы можно было в личных сообщениях тоже
 * отвечать на конкретное сообщение — добавить эту функцию в меню». До этого
 * ответ существовал только свайпом по пузырю, и найти его было нельзя.
 *
 * В группах и каналах пункт стоит первым — здесь ровно так же, с той же
 * подписью и иконкой: привычка «ответ — сверху меню» должна работать в обоих
 * экранах. Отдельный файл держит размер `ChatDetailScreen.kt` в потолке
 * `check-god-files.py`.
 */
@Composable
internal fun ChatReplyActionBubble(onPick: () -> Unit) {
    ApuActionBubble(
        label = stringResource(R.string.group_reply),
        icon = Icons.AutoMirrored.Filled.Reply,
        onClick = onPick,
    )
}
