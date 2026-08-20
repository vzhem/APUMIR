package com.vladimir.messenger.data.referral

import android.content.Context
import com.vladimir.messenger.BuildConfig

/** Local read boundary for qualified-direct-referral entitlements. */
object ReferralRankStore {
    private const val REAL_PREFS = "apu_referral_qualification"
    private const val REAL_COUNT = "qualified_direct_count_v1"
    private const val TEST_PREFS = "apu_test_entitlements"
    private const val TEST_OVERRIDE = "qualified_direct_override_v1"
    const val MAX_SUPPORTED_COUNT = 1_000

    fun qualifiedDirectCount(context: Context): Int {
        val app = context.applicationContext
        if (BuildConfig.DEBUG) {
            val override = app.getSharedPreferences(TEST_PREFS, Context.MODE_PRIVATE)
                .getInt(TEST_OVERRIDE, -1)
            if (override >= 0) return override.coerceAtMost(MAX_SUPPORTED_COUNT)
        }
        return app.getSharedPreferences(REAL_PREFS, Context.MODE_PRIVATE)
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
}
