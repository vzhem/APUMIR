package com.vladimir.messenger.data.file

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One process-wide gate for file-exchange pin reads used by a protected action,
 * first-seen writes and explicit pin removal. Never nest it or call another
 * FileExchangePeerStore method from its callback: Mutex is not reentrant.
 *
 * This gate alone does not serialize profile/key replacement, database restore,
 * other processes, cached bindings, or a device elsewhere. Keep each action
 * short and never hold the gate across network IO. Push must remain disabled
 * until its complete identity/pin/journal lifecycle has a reviewed ordering.
 */
internal object PeerPinSerialization {
    private val mutex = Mutex()

    suspend fun <T> serialized(block: suspend () -> T): T = mutex.withLock { block() }
}
