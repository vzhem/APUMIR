package com.vladimir.messenger.data.update

/** Limit expensive APK hashing/copy/encryption after a failed automatic preparation. */
object SelfSeedRetryPolicy {
    const val RETRY_AFTER_FAILURE_MS = 60L * 60_000L

    fun mayAttempt(version: String, lastVersion: String?, lastAttemptMs: Long, nowMs: Long): Boolean =
        lastVersion != version || lastAttemptMs <= 0L || nowMs < lastAttemptMs ||
            nowMs - lastAttemptMs >= RETRY_AFTER_FAILURE_MS
}
