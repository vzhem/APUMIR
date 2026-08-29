package com.vladimir.messenger.data.referral

import android.content.Context
import com.vladimir.messenger.BuildConfig

/** Local read boundary for qualified-direct-referral entitlements. */
object ReferralRankStore {
    private const val REAL_PREFS = "apu_referral_qualification"
    private const val REAL_COUNT = "qualified_direct_count_v1"
    private const val TEST_PREFS = "apu_test_entitlements"
    private const val TEST_OVERRIDE = "qualified_direct_override_v1"
    private const val TEST_PREFS_PREFIX = "apu_referral_qualification_test_"
    const val MAX_SUPPORTED_COUNT = 1_000

    fun qualifiedDirectCount(context: Context): Int {
        val app = context.applicationContext
        if (BuildConfig.DEBUG) {
            val override = app.getSharedPreferences(TEST_PREFS, Context.MODE_PRIVATE)
                .getInt(TEST_OVERRIDE, -1)
            if (override >= 0) return override.coerceAtMost(MAX_SUPPORTED_COUNT)
        }
        return qualifiedDirectCountIn(context, REAL_PREFS)
    }

    /**
     * Единственный production-писатель настоящего счётчика.
     *
     * До раунда 49 ключ `qualified_direct_count_v1` не писал никто: ранг
     * читался из него повсюду, но приглашения его не поднимали вовсе, и
     * единственным источником числа был debug-override. Вызывается из
     * [ReferralAttributionRouter] и только после того, как идемпотентная
     * отметка [ReferralAttributionStore.markCredited] вернула true.
     *
     * @return новое значение счётчика (с прежним ограничением сверху).
     */
    @Synchronized
    internal fun creditQualifiedDirect(context: Context, by: Int = 1): Int =
        creditQualifiedDirectIn(context, REAL_PREFS, by)

    @Synchronized
    internal fun creditQualifiedDirectIn(context: Context, prefsName: String, by: Int): Int {
        requireAllowedStore(prefsName)
        if (by <= 0) return qualifiedDirectCountIn(context, prefsName)
        val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val current = prefs.getInt(REAL_COUNT, 0).coerceIn(0, MAX_SUPPORTED_COUNT)
        val next = (current + by).coerceIn(0, MAX_SUPPORTED_COUNT)
        if (next == current) return current
        prefs.edit().putInt(REAL_COUNT, next).commit()
        return next
    }

    /** Значение настоящего счётчика без учёта debug-override. */
    @Synchronized
    internal fun qualifiedDirectCountIn(context: Context, prefsName: String): Int {
        requireAllowedStore(prefsName)
        return context.applicationContext
            .getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getInt(REAL_COUNT, 0)
            .coerceIn(0, MAX_SUPPORTED_COUNT)
    }

    /** Test APK only. Release builds reject synthetic entitlement writes. */
    internal fun setDebugOverride(context: Context, qualifiedDirectCount: Int): Boolean {
        check(BuildConfig.DEBUG) { "Test rank override is unavailable in release builds" }
        require(qualifiedDirectCount in 0..MAX_SUPPORTED_COUNT)
        return context.applicationContext
            .getSharedPreferences(TEST_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(TEST_OVERRIDE, qualifiedDirectCount)
            .commit()
    }

    internal fun clearDebugOverride(context: Context): Boolean {
        check(BuildConfig.DEBUG)
        return context.applicationContext
            .getSharedPreferences(TEST_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(TEST_OVERRIDE)
            .commit()
    }

    private fun requireAllowedStore(prefsName: String) {
        require(prefsName == REAL_PREFS || prefsName.startsWith(TEST_PREFS_PREFIX)) {
            "Unexpected referral rank store"
        }
    }
}
