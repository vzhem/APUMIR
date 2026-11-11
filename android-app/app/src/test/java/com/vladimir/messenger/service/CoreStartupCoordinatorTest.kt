package com.vladimir.messenger.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises service-owner gating without starting the native engine. */
class CoreStartupCoordinatorTest {
    @Test fun obsoleteOwnerCannotPublishReadyOrQueueANewEngine() = runBlocking {
        val old = Any()
        val current = Any()
        try {
            CoreStartupCoordinator.serviceCreated(old)
            CoreStartupCoordinator.report(old, "old preparing")
            CoreStartupCoordinator.serviceCreated(current)
            CoreStartupCoordinator.report(current, "current preparing")
            CoreStartupCoordinator.report(old, "obsolete report")
            CoreStartupCoordinator.markReady(old, engineStarted = true)
            assertEquals("current preparing", CoreStatus.stage.value)
            assertFalse(CoreStatus.ready.value)
            assertFalse(CoreStatus.engineStarted.value)

            var rejected = false
            try {
                CoreStartupCoordinator.initialize(old, "test", "pk_test", null, "test-path")
            } catch (_: CancellationException) {
                rejected = true
            }
            assertTrue(rejected)
            CoreStartupCoordinator.markReady(current, engineStarted = true)
            assertTrue(CoreStatus.ready.value)
            assertTrue(CoreStatus.engineStarted.value)
            CoreStartupCoordinator.serviceDestroyed(old)
            assertTrue(CoreStatus.engineStarted.value)
            CoreStartupCoordinator.serviceDestroyed(current)
            assertFalse(CoreStatus.engineStarted.value)
            assertTrue(CoreStatus.ready.value)
        } finally {
            CoreStartupCoordinator.serviceDestroyed(old)
            CoreStartupCoordinator.serviceDestroyed(current)
        }
    }
}
