package com.vladimir.messenger.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Счётчик аренд CPU-замка: первая берёт, последняя снимает, лишние не ломают. */
class WakeLeaseRegistryTest {

    @Test
    fun `first acquire and last release are the only transitions`() {
        val r = WakeLeaseRegistry()
        assertFalse(r.held)
        assertTrue(r.acquire())
        assertTrue(r.held)
        assertFalse(r.acquire())
        assertFalse(r.release())
        assertTrue(r.held)
        assertTrue(r.release())
        assertFalse(r.held)
    }

    @Test
    fun `release without acquire is a no-op`() {
        val r = WakeLeaseRegistry()
        assertFalse(r.release())
        assertFalse(r.held)
    }

    @Test
    fun `reset drops all leases for service shutdown`() {
        val r = WakeLeaseRegistry()
        r.acquire()
        r.acquire()
        r.reset()
        assertFalse(r.held)
        assertTrue(r.acquire())
    }
}
