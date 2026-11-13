package com.vladimir.messenger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vladimir.messenger.data.security.DeviceIdentityMarker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.p2p_core.P2pCoreHandle
import uniffi.p2p_core.createEngine
import uniffi.p2p_core.initializeCore

/**
 * Uses an in-memory native engine and synthetic service owners on a fresh test
 * installation. It checks the real Kotlin/FFI queue, not Android Service
 * callbacks, remote push, message delivery or physical phones.
 */
@RunWith(AndroidJUnit4::class)
class CoreLifecycleOwnershipInstrumentedTest {
    @Test(timeout = 120_000L)
    fun staleServiceStopCannotStopARealEngineAdoptedByAnotherOwner() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
        // Never run an anonymous test engine over an installed profile.
        assertFalse(prefs.getBoolean("identity_created", false))
        assertTrue(prefs.getString("node_id", null).isNullOrBlank())
        assertFalse(DeviceIdentityMarker.isPresent(context))
        assertFalse(RustBridge.isRunning())

        val old = Any()
        val replacement = Any()
        try {
            assertTrue(RustBridge.initialize("Lifecycle CI", serviceOwner = old))
            val nodeId = RustBridge.nodeId()
            assertNotNull(nodeId)
            val otherRoute = if (nodeId == "pk_" + "0".repeat(64)) "pk_" + "1".repeat(64) else "pk_" + "0".repeat(64)
            assertTrue(RustBridge.signingRouteCompatible(nodeId!!))
            assertFalse(RustBridge.signingRouteCompatible(otherRoute))
            assertFalse(RustBridge.signingRouteCompatible(""))
            assertTrue(RustBridge.initialize("Lifecycle CI", existingPublicKey = nodeId, serviceOwner = replacement))

            RustBridge.shutdownServiceAsync(old)
            assertTrue(runningAfterLifecycleBarrier())
            assertTrue(RustBridge.isRunning())

            RustBridge.shutdownServiceAsync(replacement)
            assertFalse(runningAfterLifecycleBarrier())
            assertFalse(RustBridge.isRunning())
        } finally {
            // The normal path is already stopped; also clean up on failed assertions.
            RustBridge.shutdown()
        }
    }

    @Test(timeout = 120_000L)
    fun rejectedRealEngineIsStoppedBeforeAReplacementIsPublished() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
        assertFalse(prefs.getBoolean("identity_created", false))
        assertFalse(DeviceIdentityMarker.isPresent(context))
        assertFalse(RustBridge.isRunning())
        initializeCore()
        val handles = mutableListOf<P2pCoreHandle>()
        var simulateStopFailure = true
        var failedStops = 0
        val slot = CoreEngineSlot<P2pCoreHandle>(Any(), {
            if (simulateStopFailure) throw IllegalStateException("simulated stop failure")
            it.stop()
        }, { failedStops++ })
        fun fresh(): P2pCoreHandle = createEngine("Lifecycle slot CI").also(handles::add)
        try {
            val rejected = fresh()
            assertFalse(slot.startNew(
                create = { rejected },
                prepareAndStart = { assertTrue(it.start()); assertTrue(it.isRunning()); false },
                beforePublish = { throw AssertionError("rejected engine must not be published") },
                recordFailure = { throw AssertionError(it) },
            ))
            assertNull(slot.active())
            assertTrue(slot.unsettled())
            assertTrue(rejected.isRunning()) // the simulated failure happened before real native stop
            assertTrue(failedStops == 1)
            var createdWhilePending = false
            assertFalse(slot.startNew({ createdWhilePending = true; fresh() }, { it.start() }, {}, {}))
            assertFalse(createdWhilePending)
            simulateStopFailure = false
            assertTrue(slot.stop())
            assertFalse(slot.unsettled())
            assertFalse(rejected.isRunning())

            val replacement = fresh()
            assertTrue(slot.startNew(
                create = { replacement },
                prepareAndStart = { it.start() },
                beforePublish = { assertNull(slot.active()); assertTrue(slot.unsettled()) },
                recordFailure = { throw AssertionError(it) },
            ))
            assertTrue(slot.active() === replacement)
            assertTrue(replacement.isRunning())
            assertTrue(slot.stop())
            assertFalse(replacement.isRunning())
        } finally {
            simulateStopFailure = false
            slot.stop()
            handles.forEach { it.close() }
        }
    }

    /** The eligibility check runs on the same executor, after every earlier queued task. */
    private fun runningAfterLifecycleBarrier(): Boolean {
        var running = false
        val started = RustBridge.initialize("Lifecycle barrier", startupAllowed = {
            running = RustBridge.isRunningCached()
            false // observe only; do not start or adopt an engine
        })
        assertFalse(started)
        return running
    }
}
