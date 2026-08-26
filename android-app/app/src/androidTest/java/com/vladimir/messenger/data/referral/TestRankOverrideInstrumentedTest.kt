package com.vladimir.messenger.data.referral

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vladimir.messenger.data.file.FileTransferRankPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TestRankOverrideInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun setExplicitDebugRankForTestPhone() {
        val requested = InstrumentationRegistry.getArguments()
            .getString("qualified_referrals")
            ?.toIntOrNull()
            ?: error("Missing qualified_referrals")
        assertTrue(ReferralRankStore.setDebugOverride(context, requested))
        val actual = ReferralRankStore.qualifiedDirectCount(context)
        assertEquals(requested, actual)
        val entitlement = FileTransferRankPolicy.entitlement(actual)
        if (requested == 1_000) {
            assertTrue(entitlement.canCreateGroup)
            assertTrue(entitlement.canUseAutomaticProxy)
            assertTrue(entitlement.canCreateChannel)
        }
    }
}
