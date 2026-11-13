package com.vladimir.messenger.data.notification

/**
 * Group catch-up can contain either genuinely missed messages or old channel
 * history. Match the repository's existing unread policy: never alert for
 * self/parts or historical backfill, but do alert for missed swarm messages.
 */
internal object NotificationArrivalPolicy {
    fun groupMessage(
        isMine: Boolean,
        isPart: Boolean,
        relayed: Boolean,
        missedSwarmMessage: Boolean,
        sentAtMs: Long,
        nowMs: Long,
        freshPostMs: Long,
    ): Boolean = !isMine && !isPart &&
        (!relayed || missedSwarmMessage || nowMs - sentAtMs < freshPostMs)
}
