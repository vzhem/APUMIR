package com.vladimir.messenger.data.security

import com.vladimir.messenger.data.contactreset.ContactResetWire
import org.junit.Assert.*
import org.junit.Test

class EphemeralControlTest {
    @Test fun typingAndCatalogHintsAreStableAcrossRetryButSeparateAcrossRecipients() {
        val reset = ContactResetWire.build(ContactResetWire.EPOCH, "pk_${"ab".repeat(32)}", "", ByteArray(32) { 1 }, ByteArray(32) { 2 })
        for (text in listOf("APUTYP1|APUGAME1|signed-snapshot", "APUTYP1|1", "APUTYP1|0", "APUGRP1|typ|group|topic|1", "APUGRP1|avv1|an|owner|${"ab".repeat(32)}|42|1|0", "APUGRP1|avv1|rd|owner|${"ab".repeat(32)}", "APUGIF1|have|[]", "APUSTK1|thmb|opaque",
            "APUTYP1|APURANK1|pk_${"ab".repeat(32)}|5|1700000000000",
            "APURANKREQ1|",
            "APUBK1|store|pk_${"ab".repeat(32)}|blob",
            "APUINVREJ1|c|dGl0bGU",
            "APUVIEW1|topic-1|1700000000000",
            "APUHEART1|pk_${"ab".repeat(32)}|1|1700000000000",
            "APUREAD1|chat-1|m1,m2|1700000000000",
            "APUREACT1|chat-1|m1|8J+RiQ|1|1700000000000",
            "APUGRP1|dir|g1|name",
            "APUGRP1|nick|pk_${"ab".repeat(32)}|bmFtZQ|1",
            "APUGRP1|avat|pk_${"ab".repeat(32)}|jpeg",
            "APUGRP1|who|pk_${"ab".repeat(32)}",
            "APUGRP1|upk|11.74.558|${"ab".repeat(32)}|1024|1700000000000",
            // Истории и отклики на них: пакет истории и реакция/комментарий.
            "APUSTORY1:{\"id\":\"s1\",\"kind\":\"publish\",\"text\":\"hi\"}",
            "APUSTORY1:FB1|react|c3Rhcnk|b3duZXI|d2hv|d2hvLW5hbWU|1700000000000|8J+RiQ|1|",
            reset)) {
            val id = EphemeralControl.transportId("caller-id", "peer-a", text)
            assertTrue(id.startsWith("eph1-"))
            assertEquals(id, EphemeralControl.transportId("caller-id", "peer-a", text))
            assertNotEquals(id, EphemeralControl.transportId("caller-id", "peer-b", text))
        }
    }
    @Test fun aMintedTransportIdIsRecognisedAndAPlainMessageIdIsNot() {
        val text = "APUTYP1|APUGAME1|signed-snapshot"
        val minted = EphemeralControl.transportId("caller-id", "peer-a", text)
        assertTrue(EphemeralControl.isTransportId(minted))
        // Обычная переписка и долгие контроли свой ID не меняют.
        assertFalse(EphemeralControl.isTransportId("user-id"))
        // Префикс без канонического UUID — не транспортный ID.
        assertFalse(EphemeralControl.isTransportId("eph1-"))
        assertFalse(EphemeralControl.isTransportId("eph1-not-a-uuid"))
        assertFalse(EphemeralControl.isTransportId("eph1-" + "0".repeat(32)))
    }

    @Test fun userMessagesMediaReferencesAndDurableControlsKeepTheirIds() {
        for (text in listOf("обычное письмо", "APUGIF1|ref|media", "APUSTK1|ref|media", "APUGRP1|delete|message", "APUGRP1|msg|g|t|m|a|b", "APUGRP1|msdel|g|m", "APUGRP1|edit|g|m|b", "APUTYP1|invalid", "APUGRP1|typ|g|t|2", "APUGRP1|hello|binding")) {
            assertFalse(EphemeralControl.isReplaceable(text))
            assertEquals("user-id", EphemeralControl.transportId("user-id", "peer", text))
        }
    }
}
