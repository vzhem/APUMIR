package com.vladimir.messenger.service

/**
 * Serializes lease accounting with the platform wake-lock transitions. Closing
 * the service is terminal: an in-flight coroutine cannot reacquire afterward.
 * All platform callbacks are invoked under the same monitor as the counters.
 */
internal class WorkWakeLeases(
    private val platformIsHeld: () -> Boolean,
    private val platformAcquire: () -> Unit,
    private val platformRelease: () -> Unit,
    private val recordFailure: (String, Exception) -> Unit,
) {
    private val monitor = Any()
    private var holders = 0
    private var stopped = false

    fun acquire(): AutoCloseable = synchronized(monitor) {
        if (stopped) return@synchronized InactiveLease
        try {
            // A bounded system lock may already have timed out while an older
            // lease is still active. A new unit of work must reacquire it.
            if (holders == 0 || !platformIsHeld()) platformAcquire()
        } catch (error: Exception) {
            recordFailure("acquire", error)
            // The platform callback may have failed after acquiring the lock.
            // Clean up only when no other lease depends on that lock.
            if (holders == 0) releasePlatform()
            return@synchronized InactiveLease
        }
        holders++
        Lease()
    }

    fun releaseAll() = synchronized(monitor) {
        if (stopped) return@synchronized
        stopped = true
        holders = 0
        releasePlatform()
    }

    private fun releasePlatform() {
        try {
            platformRelease()
        } catch (error: Exception) {
            recordFailure("release", error)
        }
    }

    private inner class Lease : AutoCloseable {
        private var closed = false

        override fun close() = synchronized(monitor) {
            if (closed) return@synchronized
            closed = true
            // releaseAll already accounted for every lease. In particular, a
            // late close must never affect work started by a replacement service.
            if (stopped) return@synchronized
            check(holders > 0) { "wake lease accounting underflow" }
            holders--
            if (holders == 0) releasePlatform()
        }
    }

    private object InactiveLease : AutoCloseable { override fun close() = Unit }
}
