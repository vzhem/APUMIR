package com.vladimir.messenger.data

import com.vladimir.messenger.service.CoreServiceGate
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses the production queue with a fake engine; this is not an Android service or native test. */
class CoreLifecycleQueueTest {
    private fun queue() = CoreLifecycleQueue { message, error ->
        throw AssertionError(message, error)
    }
    private fun started(replaced: Boolean = true) = CoreLifecycleQueue.StartResult(true, replaced, true)
    private fun failed() = CoreLifecycleQueue.StartResult(false, false, true)

    @Test fun lateStopCannotStopEngineAdoptedByReplacement() {
        val queue = queue()
        val old = Any()
        val replacement = Any()
        val events = mutableListOf<String>()
        var running = false
        assertTrue(queue.start(old, null) { running = true; events.add("old-start"); started() })
        assertTrue(queue.start(replacement, null) { events.add("new-adopt"); check(running); started(replaced = false) })
        queue.stopIfOwned(old) { running = false; events.add("old-stop") }
        queue.call { assertTrue(running) }
        queue.stopIfOwned(replacement) { running = false; events.add("new-stop") }
        queue.call { assertFalse(running) }
        assertEquals(listOf("old-start", "new-adopt", "new-stop"), events)
    }

    @Test fun serviceStopDoesNotStopNonServiceEngine() {
        val queue = queue()
        val old = Any()
        var running = false
        assertTrue(queue.start(old, null) { running = true; started() })
        // For example, onboarding started a different engine after the service's.
        assertTrue(queue.start(null, null) { check(running); started(replaced = true) })
        queue.stopIfOwned(old) { running = false }
        queue.call { assertTrue(running) }
        // An explicit unconditional shutdown still releases ownership.
        queue.call { running = false; queue.clearOwner() }
        var stoppedAgain = false
        queue.stopIfOwned(old) { stoppedAgain = true }
        queue.call { assertFalse(stoppedAgain) }
    }

    @Test fun nonServiceBorrowerDoesNotTakeOwnershipAwayFromService() {
        val queue = queue()
        val service = Any()
        var running = false
        assertTrue(queue.start(service, null) { running = true; started() })
        assertTrue(queue.start(null, null) { check(running); started(replaced = false) })
        queue.stopIfOwned(service) { running = false }
        queue.call { assertFalse(running) }
    }

    @Test fun failedReplacementDoesNotStealOwnership() {
        val queue = queue()
        val old = Any()
        val replacement = Any()
        var stopped = false
        assertTrue(queue.start(old, null) { started() })
        assertFalse(queue.start(replacement, null) { failed() })
        queue.stopIfOwned(replacement) { stopped = true }
        queue.call { assertFalse(stopped) }
        queue.stopIfOwned(old) { stopped = true }
        queue.call { assertTrue(stopped) }
    }

    @Test fun failedStartThatPublishedANewHandleStillTransfersCleanupOwnership() {
        val queue = queue()
        val old = Any()
        val replacement = Any()
        var stopped = false
        assertTrue(queue.start(old, null) { started() })
        assertFalse(queue.start(replacement, null) { CoreLifecycleQueue.StartResult(false, true, true) })
        queue.stopIfOwned(old) { stopped = true }
        queue.call { assertFalse(stopped) }
        queue.stopIfOwned(replacement) { stopped = true }
        queue.call { assertTrue(stopped) }
    }

    @Test fun failedStartThatRemovedTheHandleClearsTheOldOwner() {
        val queue = queue()
        val old = Any()
        var stopped = false
        assertTrue(queue.start(old, null) { started() })
        assertFalse(queue.start(null, null) { CoreLifecycleQueue.StartResult(false, true, false) })
        queue.stopIfOwned(old) { stopped = true }
        queue.call { assertFalse(stopped) }
    }

    @Test fun retiredQueuedStartIsRejectedEvenAfterAStopHasAlreadyRun() {
        val queue = queue()
        val gate = CoreServiceGate()
        val old = Any()
        val replacement = Any()
        val events = mutableListOf<String>()
        gate.activate(old) { }
        assertTrue(queue.start(old, { gate.isCurrent(old) }) { events.add("old-start"); started() })
        assertTrue(gate.retire(old) { })
        queue.stopIfOwned(old) { events.add("old-stop") }
        gate.activate(replacement) { }
        assertFalse(queue.start(old, { gate.isCurrent(old) }) { events.add("stale-start"); started() })
        assertTrue(queue.start(replacement, { gate.isCurrent(replacement) }) { events.add("new-start"); started() })
        assertEquals(listOf("old-start", "old-stop", "new-start"), events)
    }

    @Test fun inFlightOldStartStopsBeforeQueuedReplacementStarts() {
        val queue = queue()
        val gate = CoreServiceGate()
        val old = Any()
        val replacement = Any()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val callers = Executors.newFixedThreadPool(2)
        try {
            gate.activate(old) { }
            val startOld = callers.submit<Boolean> {
                queue.start(old, { gate.isCurrent(old) }) {
                    events.add("old-start")
                    entered.countDown()
                    check(finish.await(5, TimeUnit.SECONDS))
                    events.add("old-finished")
                    started()
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(gate.retire(old) { })
            queue.stopIfOwned(old) { events.add("old-stop") }
            gate.activate(replacement) { }
            val startNew = callers.submit<Boolean> {
                queue.start(replacement, { gate.isCurrent(replacement) }) { events.add("new-start"); started() }
            }
            assertEquals(listOf("old-start"), events.toList())
            finish.countDown()
            assertTrue(startOld.get(5, TimeUnit.SECONDS))
            assertTrue(startNew.get(5, TimeUnit.SECONDS))
            assertEquals(listOf("old-start", "old-finished", "old-stop", "new-start"), events.toList())
        } finally {
            finish.countDown()
            callers.shutdownNow()
        }
    }

    @Test fun interruptedCallerReturnsBeforeCleanupButQueueStillWaitsForIt() {
        val queue = queue()
        val entered = CountDownLatch(1)
        val cleanupEntered = CountDownLatch(1)
        val allowCleanup = CountDownLatch(1)
        val callerReturned = CountDownLatch(1)
        val nextRan = CountDownLatch(1)
        val interruptPreserved = AtomicBoolean(false)
        val caller = Thread {
            try {
                queue.call {
                    try {
                        entered.countDown()
                        CountDownLatch(1).await()
                    } catch (_: InterruptedException) {
                        // A worker's optional wait was interrupted; cleanup is deliberately slow.
                    } finally {
                        cleanupEntered.countDown()
                        allowCleanup.await(5, TimeUnit.SECONDS)
                    }
                }
            } catch (_: InterruptedException) {
                interruptPreserved.set(Thread.currentThread().isInterrupted)
            } finally {
                callerReturned.countDown()
            }
        }
        try {
            caller.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            caller.interrupt()
            assertTrue(callerReturned.await(5, TimeUnit.SECONDS))
            assertTrue(interruptPreserved.get())
            assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS))
            queue.enqueue { nextRan.countDown() }
            assertFalse(nextRan.await(200, TimeUnit.MILLISECONDS))
            allowCleanup.countDown()
            assertTrue(nextRan.await(5, TimeUnit.SECONDS))
        } finally {
            allowCleanup.countDown()
            caller.interrupt()
            caller.join(5_000)
        }
        assertFalse(caller.isAlive)
    }
}
