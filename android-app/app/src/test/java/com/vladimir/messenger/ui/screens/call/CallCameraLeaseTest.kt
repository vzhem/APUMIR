package com.vladimir.messenger.ui.screens.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallCameraLeaseTest {
    @Test fun disposedListenerCannotBindOrReleaseTheNewPreview() {
        val releases = mutableListOf<String>()
        val lease = CallCameraLease<String> { releases += it }
        val old = lease.newOwner()
        lease.dispose(old)
        val current = lease.newOwner()
        assertTrue(lease.install(current, "new") {})
        assertFalse(lease.install(old, "old") { error("stale listener bound") })
        lease.dispose(old)
        assertEquals(emptyList<String>(), releases)
        lease.dispose(current)
        assertEquals(listOf("new"), releases)
    }

    @Test fun synchronousReplacementCannotBeOverwrittenByOlderActivation() {
        val releases = mutableListOf<String>()
        val lease = CallCameraLease<String> { releases += it }
        val old = lease.newOwner()
        var current = -1L
        assertFalse(lease.install(old, "old") {
            current = lease.newOwner()
            assertTrue(lease.install(current, "new") {})
        })
        lease.dispose(old)
        assertEquals(listOf("old"), releases)
        lease.dispose(current)
        assertEquals(listOf("old", "new"), releases)
    }

    @Test fun replacementReleasesOnlyPreviousOwnerAndFailureCleansPartialBinding() {
        val releases = mutableListOf<String>()
        val lease = CallCameraLease<String> { releases += it }
        val old = lease.newOwner()
        assertTrue(lease.install(old, "old") {})
        val current = lease.newOwner()
        assertTrue(lease.install(current, "new") {})
        lease.dispose(old)
        assertEquals(listOf("old"), releases)
        val failed = lease.newOwner()
        try {
            lease.install(failed, "partial") { error("bind failed") }
        } catch (_: IllegalStateException) { }
        lease.dispose(failed)
        assertEquals(listOf("old", "new", "partial"), releases)
    }
}
