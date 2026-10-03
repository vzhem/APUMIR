package com.vladimir.messenger.data.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSyncDeviceIdTest {
    private val phoneA = "d_" + "a".repeat(32)
    private val phoneB = "d_" + "b".repeat(32)

    @Test
    fun deviceMarkerIsAValidRandom128BitId() {
        val first = ProfileSyncDeviceId.newId()
        val second = ProfileSyncDeviceId.newId()

        assertTrue(ProfileSyncDeviceId.isValid(first))
        assertTrue(ProfileSyncDeviceId.isValid(second))
        assertNotEquals(first, second)
    }

    @Test
    fun sameAccountOnDifferentPhonesIsRecognizedAsAnotherDevice() {
        // nodeId личности один и тот же, но метки установок разные.
        assertTrue(ProfileSyncNet.isForeignDevice(
            ProfileSyncNet.NetMeta(timeMs = 1L, sizeBytes = 100L, dev = phoneB),
            phoneA,
            "pk_same-account",
        ))
        assertFalse(ProfileSyncNet.isForeignDevice(
            ProfileSyncNet.NetMeta(timeMs = 1L, sizeBytes = 100L, dev = phoneA),
            phoneA,
            "pk_same-account",
        ))
    }

    @Test
    fun oldAccountNodeMarkersRemainRecognizable() {
        assertFalse(ProfileSyncNet.isForeignDevice(
            ProfileSyncNet.NetMeta(timeMs = 1L, sizeBytes = 100L, dev = "pk_same-account"),
            phoneA,
            "pk_same-account",
        ))
        assertTrue(ProfileSyncNet.isForeignDevice(
            ProfileSyncNet.NetMeta(timeMs = 1L, sizeBytes = 100L, dev = "pk_other-account"),
            phoneA,
            "pk_same-account",
        ))
        assertTrue(ProfileSyncNet.isOwnDevice(
            ProfileSyncNet.NetMeta(timeMs = 1L, sizeBytes = 100L, dev = "pk_same-account"),
            phoneA,
            "pk_same-account",
        ))
    }

    @Test
    fun missingOrUnknownDeviceMarkerIsNotMisreportedAsForeignCopy() {
        assertFalse(ProfileSyncNet.isForeignDevice(
            ProfileSyncNet.NetMeta(timeMs = 1L, sizeBytes = 100L, dev = ""),
            phoneA,
            "pk_same-account",
        ))
        assertFalse(ProfileSyncNet.isForeignDevice(null, phoneA, "pk_same-account"))
        assertFalse(ProfileSyncNet.isForeignDevice(
            ProfileSyncNet.NetMeta(timeMs = 1L, sizeBytes = 100L, dev = "unknown"),
            phoneA,
            "pk_same-account",
        ))
    }
}
