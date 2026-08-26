package com.vladimir.messenger.data.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileTransferChatRoutingTest {
    @Test
    fun directScopeUsesRecipientsLocalChat() {
        assertEquals(
            "local-chat-42",
            FileTransferChatRouting.resolve(
                FileTransferChatRouting.DIRECT_TRANSPORT_SCOPE,
                "local-chat-42",
            ),
        )
    }

    @Test
    fun directScopeWithoutLocalChatIsRejected() {
        assertNull(
            FileTransferChatRouting.resolve(
                FileTransferChatRouting.DIRECT_TRANSPORT_SCOPE,
                null,
            ),
        )
        assertNull(
            FileTransferChatRouting.resolve(
                FileTransferChatRouting.DIRECT_TRANSPORT_SCOPE,
                "",
            ),
        )
    }

    @Test
    fun localChatOverridesRemoteDeviceChatUuid() {
        assertEquals(
            "recipient-local-chat",
            FileTransferChatRouting.resolve("sender-local-chat", "recipient-local-chat"),
        )
    }

    @Test
    fun legacyScopeRemainsAvailableWhenNoLocalChatExists() {
        assertEquals(
            "legacy-chat",
            FileTransferChatRouting.resolve("legacy-chat", null),
        )
    }
}
