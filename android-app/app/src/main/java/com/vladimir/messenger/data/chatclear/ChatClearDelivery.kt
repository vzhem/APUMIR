package com.vladimir.messenger.data.chatclear

import android.content.Context
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.data.local.dao.ChatDao
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Authenticated incoming clear commands and peer-scoped application receipts. */
@Singleton
class ChatClearDelivery @Inject constructor(
    @ApplicationContext context: Context,
    private val chatDao: ChatDao,
    db: AppDatabase,
) {
    private val outbox = ChatClearOutbox(File(context.noBackupFilesDir, "chat_clear_outbox.v1"))
    private val apply = ChatClearApplier(context, chatDao, ChatClearLocalCleanup(db))
    private val protocol = ChatClearProtocol(outbox, apply) { peer, ack ->
        val chat = chatDao.getChatByContactId(peer) ?: return@ChatClearProtocol
        val id = UUID.nameUUIDFromBytes("apu-clear-ack:$peer:$ack".toByteArray(Charsets.UTF_8)).toString()
        RustBridge.sendMessage(id, chat.id, peer, ack)
    }

    /** Unauthenticated or malformed control packets are consumed but never applied. */
    suspend fun routeIncoming(peer: String, text: String, authenticated: Boolean): Boolean =
        if (!ChatClearWire.isControl(text)) false else withContext(Dispatchers.IO) {
            protocol.receive(peer, authenticated, text)
        }
}
