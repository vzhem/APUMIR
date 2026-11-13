package com.vladimir.messenger.data.update

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The broadcast, update monitor and settings can all request a scan at once. */
internal object CompletedApkAdoption {
    private val mutex = Mutex()

    suspend fun run(scan: suspend () -> Unit) = mutex.withLock { scan() }
}
