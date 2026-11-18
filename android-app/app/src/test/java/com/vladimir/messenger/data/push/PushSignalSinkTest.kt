package com.vladimir.messenger.data.push

import org.junit.Assert.assertEquals
import org.junit.Test

class PushSignalSinkTest {
    private val now = 1_800_000_000_000L

    private val route = "ab".repeat(32)

    @Test fun onlyTheExactDataOnlySignalWritesAGenericMarker() {
        var recoveries = 0
        val sink = PushSignalSink({ recoveries++; PushWakeIntake.Result.QUEUED }, { PushWakeIntake.Result.IGNORED }, { now })
        assertEquals(PushSignalSink.Result.QUEUED, sink.onMessage(mapOf("v" to "1", "route" to route), false))
        assertEquals(1, recoveries)
        // The route is only a lookup hint: it is never stored on the generic
        // marker, and a shape with any other key is rejected outright.
        assertEquals(PushSignalSink.Result.IGNORED, sink.onMessage(mapOf("v" to "1"), false))
        assertEquals(PushSignalSink.Result.IGNORED, sink.onMessage(mapOf("v" to "1", "route" to route, "t" to "x"), false))
        assertEquals(1, recoveries)
    }

    @Test fun aRoutedSignalQueuesThatRouteInsteadOfAFullScan() {
        val routed = mutableListOf<String>()
        val sink = PushSignalSink(
            onRecovery = { PushWakeIntake.Result.IGNORED },
            onResume = { PushWakeIntake.Result.IGNORED },
            nowMs = { now },
            onRoute = { value, _ -> routed += value; PushWakeIntake.Result.QUEUED },
        )
        assertEquals(PushSignalSink.Result.QUEUED, sink.onRoutedSignal(mapOf("v" to "1", "route" to route), false))
        assertEquals(listOf(route), routed)
        // Without a usable route the conservative marker is used, not a scan of
        // everything the caller happens to hold.
        assertEquals(PushSignalSink.Result.IGNORED, sink.onRoutedSignal(mapOf("v" to "1", "route" to "zz"), false))
        assertEquals(listOf(route), routed)
    }

    @Test fun notificationBlocksAndUnknownVersionsWriteNothing() {
        var recoveries = 0
        val sink = PushSignalSink({ recoveries++; PushWakeIntake.Result.QUEUED }, { PushWakeIntake.Result.IGNORED }, { now })
        assertEquals(
            PushSignalSink.Result.IGNORED,
            sink.onMessage(mapOf("v" to "1", "route" to route), hasNotificationBlock = true),
        )
        assertEquals(PushSignalSink.Result.IGNORED, sink.onMessage(mapOf("v" to "2", "route" to route), false))
        assertEquals(
            PushSignalSink.Result.IGNORED,
            sink.onMessage(mapOf("v" to "1", "route" to route, "text" to "hi"), false),
        )
        assertEquals(PushSignalSink.Result.IGNORED, sink.onMessage(null, false))
        assertEquals(0, recoveries)
    }

    @Test fun aProviderGapAndAStartupResumeAreReportedWithoutInventingSuccess() {
        val queued = PushSignalSink({ PushWakeIntake.Result.QUEUED }, { PushWakeIntake.Result.QUEUED_WITHOUT_SCHEDULER }, { now })
        assertEquals(PushSignalSink.Result.QUEUED, queued.onDeletedMessages())
        assertEquals(PushSignalSink.Result.QUEUED_WITHOUT_SCHEDULER, queued.resume())

        val unavailable = PushSignalSink({ PushWakeIntake.Result.UNAVAILABLE }, { PushWakeIntake.Result.UNAVAILABLE }, { now })
        assertEquals(
            PushSignalSink.Result.UNAVAILABLE,
            unavailable.onMessage(mapOf("v" to "1", "route" to route), false),
        )
        assertEquals(PushSignalSink.Result.UNAVAILABLE, unavailable.resume())
    }
}
