package com.vladimir.messenger.ui.screens.chat

import com.vladimir.messenger.domain.model.Message
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatHistoryObserverTest {
    private fun row(id: String, mine: Boolean = false) = Message(
        id = id,
        chatId = "local-chat",
        senderId = if (mine) "self" else "peer",
        content = id,
        timestamp = 1,
        isFromMe = mine,
    )

    @Test
    fun localHistoryAndNewRowsAppearWhileReceiptIsBlocked() = runTest {
        val source = MutableStateFlow(listOf(row("first")))
        val gate = CompletableDeferred<Unit>()
        val rendered = mutableListOf<List<Message>>()
        val sends = mutableListOf<String>()
        val observer = ChatHistoryObserver(
            backgroundScope, { source }, { rendered += it },
            reportRead = { id ->
                assertEquals(id, rendered.last().last().id)
                sends += id
                gate.await()
            },
            onLoadError = { throw it }, onReadError = { throw it },
        )
        observer.start()
        runCurrent()
        assertEquals("first", rendered.single().single().id)
        assertEquals(listOf("first"), sends)
        assertFalse(gate.isCompleted)

        source.value = listOf(row("first"), row("second"))
        runCurrent()
        assertEquals("second", rendered.last().last().id)
        assertEquals(listOf("first"), sends)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("first", "second"), sends)
    }

    @Test
    fun emptyChatIsPublishedWithoutAnyNetworkCall() = runTest {
        var rendered: List<Message>? = null
        var sends = 0
        ChatHistoryObserver(
            backgroundScope, { flowOf(emptyList()) }, { rendered = it },
            reportRead = { sends++ }, onLoadError = { throw it }, onReadError = { throw it },
        ).start()
        runCurrent()
        assertEquals(emptyList<Message>(), rendered)
        assertEquals(0, sends)
    }

    @Test
    fun outgoingOnlyHistoryDoesNotSendAReadReceipt() = runTest {
        var sends = 0
        var rendered = emptyList<Message>()
        ChatHistoryObserver(
            backgroundScope, { flowOf(listOf(row("outgoing", mine = true))) }, { rendered = it },
            reportRead = { sends++ }, onLoadError = { throw it }, onReadError = { throw it },
        ).start()
        runCurrent()
        assertEquals("outgoing", rendered.single().id)
        assertEquals(0, sends)
    }

    @Test
    fun receiptFailureDoesNotKillTheLocalCollectorOrLaterReceipts() = runTest {
        val source = MutableStateFlow(listOf(row("first")))
        val rendered = mutableListOf<List<Message>>()
        val sends = mutableListOf<String>()
        val errors = mutableListOf<Exception>()
        ChatHistoryObserver(
            backgroundScope, { source }, { rendered += it },
            reportRead = { sends += it; throw IllegalStateException("offline") },
            onLoadError = { throw it }, onReadError = { errors += it },
        ).start()
        runCurrent()
        source.value = listOf(row("first"), row("second"))
        runCurrent()
        assertEquals("second", rendered.last().last().id)
        assertEquals(listOf("first", "second"), sends)
        assertEquals(2, errors.size)
    }

    @Test
    fun queuedReceiptsAreBoundedAndConflatedToTheNewestRow() = runTest {
        val source = MutableStateFlow(listOf(row("first")))
        val gate = CompletableDeferred<Unit>()
        val sends = mutableListOf<String>()
        var rendered = emptyList<Message>()
        ChatHistoryObserver(
            backgroundScope, { source }, { rendered = it },
            reportRead = { sends += it; gate.await() },
            onLoadError = { throw it }, onReadError = { throw it },
        ).start()
        runCurrent()
        repeat(100) { index ->
            source.value = listOf(row("next-$index"))
            runCurrent()
        }
        assertEquals("next-99", rendered.single().id)
        assertEquals(listOf("first"), sends)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("first", "next-99"), sends)
    }

    @Test
    fun statusAndPinChangesDoNotDuplicateAReceipt() = runTest {
        val incoming = row("first")
        val source = MutableStateFlow(listOf(incoming))
        val sends = mutableListOf<String>()
        ChatHistoryObserver(
            backgroundScope, { source }, {}, reportRead = { sends += it },
            onLoadError = { throw it }, onReadError = { throw it },
        ).start()
        runCurrent()
        source.value = listOf(incoming.copy(isPinned = true), row("my-reply", mine = true))
        runCurrent()
        assertEquals(listOf("first"), sends)
    }

    @Test
    fun localReadFailureIsReportedAndRetryRestoresHistory() = runTest {
        var reads = 0
        val errors = mutableListOf<Exception>()
        var rendered = emptyList<Message>()
        val source: () -> Flow<List<Message>> = {
            flow {
                reads++
                if (reads == 1) throw IllegalStateException("database read failed")
                emit(listOf(row("restored")))
            }
        }
        val observer = ChatHistoryObserver(
            backgroundScope, source, { rendered = it }, {},
            onLoadError = { errors += it }, onReadError = { throw it },
        )
        observer.start()
        runCurrent()
        assertEquals(1, errors.size)
        assertTrue(rendered.isEmpty())
        observer.start()
        runCurrent()
        assertEquals("restored", rendered.single().id)
        assertEquals(2, reads)
    }

    @Test
    fun retryDoesNotStartAnotherReceiptWorker() = runTest {
        val gate = CompletableDeferred<Unit>()
        var sends = 0
        var renders = 0
        val observer = ChatHistoryObserver(
            backgroundScope, { flowOf(listOf(row("first"))) }, { renders++ },
            reportRead = { sends++; gate.await() }, onLoadError = { throw it }, onReadError = { throw it },
        )
        observer.start()
        runCurrent()
        repeat(10) { observer.start(); runCurrent() }
        assertEquals(11, renders)
        assertEquals(1, sends)
        gate.complete(Unit)
    }

    @Test
    fun closingScreenCancelsReceiptWithoutReportingItAsAReadFailure() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        var cancelled = false
        var errors = 0
        ChatHistoryObserver(
            scope, { flowOf(listOf(row("first"))) }, {},
            reportRead = { try { gate.await() } finally { cancelled = true } },
            onLoadError = { errors++ }, onReadError = { errors++ },
        ).start()
        runCurrent()
        scope.cancel()
        runCurrent()
        assertTrue(cancelled)
        assertEquals(0, errors)
    }
}
