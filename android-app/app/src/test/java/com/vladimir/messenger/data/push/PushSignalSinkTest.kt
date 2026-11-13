package com.vladimir.messenger.data.push

import org.junit.Assert.assertEquals
import org.junit.Test

class PushSignalSinkTest {
    private val now = 1_800_000_000_000L

    @Test fun onlyTheExactDataOnlySignalWritesAGenericMarker() {
        var recoveries = 0
        val sink = PushSignalSink({ recoveries++; PushWakeIntake.Result.QUEUED }, { PushWakeIntake.Result.IGNORED }, { now })
        assertEquals(PushSignalSink.Result.QUEUED, sink.onMessage(mapOf("v" to "1"), false))
        assertEquals(1, recoveries)
        // A provider-chosen route hint must not be trusted or stored: the shape
        // with an extra key is rejected outright.
        assertEquals(PushSignalSink.Result.IGNORED, sink.onMessage(mapOf("v" to "1", "route" to "ab".repeat(32)), false))
        assertEquals(1, recoveries)
    }

    @Test fun notificationBlocksAndUnknownVersionsWriteNothing() {
        var recoveries = 0
        val sink = PushSignalSink({ recoveries++; PushWakeIntake.Result.QUEUED }, { PushWakeIntake.Result.IGNORED }, { now })
        assertEquals(PushSignalSink.Result.IGNORED, sink.onMessage(mapOf("v" to "1"), hasNotificationBlock = true))
        assertEquals(PushSignalSink.Result.IGNORED, sink.onMessage(mapOf("v" to "2"), false))
        assertEquals(PushSignalSink.Result.IGNORED, sink.onMessage(mapOf("v" to "1", "text" to "hi"), false))
        assertEquals(PushSignalSink.Result.IGNORED, sink.onMessage(null, false))
        assertEquals(0, recoveries)
    }

    @Test fun aProviderGapAndAStartupResumeAreReportedWithoutInventingSuccess() {
        val queued = PushSignalSink({ PushWakeIntake.Result.QUEUED }, { PushWakeIntake.Result.QUEUED_WITHOUT_SCHEDULER }, { now })
        assertEquals(PushSignalSink.Result.QUEUED, queued.onDeletedMessages())
        assertEquals(PushSignalSink.Result.QUEUED_WITHOUT_SCHEDULER, queued.resume())

        val unavailable = PushSignalSink({ PushWakeIntake.Result.UNAVAILABLE }, { PushWakeIntake.Result.UNAVAILABLE }, { now })
        assertEquals(PushSignalSink.Result.UNAVAILABLE, unavailable.onMessage(mapOf("v" to "1"), false))
        assertEquals(PushSignalSink.Result.UNAVAILABLE, unavailable.resume())
    }
}
