package com.vladimir.messenger.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses fake handles; it does not simulate a Rust panic, Service or Android process death. */
class CoreEngineSlotTest {
    private fun start(slot: CoreEngineSlot<Any>, handle: Any): Boolean =
        slot.startNew({ handle }, { true }, {}, { throw AssertionError(it) })

    @Test fun provisionalHandleIsNotPublishedUntilItIsAccepted() {
        val stopped = mutableListOf<Any>()
        val slot = CoreEngineSlot<Any>(Any(), { stopped.add(it) }, { throw AssertionError(it) })
        val handle = Any()
        var competingCreateCalled = false
        assertTrue(slot.startNew(
            create = { handle },
            prepareAndStart = {
                assertTrue(it === handle)
                assertNull(slot.active())
                assertTrue(slot.unsettled())
                assertFalse(slot.startNew({ competingCreateCalled = true; Any() }, { true }, {}, {}))
                true
            },
            beforePublish = { assertNull(slot.active()); assertTrue(slot.unsettled()) },
            recordFailure = { throw AssertionError(it) },
        ))
        assertFalse(competingCreateCalled)
        assertTrue(slot.active() === handle)
        assertTrue(slot.stop())
        assertNull(slot.active())
        assertFalse(slot.unsettled())
        assertEquals(listOf(handle), stopped)
        assertTrue(slot.stop())
        assertEquals(listOf(handle), stopped)
    }

    @Test fun failedStopDetachesButRetainsHandleAndBlocksAnotherEngineUntilRetry() {
        val failures = mutableListOf<Exception>()
        val calls = mutableListOf<Any>()
        var fail = true
        val slot = CoreEngineSlot<Any>(Any(), { handle ->
            calls.add(handle)
            if (fail) throw IllegalStateException("simulated native failure")
        }, { failures.add(it) })
        val first = Any()
        val next = Any()
        assertTrue(start(slot, first))
        assertFalse(slot.stop())
        assertNull(slot.active())
        assertTrue(slot.unsettled())
        assertEquals(1, failures.size)
        var created = false
        assertFalse(slot.startNew({ created = true; next }, { true }, {}, {}))
        assertFalse(created)
        fail = false
        assertTrue(slot.stop())
        assertFalse(slot.unsettled())
        assertEquals(listOf(first, first), calls)
        assertTrue(start(slot, next))
        assertTrue(slot.active() === next)
    }

    @Test fun failedProvisionalStartupIsRetainedWhenCleanupAlsoFails() {
        val failures = mutableListOf<Exception>()
        var fail = true
        val slot = CoreEngineSlot<Any>(Any(), {
            if (fail) throw IllegalStateException("partial native startup")
        }, { failures.add(it) })
        val candidate = Any()
        assertFalse(slot.startNew({ candidate }, { false }, { throw AssertionError("must not publish") }, {}))
        assertNull(slot.active())
        assertTrue(slot.unsettled())
        assertEquals(1, failures.size)
        assertFalse(start(slot, Any()))
        fail = false
        assertTrue(slot.stop())
        assertFalse(slot.unsettled())
    }

    @Test fun exceptionDuringStartAttemptsCleanupAndLeavesFailedHandleQuarantined() {
        val cleanupFailures = mutableListOf<Exception>()
        val startupFailures = mutableListOf<Exception>()
        val stopped = mutableListOf<Any>()
        var failStop = true
        val slot = CoreEngineSlot<Any>(Any(), {
            stopped.add(it)
            if (failStop) throw IllegalStateException("stop failed")
        }, { cleanupFailures.add(it) })
        val candidate = Any()
        assertFalse(slot.startNew({ candidate }, { throw IllegalStateException("start failed") }, {}, { startupFailures.add(it) }))
        assertEquals(1, startupFailures.size)
        assertEquals(1, cleanupFailures.size)
        assertEquals(listOf(candidate), stopped)
        assertNull(slot.active())
        assertTrue(slot.unsettled())
        failStop = false
        assertTrue(slot.stop())
        assertEquals(listOf(candidate, candidate), stopped)
    }

    @Test fun failedPrePublicationCallbackAlsoStopsTheSameHandle() {
        val stopped = mutableListOf<Any>()
        val startupFailures = mutableListOf<Exception>()
        val slot = CoreEngineSlot<Any>(Any(), { stopped.add(it) }, { throw AssertionError(it) })
        val candidate = Any()
        assertFalse(slot.startNew({ candidate }, { true }, { throw IllegalStateException("publication failed") }, { startupFailures.add(it) }))
        assertEquals(1, startupFailures.size)
        assertEquals(listOf(candidate), stopped)
        assertNull(slot.active())
        assertFalse(slot.unsettled())
    }

    @Test fun failingReporterCannotSkipCleanupAndCreateFailureHasNoHandleToStop() {
        val stopped = mutableListOf<Any>()
        val slot = CoreEngineSlot<Any>(Any(), { stopped.add(it) }, { throw AssertionError(it) })
        val candidate = Any()
        assertTrue(runCatching {
            slot.startNew({ candidate }, { throw IllegalStateException("start") }, {}, { throw IllegalStateException("report") })
        }.isFailure)
        assertEquals(listOf(candidate), stopped)
        assertFalse(slot.unsettled())
        assertFalse(slot.startNew({ throw IllegalStateException("create") }, { true }, {}, {}))
        assertEquals(listOf(candidate), stopped)
    }

    @Test fun reservationPrecedesCreateAndDoesNotHoldIdentityLockDuringStartup() {
        val publicationLock = Any()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val threadError = AtomicReference<Throwable?>()
        val handle = Any()
        val slot = CoreEngineSlot<Any>(publicationLock, { }, { throw AssertionError(it) })
        val thread = Thread {
            try {
                check(slot.startNew(
                    allow = { check(Thread.holdsLock(publicationLock)); true },
                    create = {
                        check(!Thread.holdsLock(publicationLock))
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        handle
                    },
                    prepareAndStart = { check(!Thread.holdsLock(publicationLock)); true },
                    beforePublish = { check(!Thread.holdsLock(publicationLock)); check(slot.unsettled()) },
                    recordFailure = { throw AssertionError(it) },
                ))
            } catch (error: Throwable) { threadError.set(error) }
        }
        try {
            thread.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            synchronized(publicationLock) {
                assertNull(slot.active())
                assertTrue(slot.unsettled()) // production sidecar installation must refuse here
                var created = false
                assertFalse(slot.startNew({ created = true; Any() }, { true }, {}, {}))
                assertFalse(created)
                assertFalse(slot.stop()) // never stop a candidate still being created
            }
        } finally {
            release.countDown()
            thread.join(5_000)
        }
        assertFalse(thread.isAlive)
        assertNull(threadError.get())
        assertTrue(slot.active() === handle)
        assertFalse(slot.unsettled())
        assertTrue(slot.stop())
    }

    @Test fun identityGuardRejectsBeforeCreatingAndDoesNotLeakReservation() {
        val publicationLock = Any()
        val slot = CoreEngineSlot<Any>(publicationLock, {}, { throw AssertionError(it) })
        var created = false
        assertFalse(slot.startNew(
            create = { created = true; Any() }, prepareAndStart = { true }, beforePublish = {},
            recordFailure = { throw AssertionError(it) },
            allow = { assertTrue(Thread.holdsLock(publicationLock)); false },
        ))
        assertFalse(created)
        assertNull(slot.active())
        assertFalse(slot.unsettled())
        assertTrue(start(slot, Any()))
        assertTrue(slot.stop())
    }

    @Test fun errorDuringStartupStillAttemptsCleanupAndPropagates() {
        val failures = mutableListOf<Exception>()
        var stopFails = true
        var stopCount = 0
        val slot = CoreEngineSlot<Any>(Any(), {
            stopCount++
            if (stopFails) throw IllegalStateException("stop")
        }, { failures.add(it) })
        val error = runCatching {
            slot.startNew({ Any() }, { throw AssertionError("fatal startup") }, {}, {})
        }.exceptionOrNull()
        assertTrue(error is AssertionError)
        assertEquals(1, stopCount)
        assertEquals(1, failures.size)
        assertNull(slot.active())
        assertTrue(slot.unsettled())
        assertFalse(start(slot, Any()))
        stopFails = false
        assertTrue(slot.stop())
        assertEquals(2, stopCount)
        assertFalse(slot.unsettled())
    }

    @Test fun lateStatusForAnOldHandleCannotOverwriteTheReplacement() {
        val slot = CoreEngineSlot<Any>(Any(), {}, { throw AssertionError(it) })
        val old = Any()
        val replacement = Any()
        var running = true
        assertTrue(slot.startNew({ old }, { true }, {}, { throw AssertionError(it) }))
        assertTrue(slot.stop())
        assertTrue(slot.startNew({ replacement }, { true }, {}, { throw AssertionError(it) }))
        assertFalse(slot.ifActive(old) { running = false })
        assertTrue(running)
        assertTrue(slot.ifActive(replacement) { running = false })
        assertFalse(running)
    }

    @Test fun interruptedStartupPreservesTheInterruptAndStillAttemptsCleanup() {
        val threadError = AtomicReference<Throwable?>()
        val thread = Thread {
            try {
                var cleanupSawInterrupt = false
                val slot = CoreEngineSlot<Any>(Any(), {
                    cleanupSawInterrupt = Thread.currentThread().isInterrupted
                }, { throw AssertionError(it) })
                var reported = false
                assertFalse(slot.startNew({ Any() }, { throw InterruptedException("cancelled") }, {}, { reported = true }))
                assertTrue(reported)
                assertTrue(cleanupSawInterrupt)
                assertTrue(Thread.currentThread().isInterrupted)
                assertFalse(slot.unsettled())
            } catch (error: Throwable) { threadError.set(error) }
            finally { Thread.interrupted() }
        }
        thread.start()
        thread.join(5_000)
        assertFalse(thread.isAlive)
        assertNull(threadError.get())
    }

    @Test fun errorDuringStopKeepsTheHandleAndAllowsALaterCleanupAttempt() {
        var stopFails = true
        val candidate = Any()
        val slot = CoreEngineSlot<Any>(Any(), {
            if (stopFails) throw AssertionError("fatal stop")
        }, { throw AssertionError(it) })
        assertTrue(start(slot, candidate))
        assertTrue(runCatching { slot.stop() }.exceptionOrNull() is AssertionError)
        assertNull(slot.active())
        assertTrue(slot.unsettled())
        stopFails = false
        assertTrue(slot.stop())
        assertFalse(slot.unsettled())
    }

    @Test fun blockingStopLeavesIdentityLockAvailableAndHandleUnavailable() {
        val publicationLock = Any()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val threadError = AtomicReference<Throwable?>()
        val handle = Any()
        val slot = CoreEngineSlot<Any>(publicationLock, {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }, { throw AssertionError(it) })
        assertTrue(start(slot, handle))
        val thread = Thread {
            try {
                check(slot.stop())
            } catch (error: Throwable) {
                threadError.set(error)
            }
        }
        try {
            thread.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            // Production signing checks hold this same lock. It must be free,
            // and the pending handle must make them refuse another identity.
            synchronized(publicationLock) {
                assertNull(slot.active())
                assertTrue(slot.unsettled())
                assertFalse(start(slot, Any()))
                assertFalse(slot.stop()) // no second concurrent stop on the same handle
            }
        } finally {
            release.countDown()
            thread.join(5_000)
        }
        assertFalse(thread.isAlive)
        assertNull(threadError.get())
        assertFalse(slot.unsettled())
    }
}
