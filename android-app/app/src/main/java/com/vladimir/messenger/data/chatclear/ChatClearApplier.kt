package com.vladimir.messenger.data.chatclear

import android.content.Context
import com.vladimir.messenger.data.local.dao.ChatDao
import com.vladimir.messenger.data.schedule.DeferredInboxStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Production storage adapter. Authentication is enforced by ChatClearReceiver before this is called. */
internal class ChatClearApplier @Inject constructor(
    @ApplicationContext context: Context,
    private val chatDao: ChatDao,
    cleanup: ChatClearLocalCleanup,
) : ChatClearReceiver.Apply {
    private val context = context.applicationContext
    private val delegate = ChatClearApply(
        ownerId = { DeferredInboxStore.owner(this.context) },
        chatForPeer = { peer -> chatDao.getChatByContactId(peer)?.id },
        tombstone = { owner, peer, ids -> ChatClearRegistry.store(this.context).remember(owner, peer, ids) },
        cleanup = { chat, ids -> cleanup.apply(chat, ids) },
        relatedIds = { chat, ids -> ChatClearFileAliases(cleanup.transferDao).relatedIds(chat, ids) },
    )
    override suspend fun apply(peer: String, batch: ChatClearWire.Batch): Boolean = delegate.apply(peer, batch)
}
