package com.vladimir.messenger.ui.screens.chat

import com.vladimir.messenger.domain.model.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Holds only the live tail and the older pages explicitly requested by scrolling.
 * Room remains the source of truth; this object is only the current screen window.
 */
internal class DirectChatHistoryPaging(
    private val pageSize: Int = CHAT_HISTORY_PAGE_SIZE,
) {
    private var latest: List<Message> = emptyList()
    private var older: List<Message> = emptyList()
    private var hasMore = true
    private var loading = false

    fun reset() {
        latest = emptyList()
        older = emptyList()
        hasMore = true
        loading = false
    }

    fun showLatest(rows: List<Message>): List<Message> {
        latest = rows
        if (rows.size < pageSize) hasMore = false
        return merged()
    }

    /** Returns null when already loading, at the end of history, or before first load. */
    suspend fun loadOlder(
        fetch: suspend (timestamp: Long, id: String, limit: Int) -> List<Message>,
    ): List<Message>? {
        if (loading || !hasMore) return null
        val cursor = older.firstOrNull() ?: latest.firstOrNull() ?: return null
        loading = true
        try {
            val page = fetch(cursor.timestamp, cursor.id, pageSize)
            if (page.isEmpty()) {
                hasMore = false
                return null
            }
            older = (page + older).distinctBy { it.id }
            if (page.size < pageSize) hasMore = false
            return merged()
        } finally {
            loading = false
        }
    }

    fun requestOlder(
        scope: CoroutineScope,
        fetch: suspend (Long, String, Int) -> List<Message>,
        onLoaded: (List<Message>) -> Unit,
    ) {
        scope.launch {
            try {
                loadOlder(fetch)?.let(onLoaded)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("ChatDetailVM", "Older local history read failed", error)
            }
        }
    }

    private fun merged(): List<Message> =
        (older + latest)
            .distinctBy { it.id }
            .sortedWith(compareBy<Message> { it.timestamp }.thenBy { it.id })
}
