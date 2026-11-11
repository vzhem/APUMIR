package com.vladimir.messenger.data.file

import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerPinSerializationTest {
    @Test fun removalCannotOvertakeAnActionUsingThePreviouslyReadPin() = runBlocking {
        withTimeout(5_000) {
            val events = Collections.synchronizedList(mutableListOf<String>())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val action = async(Dispatchers.Default) {
                PeerPinSerialization.serialized {
                    events.add("read")
                    entered.complete(Unit)
                    release.await()
                    events.add("effect")
                }
            }
            try {
                entered.await()
                val started = CompletableDeferred<Unit>()
                val removal = async(Dispatchers.Default) {
                    started.complete(Unit)
                    PeerPinSerialization.serialized { events.add("remove") }
                }
                started.await()
                assertNull(withTimeoutOrNull(100) { removal.await() })
                release.complete(Unit)
                action.await()
                removal.await()
                assertEquals(listOf("read", "effect", "remove"), events.toList())
            } finally { release.complete(Unit) }
        }
    }

    @Test fun cancellationOfHolderOrWaiterAlwaysReleasesTheGate() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val holder = launch(Dispatchers.Default) {
                PeerPinSerialization.serialized {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            entered.await()
            val waiterStarted = CompletableDeferred<Unit>()
            val waiter = launch(Dispatchers.Default) {
                waiterStarted.complete(Unit)
                PeerPinSerialization.serialized { error("cancelled waiter must not run") }
            }
            waiterStarted.await()
            waiter.cancelAndJoin()
            holder.cancelAndJoin()
            assertTrue(PeerPinSerialization.serialized { true })
        }
    }

    @Test fun exceptionsAlsoReleaseTheGate() = runBlocking {
        withTimeout(5_000) {
            val failed = runCatching { PeerPinSerialization.serialized { error("test") } }.exceptionOrNull()
            assertTrue(failed is IllegalStateException)
            assertTrue(PeerPinSerialization.serialized { true })
        }
    }
}
