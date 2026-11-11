package com.vladimir.messenger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vladimir.messenger.data.security.DeviceIdentityMarker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

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
