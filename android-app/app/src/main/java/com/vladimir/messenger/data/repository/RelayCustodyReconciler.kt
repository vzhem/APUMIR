package com.vladimir.messenger.data.repository

/** A snapshot and its cleanup callback refer to the same native engine lifetime. */
internal class CustodySnapshot(val ids: List<String>, val confirm: (String) -> Unit)

/**
 * A Room ACK can be committed just before Android dies, leaving its native
 * relay copy behind. Compare bounded pages of locally-owned native IDs with
 * Room; only terminal outgoing rows may request cleanup. Missing, incoming and
 * nonterminal rows stay untouched. Repeating a page after an error is safe.
 *
 * IDs are already sorted as UTF-8 bytes by the native queue. Insertion or
 * removal while paging can delay a row until the next sweep, but cannot make
 * it terminal or authorize deletion. Native copies absent from the in-memory
 * snapshot are not covered by this repair and still expire normally.
 */
internal class RelayCustodyReconciler(
    private val snapshot: () -> CustodySnapshot?,
    private val terminalIds: suspend (List<String>) -> List<String>,
) {
    private var after: String? = null

    /** Returns true when another bounded page is available in this sweep. */
    suspend fun reconcile(): Boolean {
        val current = snapshot() ?: return false
        val cursor = after?.let { OutboxPosition(0L, it) }
        val start = if (cursor == null) 0 else current.ids.indexOfFirst { id ->
            !OutboxPosition(0L, id).atOrBefore(cursor)
        }
        if (start < 0 || start >= current.ids.size) {
            after = null
            return false
        }
        val page = current.ids.drop(start).take(PAGE_SIZE)
        // Do not advance the cursor if the database or callback fails. The
        // next cycle retries the same page, including any already completed IDs.
        val terminal = terminalIds(page).toHashSet()
        page.filter { it in terminal }.forEach(current.confirm)
        val more = start + page.size < current.ids.size
        after = if (more) page.last() else null
        return more
    }

    private companion object { const val PAGE_SIZE = 32 }
}
