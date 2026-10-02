package com.vladimir.messenger.data.group

import java.time.Instant
import java.time.ZoneOffset

/** Расчёты статистики без Android/Room: тот же UTC, что у group_message_stats. */
object GroupStatsCalculator {
    fun dayKey(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate().toString()

    /** Ровно N календарных дней до сегодня включительно, от старых к новым. */
    fun dayKeys(nowMs: Long, days: Int = 7): List<String> {
        require(days in 1..366)
        val today = Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).toLocalDate()
        return (days - 1 downTo 0).map { today.minusDays(it.toLong()).toString() }
    }

    /** Отсутствие строки означает нулевую активность, а не отсутствие дня. */
    fun completeDays(keys: List<String>, recorded: List<GroupStatDay>): List<GroupStatDay> {
        val byDay = recorded.associateBy { it.dayKey }
        return keys.map { key -> byDay[key] ?: GroupStatDay(key, 0, 0) }
    }

    /** Один и тот же автор, писавший в разные дни, считается только один раз. */
    fun uniqueSenders(sendersCsv: List<String>): Int = sendersCsv.asSequence()
        .flatMap { it.split(',').asSequence() }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .count()

    /** Даты самих постов, а не даты доставки на этот телефон или комментариев. */
    fun publicationDays(keys: List<String>, posts: List<ChannelPostStat>): List<GroupStatDay> {
        val recorded = posts.groupBy { dayKey(it.publishedAtMs) }.map { (key, dailyPosts) ->
            GroupStatDay(
                dayKey = key,
                messageCount = dailyPosts.size,
                senderCount = dailyPosts.map { it.authorId }.filter { it.isNotBlank() }.distinct().size,
            )
        }
        return completeDays(keys, recorded)
    }
}
