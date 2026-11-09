package com.vladimir.messenger.data.chatclear

import android.content.Context
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.data.schedule.DeferredInboxStore

/** Revisit durable cleanup intents in bounded pages to catch late in-flight writes. */
internal class ChatClearFileSweep(context: Context, private val db: AppDatabase, private val journal: ChatClearFileJournal) {
    private val context = context.applicationContext
    private val cleaner = ChatClearPrivateFileCleaner(this.context)
    private var owner = ""
    private var cursor: String? = null
    private var nextAt = 0L

    suspend fun run(currentOwner: String, now: Long) {
        if (owner != currentOwner) { owner = currentOwner; cursor = null; nextAt = 0L }
        if (currentOwner.isBlank() || now < nextAt || !journal.exists()) return
        nextAt = now + INTERVAL_MS
        val rows = journal.page(currentOwner, cursor)
        if (rows.isEmpty()) { cursor = null; nextAt = now + CYCLE_MS; return }
        for (entry in rows) {
            if (DeferredInboxStore.owner(context) != currentOwner) return
            val chat = db.chatDao().getChatById(entry.chat)
            val transfer = db.fileTransferDao().getTransfer(entry.transferId)
            // A reused ID or remapped chat is never authority to touch another file.
            if ((chat == null || chat.contactId == entry.peer) &&
                (transfer == null || (transfer.chatId == entry.chat && transfer.peerNodeId == entry.peer &&
                    transfer.messageId == entry.messageId &&
                    !transfer.displayName.startsWith("apu-story-video-", ignoreCase = true) &&
                    !transfer.displayName.startsWith("apu-avatar-video-", ignoreCase = true)))
            ) {
                if (DeferredInboxStore.owner(context) != currentOwner) return
                check(cleaner.removeId(entry.transferId)) { "Cannot finish private file cleanup" }
            }
            cursor = entry.transferId
        }
    }

    companion object {
        private const val INTERVAL_MS = 30_000L
        private const val CYCLE_MS = 15L * 60 * 1000
    }
}
