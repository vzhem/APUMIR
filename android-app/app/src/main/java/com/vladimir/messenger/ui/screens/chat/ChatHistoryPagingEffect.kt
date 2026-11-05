package com.vladimir.messenger.ui.screens.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collect

/** Ask for another bounded page only while the user is actively at the top. */
@Composable
internal fun ChatHistoryPagingEffect(
    listState: LazyListState,
    onLoadOlder: () -> Unit,
) {
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.isScrollInProgress }
            .collect { (firstVisible, scrolling) ->
                if (firstVisible == 0 && scrolling) onLoadOlder()
            }
    }
}
