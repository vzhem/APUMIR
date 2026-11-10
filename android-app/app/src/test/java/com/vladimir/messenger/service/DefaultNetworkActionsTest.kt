package com.vladimir.messenger.service

import com.vladimir.messenger.service.DefaultNetworkState.Action
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class DefaultNetworkActionsTest {
    @Test
    fun `pending loss cannot overtake the most recent recovery`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val seen = CopyOnWriteArrayList<Action>()
        val worker = DefaultNetworkActions(scope, { action ->
            seen.add(action)
            if (seen.size == 1) {
                entered.complete(Unit)
                release.await()
            } else finished.complete(Unit)
        }, { throw it })
        try {
            worker.submit(Action.AVAILABLE)
            withTimeout(3_000) { entered.await() }
            worker.submit(Action.LOST)
            worker.submit(Action.AVAILABLE)
            release.complete(Unit)
            withTimeout(3_000) { finished.await() }
            assertEquals(listOf(Action.AVAILABLE, Action.AVAILABLE), seen.toList())
        } finally {
            worker.stop()
            scope.cancel()
        }
    }

    @Test
    fun `a final loss is applied after the busy transition`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val seen = CopyOnWriteArrayList<Action>()
        val worker = DefaultNetworkActions(scope, { action ->
            seen.add(action)
            if (seen.size == 1) {
                entered.complete(Unit)
                release.await()
            } else finished.complete(Unit)
        }, { throw it })
        try {
            worker.submit(Action.AVAILABLE)
            withTimeout(3_000) { entered.await() }
            worker.submit(Action.LOST)
            release.complete(Unit)
            withTimeout(3_000) { finished.await() }
            assertEquals(listOf(Action.AVAILABLE, Action.LOST), seen.toList())
        } finally {
            worker.stop()
            scope.cancel()
        }
    }

    @Test
    fun `stopping cancels the current worker and discards pending changes`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        val seen = CopyOnWriteArrayList<Action>()
        val worker = DefaultNetworkActions(scope, { action ->
            seen.add(action)
            try {
                entered.complete(Unit)
                release.await()
            } finally {
                exited.complete(Unit)
            }
        }, { throw it })
        try {
            worker.submit(Action.AVAILABLE)
            withTimeout(3_000) { entered.await() }
            worker.submit(Action.LOST)
            worker.stop()
            withTimeout(3_000) { exited.await() }
            release.complete(Unit)
            worker.submit(Action.AVAILABLE)
            assertEquals(listOf(Action.AVAILABLE), seen.toList())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `an ordinary failure is reported without stopping later transitions`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val failed = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val errors = CopyOnWriteArrayList<String?>()
        val worker = DefaultNetworkActions(scope, { action ->
            if (action == Action.AVAILABLE) error("test failure")
            finished.complete(Unit)
        }, { error ->
            errors.add(error.message)
            failed.complete(Unit)
        })
        try {
            worker.submit(Action.AVAILABLE)
            withTimeout(3_000) { failed.await() }
            worker.submit(Action.LOST)
            withTimeout(3_000) { finished.await() }
            assertEquals(listOf("test failure"), errors.toList())
        } finally {
            worker.stop()
            scope.cancel()
        }
    }
}
