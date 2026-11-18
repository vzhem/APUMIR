package com.vladimir.messenger.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.vladimir.messenger.data.notification.OpenChatGate

/**
 * Отмечает, что человек сейчас в этом чате, пока экран показан.
 *
 * Одна строка на экране чата и один общий «страж»: смотреть на lifecycle окна
 * отдельно не нужно, композиция сама сообщает о появлении и уходе экрана. В
 * фоне уведомления не глушатся — это решает `OpenChatGate.isReading` вместе с
 * признаком «приложение на экране».
 */
@Composable
fun TrackOpenChat(chatId: String) {
    DisposableEffect(chatId) {
        OpenChatGate.opened(chatId)
        onDispose { OpenChatGate.closed(chatId) }
    }
}
