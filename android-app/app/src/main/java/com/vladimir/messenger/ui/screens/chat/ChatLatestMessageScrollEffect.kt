package com.vladimir.messenger.ui.screens.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/** Position the bounded chat window directly at its newest message on entry. */
@Composable
internal fun ChatLatestMessageScrollEffect(
    listState: LazyListState,
    scrollToBottom: Boolean,
    messageCount: Int,
    onScrolledToBottom: () -> Unit,
) {
    LaunchedEffect(scrollToBottom, messageCount) {
        if (scrollToBottom && messageCount > 0) {
            listState.scrollToItem(messageCount - 1)
            onScrolledToBottom()
        }
    }
}
