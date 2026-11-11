package com.vladimir.messenger.data

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The relay worker owns the lifecycle executor while its temporary engine is
 * active. A service start/stop can end the optional wait early, but cannot
 * interrupt a native start/stop or create another engine. An identity token
 * also covers a request made before the worker reaches its waiting window.
 */
internal class RelayWakeYield {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var generation = Any()

    fun snapshot(): Any = lock.withLock { generation }

    fun request() = lock.withLock {
        generation = Any()
        changed.signalAll()
    }

    fun requestedSince(token: Any): Boolean = lock.withLock { generation !== token }

    /** Returns true for an early yield; interruption propagates to the owner. */
    @Throws(InterruptedException::class)
    fun await(token: Any, windowMs: Long): Boolean = lock.withLock {
        require(windowMs >= 0)
        var remaining = TimeUnit.MILLISECONDS.toNanos(windowMs)
        while (generation === token && remaining > 0) {
            remaining = changed.awaitNanos(remaining)
        }
        generation !== token
    }
}
