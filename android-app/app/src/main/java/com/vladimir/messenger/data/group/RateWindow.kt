package com.vladimir.messenger.data.group

/**
 * Скользящее окно: не больше [limit] событий за [windowMs] - вынесено из
 * [GroupRepository] (аудит 2026-10-26, п. 4.1), чтобы окном пользовались и
 * вынесенные части (`GroupComments`), и сам репозиторий.
 */
internal class RateWindow(private val limit: Int, private val windowMs: Long) {
    private val stamps = ArrayDeque<Long>()

    @Synchronized
    fun allow(now: Long): Boolean {
        while (stamps.isNotEmpty() && now - stamps.first() > windowMs) stamps.removeFirst()
        if (stamps.size >= limit) return false
        stamps.addLast(now)
        return true
    }
}
