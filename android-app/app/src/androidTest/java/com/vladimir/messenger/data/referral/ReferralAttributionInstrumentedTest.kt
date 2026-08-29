package com.vladimir.messenger.data.referral

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Проверяет атрибуцию приглашения на устройстве.
 *
 * Все записи идут в отдельные тестовые наборы preferences, поэтому ни настоящий
 * счётчик `qualified_direct_count_v1`, ни список зачисленных приглашённых на
 * этом телефоне не меняются.
 */
@RunWith(AndroidJUnit4::class)
class ReferralAttributionInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val attributionPrefs = "apu_referral_attribution_test_instrumented_v1"
    private val rankPrefs = "apu_referral_qualification_test_instrumented_v1"

    private val invitee = "pk_" + "11".repeat(32)
    private val inviter = "pk_" + "22".repeat(32)

    private fun reset() {
        context.deleteSharedPreferences(attributionPrefs)
        context.deleteSharedPreferences(rankPrefs)
    }

    @Test
    fun invitedContactRemembersTheInviterUntilAttributionIsSent() {
        reset()
        try {
            assertTrue(ReferralAttributionStore.rememberInviterIn(context, attributionPrefs, invitee, inviter))
            assertEquals(inviter, ReferralAttributionStore.pendingInviterIn(context, attributionPrefs, invitee))

            assertTrue(ReferralAttributionStore.markAttributionSentIn(context, attributionPrefs, invitee))
            assertNull(
                "после успешной отправки атрибуция не повторяется",
                ReferralAttributionStore.pendingInviterIn(context, attributionPrefs, invitee),
            )
        } finally {
            reset()
        }
    }

    @Test
    fun contactAddedWithoutALinkHasNoAttribution() {
        reset()
        try {
            assertNull(ReferralAttributionStore.pendingInviterIn(context, attributionPrefs, invitee))
            assertFalse(ReferralAttributionStore.rememberInviterIn(context, attributionPrefs, invitee, "не-узел"))
        } finally {
            reset()
        }
    }

    @Test
    fun theSameInviteeIsCreditedOnlyOnce() {
        reset()
        try {
            assertTrue(ReferralAttributionStore.markCreditedIn(context, attributionPrefs, invitee))
            assertFalse(ReferralAttributionStore.markCreditedIn(context, attributionPrefs, invitee))
            assertFalse(
                "регистр идентификатора не должен давать второе начисление",
                ReferralAttributionStore.markCreditedIn(context, attributionPrefs, invitee.uppercase()),
            )
            assertEquals(setOf(invitee), ReferralAttributionStore.creditedInviteesIn(context, attributionPrefs))
        } finally {
            reset()
        }
    }

    @Test
    fun theCounterGrowsOnlyOnFreshCreditsAndStopsAtTheCap() {
        reset()
        try {
            assertEquals(0, ReferralRankStore.qualifiedDirectCountIn(context, rankPrefs))
            assertEquals(1, ReferralRankStore.creditQualifiedDirectIn(context, rankPrefs, 1))
            assertEquals(1, ReferralRankStore.creditQualifiedDirectIn(context, rankPrefs, 0))
            assertEquals(4, ReferralRankStore.creditQualifiedDirectIn(context, rankPrefs, 3))
            assertEquals(
                ReferralRankStore.MAX_SUPPORTED_COUNT,
                ReferralRankStore.creditQualifiedDirectIn(context, rankPrefs, ReferralRankStore.MAX_SUPPORTED_COUNT),
            )
            assertEquals(
                "счётчик не растёт выше поддерживаемого предела",
                ReferralRankStore.MAX_SUPPORTED_COUNT,
                ReferralRankStore.creditQualifiedDirectIn(context, rankPrefs, 1),
            )
        } finally {
            reset()
        }
    }
}
