package com.vladimir.messenger.data.rank

import org.junit.Assert.assertEquals
import org.junit.Test

class RankProgressPolicyTest {

    @Test
    fun everyThousandHeartsAddsOneRankUnit() {
        assertEquals(0, RankProgressPolicy.rankUnits(0))
        assertEquals(0, RankProgressPolicy.rankUnits(999))
        assertEquals(1, RankProgressPolicy.rankUnits(1_000))
        assertEquals(1, RankProgressPolicy.rankUnits(1_999))
        assertEquals(2, RankProgressPolicy.rankUnits(2_000))
    }

    @Test
    fun oneConfirmedInvitationIsExactlyOneThousandHearts() {
        assertEquals(1_000L, RankProgressPolicy.referralBonusHearts(1))
        assertEquals(7_000L, RankProgressPolicy.referralBonusHearts(7))
    }

    @Test
    fun promoBonusDoesNotPretendToBeGameHearts() {
        assertEquals(2, RankProgressPolicy.rankUnits(1_000, promoBonus = 1))
        assertEquals(1_000L, RankProgressPolicy.referralBonusHearts(1))
    }

    @Test
    fun partialProgressAndNextHeartCountAreStable() {
        assertEquals(999, RankProgressPolicy.heartsIntoCurrentRank(1_999))
        assertEquals(1, RankProgressPolicy.heartsToNextRank(1_999))
        assertEquals(1_000, RankProgressPolicy.heartsToNextRank(2_000))
    }

    @Test
    fun negativeAndExcessValuesAreBounded() {
        assertEquals(0, RankProgressPolicy.rankUnits(-1))
        assertEquals(RankProgressPolicy.MAX_RANK_UNITS, RankProgressPolicy.rankUnits(Long.MAX_VALUE))
        assertEquals(0L, RankProgressPolicy.referralBonusHearts(-3))
    }
}
