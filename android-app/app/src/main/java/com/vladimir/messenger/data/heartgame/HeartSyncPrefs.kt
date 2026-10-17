package com.vladimir.messenger.data.heartgame

import android.content.Context

/**
 * Время последних рассылок сердечек. Хранится в SharedPreferences, а не в памяти:
 * иначе перезапуск приложения сбрасывает отсчёт и рассылка уходит снова и снова.
 */
internal object HeartSyncPrefs {
    private const val PREFS = "apu_heart_sync_v1"
    private const val KEY_GLOBAL = "last_global_ms"
    private const val KEY_NEWCOMER = "last_newcomer_ms"
    private const val PEER_PREFIX = "peer_"

    fun lastGlobal(context: Context): Long = prefs(context).getLong(KEY_GLOBAL, 0L)
    fun markGlobal(context: Context, nowMs: Long) = prefs(context).edit().putLong(KEY_GLOBAL, nowMs).apply()

    fun lastNewcomer(context: Context): Long = prefs(context).getLong(KEY_NEWCOMER, 0L)
    fun markNewcomer(context: Context, nowMs: Long) = prefs(context).edit().putLong(KEY_NEWCOMER, nowMs).apply()

    fun lastPeer(context: Context, contactId: String): Long = prefs(context).getLong(PEER_PREFIX + contactId, 0L)
    fun markPeer(context: Context, contactId: String, nowMs: Long) =
        prefs(context).edit().putLong(PEER_PREFIX + contactId, nowMs).apply()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
