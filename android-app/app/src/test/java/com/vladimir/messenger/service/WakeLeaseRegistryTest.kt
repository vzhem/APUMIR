package com.vladimir.messenger.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Tests the same coordinator used by the Android wake-lock wrapper, without Android. */
class WakeLeaseRegistryTest {
    private class FakeLock {
        val held = AtomicBoolean(false)
        val acquires = AtomicInteger()
        val releases = AtomicInteger()
        val failures = mutableListOf<String>()
        fun manager() = WorkWakeLeases(
            platformIsHeld = held::get,
            platformAcquire = { held.set(true); acquires.incrementAndGet() },
            platformRelease = { held.set(false); releases.incrementAndGet() },
            recordFailure = { operation, _ -> failures += operation },
        )
    }

    @Test
    fun firstAcquireAndLastReleaseAreTheOnlyTransitions() {
        val lock = FakeLock()
        val manager = lock.manager()
        val first = manager.acquire()
        val second = manager.acquire()
        assertTrue(lock.held.get())
        assertEquals(1, lock.acquires.get())
        first.close()
        first.close() // idempotent
        assertTrue(lock.held.get())
        assertEquals(0, lock.releases.get())
        second.close()
        assertFalse(lock.held.get())
        assertEquals(1, lock.releases.get())
        assertTrue(lock.failures.isEmpty())
    }

    @Test
    fun releaseAllIsTerminalAndLateLeasesCannotReacquireOrReleaseAgain() {
        val lock = FakeLock()
        val manager = lock.manager()
        val first = manager.acquire()
        val second = manager.acquire()
        manager.releaseAll()
        manager.releaseAll()
        val afterShutdown = manager.acquire()
        first.close()
        second.close()
        afterShutdown.close()
        assertFalse(lock.held.get())
        assertEquals(1, lock.acquires.get())
        assertEquals(1, lock.releases.get())
    }

    @Test
    fun newWorkReacquiresIfTheBoundedSystemLockTimedOut() {
        val lock = FakeLock()
        val manager = lock.manager()
        val first = manager.acquire()
        lock.held.set(false) // Android timed out the five-minute lock.
        val second = manager.acquire()
        assertEquals(2, lock.acquires.get())
        assertTrue(lock.held.get())
        first.close()
        assertTrue(lock.held.get())
        second.close()
        assertFalse(lock.held.get())
    }

    @Test
    fun failedFirstAcquireDoesNotMakeOtherWorkThinkItHasALock() {
        val lock = FakeLock()
        val attempts = AtomicInteger()
        val manager = WorkWakeLeases(
            platformIsHeld = lock.held::get,
            platformAcquire = {
                lock.held.set(true) // failure may happen after the platform acquire
                if (attempts.incrementAndGet() == 1) error("fake acquisition failure")
            },
            platformRelease = { lock.held.set(false) },
            recordFailure = { operation, _ -> lock.failures += operation },
        )
        val failed = manager.acquire()
        assertFalse(lock.held.get())
        val next = manager.acquire()
        assertTrue(lock.held.get())
        failed.close()
        assertTrue(lock.held.get())
        next.close()
        assertFalse(lock.held.get())
        assertEquals(listOf("acquire"), lock.failures)
    }

    @Test
    fun secondAcquireWaitsForFirstPlatformAcquireToFinish() {
        val lock = FakeLock()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val manager = WorkWakeLeases(
            platformIsHeld = lock.held::get,
            platformAcquire = {
                entered.countDown()
                check(proceed.await(5, TimeUnit.SECONDS))
                lock.held.set(true)
                lock.acquires.incrementAndGet()
            },
            platformRelease = { lock.held.set(false) },
            recordFailure = { operation, _ -> lock.failures += operation },
        )
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<AutoCloseable> { manager.acquire() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val second = pool.submit<AutoCloseable> { manager.acquire() }
            assertStillWaiting(second)
            proceed.countDown()
            val firstLease = first.get(2, TimeUnit.SECONDS)
            val secondLease = second.get(2, TimeUnit.SECONDS)
            assertEquals(1, lock.acquires.get())
            firstLease.close()
            assertTrue(lock.held.get())
            secondLease.close()
            assertFalse(lock.held.get())
        } finally {
            proceed.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun newAcquireCannotBeReleasedByThePreviousLastLease() {
        val lock = FakeLock()
        val releaseEntered = CountDownLatch(1)
        val releaseAllowed = CountDownLatch(1)
        val manager = WorkWakeLeases(
            platformIsHeld = lock.held::get,
            platformAcquire = { lock.held.set(true); lock.acquires.incrementAndGet() },
            platformRelease = {
                releaseEntered.countDown()
                check(releaseAllowed.await(5, TimeUnit.SECONDS))
                lock.held.set(false)
            },
            recordFailure = { operation, _ -> lock.failures += operation },
        )
        val old = manager.acquire()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val close = pool.submit { old.close() }
            assertTrue(releaseEntered.await(2, TimeUnit.SECONDS))
            val next = pool.submit<AutoCloseable> { manager.acquire() }
            assertStillWaiting(next)
            releaseAllowed.countDown()
            close.get(2, TimeUnit.SECONDS)
            val current = next.get(2, TimeUnit.SECONDS)
            assertTrue(lock.held.get())
            assertEquals(2, lock.acquires.get())
            current.close()
            assertFalse(lock.held.get())
        } finally {
            releaseAllowed.countDown()
            pool.shutdownNow()
        }
    }

    private fun assertStillWaiting(future: Future<*>) {
        try {
            future.get(100, TimeUnit.MILLISECONDS)
            fail("operation must wait for the in-progress platform transition")
        } catch (_: TimeoutException) {
            // Expected: the other transition owns the coordinator monitor.
        }
    }
}
