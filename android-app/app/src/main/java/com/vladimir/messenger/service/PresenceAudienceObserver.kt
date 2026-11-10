package com.vladimir.messenger.service

import com.vladimir.messenger.domain.model.Contact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Refresh discovery only when routing identities change, not on every online dot update. */
internal object PresenceAudienceObserver {
    fun start(
        scope: CoroutineScope,
        contacts: Flow<List<Contact>>,
        refresh: suspend () -> Unit,
        recordFailure: (Exception) -> Unit,
        retryMs: Long = RETRY_MS,
    ): Job = scope.launch {
        contacts.map { rows -> rows.map { it.id to it.fingerprint }.sortedWith(compareBy({ it.first }, { it.second })) }
            .distinctUntilChanged()
            .collectLatest {
                while (true) {
                    try {
                        refresh()
                        break
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        recordFailure(error)
                        delay(retryMs)
                    }
                }
            }
    }

    const val RETRY_MS = 5_000L
}
