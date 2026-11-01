package com.vladimir.messenger.ui.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Требование владельца 2026-11-01: кнопка «Скачать» (у соседей и с официального
 * сайта) сразу сообщает о старте и не даёт нажать второй раз. Первый замок —
 * атомарный захват одного тапа ещё до сетевого запроса.
 */
class UpdateDownloadTapGateTest {

    @Test
    fun `first tap is accepted and the next one is ignored until release`() {
        val gate = UpdateDownloadTapGate()
        assertTrue(gate.tryAcquire())
        assertTrue(gate.isLocked)
        assertFalse(gate.tryAcquire())
        assertFalse(gate.tryAcquire())
        gate.release()
        assertFalse(gate.isLocked)
        assertTrue(gate.tryAcquire())
    }

    @Test
    fun `ten rapid taps produce exactly one start`() {
        val gate = UpdateDownloadTapGate()
        var starts = 0
        repeat(10) {
            if (gate.tryAcquire()) starts++
        }
        assertEquals(1, starts)
    }

    @Test
    fun `simultaneous taps from different threads still start one download`() {
        val gate = UpdateDownloadTapGate()
        val threads = 16
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val starts = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) {
                pool.submit {
                    ready.countDown()
                    go.await()
                    if (gate.tryAcquire()) starts.incrementAndGet()
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            go.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
            assertEquals(1, starts.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `release is idempotent and does not unlock a newer tap by mistake`() {
        val gate = UpdateDownloadTapGate()
        gate.release()
        assertTrue(gate.tryAcquire())
        gate.release()
        gate.release()
        assertFalse(gate.isLocked)
        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())
    }
}
