package com.vladimir.messenger.data.schedule

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/** Local wall-clock selection becomes a fixed instant when the user confirms. */
object SendTimePolicy {
    val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.uuuu", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT)
    val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT)

    fun resolve(date: String, time: String, zone: ZoneId): Long? = runCatching {
        val local = LocalDateTime.of(LocalDate.parse(date, dateFormat), LocalTime.parse(time, timeFormat))
        require(local.year in 1..9999)
        // A missing spring-DST hour must not silently turn into another time.
        // During the repeated autumn hour choose the earlier occurrence consistently.
        val offset = zone.rules.getValidOffsets(local).firstOrNull() ?: return@runCatching null
        local.toInstant(offset).toEpochMilli()
    }.getOrNull()

    fun isFuture(sendAtMs: Long?, nowMs: Long): Boolean = sendAtMs != null && sendAtMs > nowMs
}
