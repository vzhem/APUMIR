package com.vladimir.messenger.data.security

import org.junit.Assert.*
import org.junit.Test

class EphemeralControlTest {
    @Test fun typingAndCatalogHintsAreStableAcrossRetryButSeparateAcrossRecipients() {
        for (text in listOf("APUTYP1|1", "APUTYP1|0", "APUGRP1|typ|group|topic|1", "APUGIF1|have|[]", "APUSTK1|thmb|opaque")) {
            val id = EphemeralControl.transportId("caller-id", "peer-a", text)
            assertTrue(id.startsWith("eph1-"))
            assertEquals(id, EphemeralControl.transportId("caller-id", "peer-a", text))
            assertNotEquals(id, EphemeralControl.transportId("caller-id", "peer-b", text))
        }
    }
    @Test fun userMessagesMediaReferencesAndDurableControlsKeepTheirIds() {
        for (text in listOf("обычное письмо", "APUGIF1|ref|media", "APUSTK1|ref|media", "APUGRP1|delete|message", "APUTYP1|invalid", "APUGRP1|typ|g|t|2", "APUGRP1|hello|binding")) {
            assertFalse(EphemeralControl.isReplaceable(text))
            assertEquals("user-id", EphemeralControl.transportId("user-id", "peer", text))
        }
    }
}
