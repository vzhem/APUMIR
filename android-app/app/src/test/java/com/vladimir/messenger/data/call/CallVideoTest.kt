package com.vladimir.messenger.data.call

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallVideoTest {
    @Test
    fun videoSeqDoesNotOverlapAudio() {
        assertEquals(0x80000000L, CallVideo.wireSeq(0))
        assertEquals(0x80000001L, CallVideo.wireSeq(1))
        assertTrue(CallVideo.isVideoSeq(CallVideo.wireSeq(0)))
        assertFalse(CallVideo.isVideoSeq(0))
        assertFalse(CallVideo.isVideoSeq(50_000))
    }

    @Test
    fun packUnpackKeepsJpegAndRotation() {
        val jpeg = byteArrayOf(1, 2, 3, 4, 5)
        val packed = CallVideo.pack(90, jpeg)
        val (rot, body) = CallVideo.unpack(packed)!!
        assertEquals(90, rot)
        assertArrayEquals(jpeg, body)
        assertEquals(180, CallVideo.unpack(CallVideo.pack(180, jpeg))!!.first)
        assertEquals(0, CallVideo.unpack(CallVideo.pack(0, jpeg))!!.first)
    }
}
