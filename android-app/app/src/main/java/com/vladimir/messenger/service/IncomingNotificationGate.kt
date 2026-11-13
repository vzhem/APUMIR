package com.vladimir.messenger.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes notification posts with direct, group and topic read actions in this process.
 * It is not a cross-process, profile-restore, or deletion barrier. Never wait
 * for it while holding a Room transaction: the protected block itself uses Room.
 */
internal object IncomingNotificationGate {
    private val mutex = Mutex()
    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}
