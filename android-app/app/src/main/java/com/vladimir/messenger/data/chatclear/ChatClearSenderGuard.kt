package com.vladimir.messenger.data.chatclear

import android.content.Context
import com.vladimir.messenger.data.local.dao.ChatDao
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Resolve direct-chat scope once, then test durable tombstones before each file packet. */
@Singleton
class ChatClearSenderGuard @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chats: ChatDao,
) {
    suspend fun prepare(transfer: FileTransferEntity): () -> Boolean {
        val peer = transfer.peerNodeId
        if (peer.isBlank() || chats.getChatByContactId(peer)?.id != transfer.chatId ||
            transfer.displayName.startsWith("apu-story-video-", ignoreCase = true) ||
            transfer.displayName.startsWith("apu-avatar-video-", ignoreCase = true)) return { false }
        val alias = "file-${transfer.transferId}"
        return {
            ChatClearRegistry.isCleared(context, peer, transfer.messageId) ||
                ChatClearRegistry.isCleared(context, peer, alias)
        }
    }
}
