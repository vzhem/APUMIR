package com.vladimir.messenger.data

/**
 * One native engine, including a handle whose cleanup failed. The caller must
 * serialize lifecycle mutations; reads may be made from any thread. Reserving
 * startup and publishing/detaching a handle share the identity publication
 * lock, but native create/start/stop must not run while holding that lock.
 *
 * While startup or cleanup is unresolved, ordinary bridge calls see no handle,
 * sidecar installation is refused and another engine cannot be created. An
 * already-running FFI call cannot be forcibly interrupted here.
 */
internal class CoreEngineSlot<T : Any>(
    private val publicationLock: Any,
    private val stop: (T) -> Unit,
    private val recordFailure: (Exception) -> Unit,
) {
    @Volatile private var live: T? = null
    @Volatile private var pending: T? = null
    @Volatile private var starting = false
    private var stopping = false // protected by publicationLock

    fun active(): T? = live
    fun unsettled(): Boolean = starting || pending != null

    /** Publish a short cache update only if this exact handle is still live. */
    fun ifActive(handle: T, update: () -> Unit): Boolean = synchronized(publicationLock) {
        if (live !== handle) false else { update(); true }
    }

    /**
     * [allow] is checked under the identity publication lock immediately before
     * reservation. After that the lock is released, but the reservation continues
     * to reject identity changes until publication or successful cleanup.
     * [beforePublish] must finish before ordinary bridge calls see the handle.
     *
     * On any unsuccessful exit, including an Error, cleanup is attempted in
     * finally. An Exception is reported and returns false; an Error still
     * propagates. A failed stop retains the handle and refuses another engine.
     */
    fun startNew(
        create: () -> T,
        prepareAndStart: (T) -> Boolean,
        beforePublish: (T) -> Unit,
        recordFailure: (Exception) -> Unit,
        allow: () -> Boolean = { true },
    ): Boolean {
        val reserved = synchronized(publicationLock) {
            if (live != null || pending != null || starting || stopping || !allow()) false
            else { starting = true; true }
        }
        if (!reserved) return false
        var claimed = false
        var published = false
        try {
            val handle = create()
            synchronized(publicationLock) {
                // The reservation rejects competing starts and stops. Keep the
                // reference before any policy, native start or callback can fail.
                pending = handle
                claimed = true
            }
            if (!prepareAndStart(handle)) return false
            beforePublish(handle)
            synchronized(publicationLock) {
                check(starting && pending === handle && live == null) { "Native startup ownership changed" }
                live = handle
                pending = null
                starting = false
                published = true
            }
            return true
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            recordFailure(error)
            return false
        } finally {
            if (!published) {
                synchronized(publicationLock) { starting = false }
                if (claimed) stop()
            }
        }
    }

    /**
     * Detach before stopping, and retain the reference unless stop returns
     * normally. The lock is free during native stop; a second concurrent stop
     * cannot act on the same handle. The production caller uses one lifecycle
     * queue, so a later explicit stop/start can retry a failed cleanup.
     */
    fun stop(): Boolean {
        val handle = synchronized(publicationLock) {
            if (starting || stopping) return false
            check(live == null || pending == null) { "Conflicting native engine handles" }
            val candidate = live ?: pending ?: return true
            pending = candidate
            live = null
            stopping = true
            candidate
        }
        var completed = false
        try {
            stop(handle)
            completed = true
            return true
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            recordFailure(error)
            return false
        } finally {
            synchronized(publicationLock) {
                if (completed) {
                    check(pending === handle) { "Native cleanup ownership changed" }
                    pending = null
                }
                stopping = false
            }
        }
    }
}
