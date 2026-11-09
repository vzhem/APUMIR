package com.vladimir.messenger.ui.screens.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatPeerVipTest {
    @Test fun `already known VIP updates when peer id arrives later`() = runTest {
        val peer = MutableStateFlow("")
        val vip = MutableStateFlow(setOf("pk_friend1"))
        val received = mutableListOf<Pair<String, Set<String>>>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeChatPeerVip(peer, vip).collect { received.add(it) }
        }
        assertEquals("", received.last().first)
        peer.value = " PK_FRIEND1 "
        assertEquals("pk_friend1", received.last().first)
        assertEquals(true, received.last().first in received.last().second)
        vip.value = setOf("pk_other1")
        assertEquals(false, received.last().first in received.last().second)
        job.cancel()
    }
}
