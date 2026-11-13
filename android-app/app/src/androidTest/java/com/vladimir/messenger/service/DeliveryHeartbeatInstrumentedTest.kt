package com.vladimir.messenger.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vladimir.messenger.data.security.DeviceIdentityMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Android preferences and alarm adapter with synthetic owners, not real Service callbacks. */
@RunWith(AndroidJUnit4::class)
class DeliveryHeartbeatInstrumentedTest {
    @Test
    fun oldServiceCallbacksDoNotClearOrRefreshAReplacementHeartbeat() = withFixture { context ->
        val alarms = context.getSharedPreferences("emergency_keep_alive", Context.MODE_PRIVATE)
        val old = Any()
        val replacement = Any()
        val now = System.currentTimeMillis()
        EmergencyKeepAlive.onServiceCreated(old)
        assertTrue(DeliveryAssurance.serviceStarted(context, old, now - 1_000L))
        EmergencyKeepAlive.onServiceCreated(replacement)
        alarms.edit().putInt("consecutive_refusals", 7).commit()

        // A replacement owns the pending start even before it has a heartbeat.
        EmergencyKeepAlive.onServiceStarted(context, old)
        EmergencyKeepAlive.onServiceStopped(context, old, intentional = false)
        EmergencyKeepAlive.onServiceRefused(context, old)
        EmergencyKeepAlive.onTaskRemoved(context, old)
        assertEquals(0L, DeliveryAssurance.currentProcessLastAliveMs())
        assertEquals(7, EmergencyKeepAlive.refusalsForDiagnostics(context))

        assertTrue(DeliveryAssurance.serviceStarted(context, replacement, now))
        DeliveryAssurance.markAlive(context, old, now + 1_000L)
        EmergencyKeepAlive.onServiceStopped(context, old, intentional = false)
        EmergencyKeepAlive.onServiceRefused(context, old)
        assertEquals(now, DeliveryAssurance.currentProcessLastAliveMs())
        assertEquals(now, DeliveryAssurance.lastAliveMs(context))
        assertEquals(7, EmergencyKeepAlive.refusalsForDiagnostics(context))

        EmergencyKeepAlive.onServiceRefused(context, replacement)
        val refusalsAfterNewOwner = EmergencyKeepAlive.refusalsForDiagnostics(context)
        EmergencyKeepAlive.onServiceStopped(context, old, intentional = false)
        assertEquals(refusalsAfterNewOwner, EmergencyKeepAlive.refusalsForDiagnostics(context))
        EmergencyKeepAlive.onServiceStopped(context, replacement, intentional = false)
        assertEquals(0L, DeliveryAssurance.currentProcessLastAliveMs())
        assertFalse(DeliveryAssurance.serviceStarted(context, replacement, now + 2_000L))
    }

    @Test
    fun externalStartRefusalDoesNotDisableTheRunningServicesFuturePulses() = withFixture { context ->
        val owner = Any()
        val now = System.currentTimeMillis()
        EmergencyKeepAlive.onServiceCreated(owner)
        assertTrue(DeliveryAssurance.serviceStarted(context, owner, now))
        EmergencyKeepAlive.onExternalStartRefused(context)
        assertEquals(now, DeliveryAssurance.currentProcessLastAliveMs())

        // Simulate a stale heartbeat and failed external request. A later real
        // service pulse must still be able to make the heartbeat fresh again.
        DeliveryAssurance.markAlive(context, owner, now - DeliveryWakePolicy.STALE_AFTER_MS - 1L)
        EmergencyKeepAlive.onExternalStartRefused(context)
        DeliveryAssurance.markAlive(context, owner, now + 1L)
        assertEquals(now + 1L, DeliveryAssurance.currentProcessLastAliveMs())
        EmergencyKeepAlive.onServiceStopped(context, owner, intentional = false)
    }

    private fun withFixture(block: (Context) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val profile = context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
        // Never overwrite liveness or alarms on an installed user's profile.
        assertFalse(profile.getBoolean("identity_created", false))
        assertFalse(DeviceIdentityMarker.isPresent(context))
        val delivery = context.getSharedPreferences("apu_delivery_assurance", Context.MODE_PRIVATE)
        val alarms = context.getSharedPreferences("emergency_keep_alive", Context.MODE_PRIVATE)
        val hadLast = delivery.contains("last_alive_ms")
        val previousLast = delivery.getLong("last_alive_ms", 0L)
        val hadRefusals = alarms.contains("consecutive_refusals")
        val previousRefusals = alarms.getInt("consecutive_refusals", 0)
        try {
            block(context)
        } finally {
            val cleanupOwner = Any()
            EmergencyKeepAlive.onServiceCreated(cleanupOwner)
            DeliveryAssurance.serviceStopped(cleanupOwner)
            EmergencyKeepAlive.cancel(context)
            delivery.edit().apply {
                if (hadLast) putLong("last_alive_ms", previousLast) else remove("last_alive_ms")
            }.commit()
            alarms.edit().apply {
                if (hadRefusals) putInt("consecutive_refusals", previousRefusals) else remove("consecutive_refusals")
            }.commit()
        }
    }
}
