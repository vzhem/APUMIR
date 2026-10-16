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
    fun notice(): String = "По расписанию группы сейчас нельзя писать (${intervals.first().zoneId}). Владелец и администраторы могут писать."
    fun encode(): String = intervals.joinToString("~") { it.encode() }
    companion object {
        // A previously saved single interval is also a valid schedule.
        fun decode(raw: String): GroupQuietSchedule? = runCatching {
            require(raw.isNotEmpty() && raw.length <= 6400)
            GroupQuietSchedule(raw.split('~').map { requireNotNull(GroupQuietHours.decode(it)) })
        }.getOrNull()
    }
}
