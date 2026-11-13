package com.vladimir.messenger.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExistingRoutingIdentityTest {
    private val id = "pk_" + "a".repeat(64)
    private val other = "pk_" + "b".repeat(64)

    @Test
    fun `missing legacy private placeholder preserves the old route`() {
        val recovered = ExistingRoutingIdentity.resolve(id, id, null, null)!!
        assertEquals(id, recovered.nodeId)
        assertEquals(id, recovered.legacyPrivateKey)
        assertTrue(recovered.usedPlaceholder)
    }

    @Test
    fun `saved private value is not replaced even when public preference is missing`() {
        val recovered = ExistingRoutingIdentity.resolve(id, null, null, "sk_original")!!
        assertEquals(id, recovered.nodeId)
        assertEquals("sk_original", recovered.legacyPrivateKey)
        assertFalse(recovered.usedPlaceholder)
    }

    @Test
    fun `any one consistent routing field can restore the legacy route`() {
        assertEquals(id, ExistingRoutingIdentity.resolve(null, null, id, "sk_original")!!.nodeId)
        assertEquals(id, ExistingRoutingIdentity.resolve(null, id, null, null)!!.nodeId)
        assertEquals(id, ExistingRoutingIdentity.resolve(" $id ", null, null, " ")!!.nodeId)
    }

    @Test
    fun `missing malformed or conflicting routes are never guessed`() {
        assertNull(ExistingRoutingIdentity.resolve(null, null, null, "sk_original"))
        assertNull(ExistingRoutingIdentity.resolve(id, other, id, "sk_original"))
        assertNull(ExistingRoutingIdentity.resolve(id, null, "invalid", "sk_original"))
        assertNull(ExistingRoutingIdentity.resolve("pk_short", null, null, null))
    }

    @Test
    fun `a running engine must match the persisted routing identity`() {
        assertTrue(ExistingRoutingIdentity.matchesRunning(id, id))
        assertFalse(ExistingRoutingIdentity.matchesRunning(id, other))
        assertFalse(ExistingRoutingIdentity.matchesRunning(id, null))
        // A new onboarding flow intentionally has no existing identity.
        assertTrue(ExistingRoutingIdentity.matchesRunning(null, id))
    }

    @Test
    fun `an installed sidecar may not silently belong to a different route`() {
        assertTrue(ExistingRoutingIdentity.matchesInstalled(id, null)) // legacy-only
        assertTrue(ExistingRoutingIdentity.matchesInstalled(id, id))
        assertFalse(ExistingRoutingIdentity.matchesInstalled(id, other))
        assertTrue(ExistingRoutingIdentity.matchesInstalled(null, other)) // onboarding
    }
}
