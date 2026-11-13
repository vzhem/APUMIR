package com.vladimir.messenger.data.push

/** Operations needed by the bounded drain; production uses the durable SQLite inbox. */
internal interface PushWakeQueue {
    fun claim(nowMs: Long): PushWakeInbox.ClaimResult
    fun complete(claim: PushWakeInbox.Claim, success: Boolean, nowMs: Long): PushWakeInbox.Completion
    fun pending(nowMs: Long): PushWakeInbox.PendingResult
}
