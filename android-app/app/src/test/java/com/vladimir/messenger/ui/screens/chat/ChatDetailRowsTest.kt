package com.vladimir.messenger.ui.screens.chat

import com.vladimir.messenger.data.local.entity.FileTransferEntity
import com.vladimir.messenger.domain.model.Message
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatDetailRowsTest {
    private fun message(id: String, time: Long) = Message(id, "chat", "peer", "hi", time)
    private fun transfer(id: String, messageId: String, time: Long, hash: String = id,
                         state: String = "OFFERED", direction: String = "INCOMING",
                         media: String = "application/octet-stream", name: String = "file") =
        FileTransferEntity(id, messageId, "chat", "peer", direction, name, media,
            1, 1, 1, hash, state, createdAtMs = time, expiresAtMs = time + 1000,
            updatedAtMs = time)

    @Test fun `messages and active transfers are merged in chronological order`() {
        val m = message("one", 20)
        val attached = transfer("associated", "one", 10, state = "COMPLETE")
        val pending = transfer("pending", "other", 15)
        val result = buildChatRows(listOf(m, message("two", 30)), listOf(attached, pending))
        assertEquals(listOf("t-pending", "one", "two"), result.map { it.key })
        assertEquals(attached, result[1].transfer)
    }

    @Test fun `duplicate incoming transfer and silent media stay hidden`() {
        val result = buildChatRows(listOf(message("one", 1)), listOf(
            transfer("first", "a", 10, hash = "same"),
            transfer("second", "b", 20, hash = "same"),
            transfer("gif", "c", 30, media = "IMAGE/GIF"),
            transfer("sticker", "d", 40, name = "Стикер_1"),
            transfer("complete", "e", 50, state = "COMPLETE"),
            transfer("out", "f", 60, direction = "OUTGOING"),
        ))
        assertEquals(listOf("one", "t-first"), result.map { it.key })
    }
}
