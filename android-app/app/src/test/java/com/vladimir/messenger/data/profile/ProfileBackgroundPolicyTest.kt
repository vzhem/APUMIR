package com.vladimir.messenger.data.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileBackgroundPolicyTest {
    @Test
    fun samplingNeverUpscalesAndKeepsPowerOfTwo() {
        assertEquals(1, ProfileBackgroundPolicy.sampleSize(800, 600))
        assertEquals(1, ProfileBackgroundPolicy.sampleSize(1600, 900))
        assertEquals(2, ProfileBackgroundPolicy.sampleSize(3200, 1800))
        assertEquals(8, ProfileBackgroundPolicy.sampleSize(12000, 9000))
    }

    @Test
    fun scaledSizeFitsWithoutUpscaling() {
        assertEquals(1600 to 900, ProfileBackgroundPolicy.scaledSize(3200, 1800, 1600))
        assertEquals(800 to 600, ProfileBackgroundPolicy.scaledSize(800, 600, 1600))
        assertEquals(1 to 1, ProfileBackgroundPolicy.scaledSize(10, 10000, 1))
    }

    @Test
    fun sourceDimensionsAreBounded() {
        assertTrue(ProfileBackgroundPolicy.validSourceDimensions(1, 1))
        assertTrue(ProfileBackgroundPolicy.validSourceDimensions(20000, 12000))
        assertFalse(ProfileBackgroundPolicy.validSourceDimensions(0, 100))
        assertFalse(ProfileBackgroundPolicy.validSourceDimensions(100, -1))
        assertFalse(ProfileBackgroundPolicy.validSourceDimensions(20001, 100))
    }

    @Test
    fun onlyExactManagedNamesAreAccepted() {
        val name = "background-123e4567-e89b-12d3-a456-426614174000.jpg"
        assertTrue(ProfileBackgroundPolicy.isImageFileName(name))
        assertTrue(ProfileBackgroundPolicy.isTempFileName("$name.tmp"))
        assertTrue(ProfileBackgroundPolicy.isManagedFileName("$name.tmp"))
        assertFalse(ProfileBackgroundPolicy.isImageFileName("../$name"))
        assertFalse(ProfileBackgroundPolicy.isImageFileName("$name.tmp"))
        assertFalse(ProfileBackgroundPolicy.isTempFileName(name))
        assertFalse(ProfileBackgroundPolicy.isManagedFileName("avatar.jpg"))
        assertFalse(ProfileBackgroundPolicy.isManagedFileName("background-123e4567-e89b-12d3-a456-426614174000.jpg/../../secret"))
    }

    @Test
    fun compressionLadderIsBoundedAndDescending() {
        assertEquals(ProfileBackgroundPolicy.MAX_SIDE, ProfileBackgroundPolicy.SIDES.first())
        assertEquals(ProfileBackgroundPolicy.SIDES, ProfileBackgroundPolicy.SIDES.sortedDescending())
        assertEquals(ProfileBackgroundPolicy.QUALITIES, ProfileBackgroundPolicy.QUALITIES.sortedDescending())
        assertTrue(ProfileBackgroundPolicy.MAX_IMAGE_BYTES <= 512 * 1024)
        assertTrue(ProfileBackgroundPolicy.MAX_SOURCE_BYTES <= 24L * 1024 * 1024)
    }
}
