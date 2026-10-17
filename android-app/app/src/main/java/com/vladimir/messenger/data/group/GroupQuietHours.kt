package com.vladimir.messenger.data.group

import java.time.Instant
import java.time.ZoneId

/** Days are ISO weekdays (Monday=1). Overnight intervals belong to the start day. */
data class GroupQuietHours(val days: Set<Int>, val startMinute: Int, val endMinute: Int, val zoneId: String) {
    init {
        require(days.isNotEmpty() && days.all { it in 1..7 }) { "Выберите дни недели" }
        require(startMinute in 0..1439 && endMinute in 0..1439 && startMinute != endMinute) {
            "Укажите разное время начала и окончания в формате ЧЧ:ММ"
        }
        ZoneId.of(zoneId)
    }

    fun blocks(atMs: Long): Boolean {
        val local = Instant.ofEpochMilli(atMs).atZone(ZoneId.of(zoneId))
        val minute = local.hour * 60 + local.minute
        val today = local.dayOfWeek.value
        return if (startMinute < endMinute) {
            today in days && minute >= startMinute && minute < endMinute
        } else {
            (today in days && minute >= startMinute) ||
                (local.minusDays(1).dayOfWeek.value in days && minute < endMinute)
        }
    }

    fun encode(): String = "${days.sorted().joinToString(",")};$startMinute;$endMinute;$zoneId"
    fun notice(): String = "По расписанию группы сейчас нельзя писать. Запрет ${time(startMinute)}–${time(endMinute)} ($zoneId). Администраторы могут писать."

    companion object {
        fun decode(raw: String): GroupQuietHours? = runCatching {
            val fields = raw.split(';')
            require(fields.size == 4 && raw.length <= 200)
            GroupQuietHours(fields[0].split(',').map { it.toInt() }.toSet(), fields[1].toInt(), fields[2].toInt(), fields[3])
        }.getOrNull()
        fun time(minute: Int): String = "%02d:%02d".format(java.util.Locale.ROOT, minute / 60, minute % 60)
        fun minute(text: String): Int {
            require(Regex("[0-9]{2}:[0-9]{2}").matches(text)) { "Время должно быть в формате ЧЧ:ММ" }
            val (h, m) = text.split(':').map { it.toInt() }
            require(h in 0..23 && m in 0..59) { "Некорректное время" }
            return h * 60 + m
        }
    }
}

/** A weekly schedule is the union of independently configured intervals. */
data class GroupQuietSchedule(val intervals: List<GroupQuietHours>) {
    init {
        require(intervals.isNotEmpty() && intervals.size <= 32) { "Добавьте от 1 до 32 интервалов" }
        require(intervals.map { it.zoneId }.distinct().size == 1) { "Выберите общий часовой пояс группы" }
    }
    fun blocks(atMs: Long): Boolean = intervals.any { it.blocks(atMs) }
    /** Next real change of the union, including DST transitions and touching intervals. */
    fun nextChangeAtMs(atMs: Long): Long? {
        val now = Instant.ofEpochMilli(atMs)
        val zone = ZoneId.of(intervals.first().zoneId)
        val date = now.atZone(zone).toLocalDate()
        val candidates = sortedSetOf<Long>()
        // A weekly schedule repeats within eight local days; include both
        // occurrences of ambiguous wall times during the autumn clock change.
        for (day in 0L..8L) {
            val localDate = date.plusDays(day)
            for (interval in intervals) {
                for (minute in listOf(interval.startMinute, interval.endMinute)) {
                    val wall = localDate.atStartOfDay().plusMinutes(minute.toLong())
                    val offsets = zone.rules.getValidOffsets(wall)
                    if (offsets.isEmpty()) candidates.add(wall.atZone(zone).toInstant().toEpochMilli())
                    else offsets.forEach { candidates.add(wall.toInstant(it).toEpochMilli()) }
                }
            }
        }
        val until = date.plusDays(9).atStartOfDay(zone).toInstant()
        var transition = zone.rules.nextTransition(now)
        while (transition != null && transition.instant.isBefore(until)) {
            candidates.add(transition.instant.toEpochMilli())
            transition = zone.rules.nextTransition(transition.instant.plusSeconds(1))
        }
        val blocked = blocks(atMs)
        return candidates.firstOrNull { it > atMs && blocks(it) != blocked }
    }

    fun notice(
        atMs: Long = System.currentTimeMillis(),
        viewerZone: ZoneId = ZoneId.systemDefault(),
        locale: java.util.Locale = java.util.Locale.getDefault(),
    ): String {
        val blocked = blocks(atMs)
        val next = nextChangeAtMs(atMs)
        val english = locale.language == "en"
        if (next == null) return if (blocked) {
            if (english) "Messaging is restricted continuously by the group schedule." else "По расписанию группы запрет действует непрерывно."
        } else {
            if (english) "Messaging is currently allowed." else "Сейчас писать можно."
        }
        val local = Instant.ofEpochMilli(next).atZone(viewerZone)
        val time = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", locale).format(local)
        val offset = local.offset.id.let { if (it == "Z") "+00:00" else it }
        return if (english) {
            if (blocked) "Messaging is restricted. You can write from $time (your local time, UTC$offset)."
            else "Messaging is allowed. The next restriction starts at $time (your local time, UTC$offset)."
        } else {
            if (blocked) "Сейчас писать нельзя. Можно писать с $time (ваше местное время, UTC$offset)."
            else "Сейчас писать можно. Следующий запрет — с $time (ваше местное время, UTC$offset)."
        }
    }
    fun encode(): String = intervals.joinToString("~") { it.encode() }
    companion object {
        // A previously saved single interval is also a valid schedule.
        fun decode(raw: String): GroupQuietSchedule? = runCatching {
            require(raw.isNotEmpty() && raw.length <= 6400)
            GroupQuietSchedule(raw.split('~').map { requireNotNull(GroupQuietHours.decode(it)) })
        }.getOrNull()
    }
}
