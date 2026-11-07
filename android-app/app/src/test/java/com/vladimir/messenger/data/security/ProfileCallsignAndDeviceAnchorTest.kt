package com.vladimir.messenger.data.security

import com.vladimir.messenger.util.ProfileCallsign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCallsignAndDeviceAnchorTest {

    private val aliceOld = "pk_" + "1a".repeat(32)
    private val aliceNew = "pk_" + "2b".repeat(32)
    private val bob = "pk_" + "3c".repeat(32)

    @Test
    fun `profile callsign is deterministic 6-digit NNN-NNN for canonical pk address`() {
        val codeLower = ProfileCallsign.of(aliceOld)
        val codeUpper = ProfileCallsign.of(aliceOld.uppercase())
        assertNotNull(codeLower)
        assertEquals(codeLower, codeUpper)
        assertTrue("Expected NNN-NNN, got $codeLower", Regex("""^\d{3}-\d{3}$""").matches(codeLower!!))
        assertEquals("APU-$codeLower", ProfileCallsign.withPrefix(aliceOld))
        assertNotEquals(codeLower, ProfileCallsign.of(bob))

        assertNull(ProfileCallsign.of(null))
        assertNull(ProfileCallsign.of(""))
        assertNull(ProfileCallsign.of("pk_short"))
        assertNull(ProfileCallsign.of("grp_" + "1a".repeat(32)))
    }

    @Test
    fun `profile callsign matches full and partial user queries with or without APU prefix`() {
        val code = ProfileCallsign.of(aliceOld)!!
        val digits = code.replace("-", "")

        assertTrue(ProfileCallsign.isFullCodeQuery(code))
        assertTrue(ProfileCallsign.isFullCodeQuery(digits))
        assertTrue(ProfileCallsign.isFullCodeQuery("APU-$code"))
        assertTrue(ProfileCallsign.isFullCodeQuery("apu $digits"))
        assertTrue(ProfileCallsign.isFullCodeQuery("#$code"))
        assertFalse(ProfileCallsign.isFullCodeQuery(digits.take(4)))
        assertFalse(ProfileCallsign.isFullCodeQuery("@vladimir"))

        assertTrue(ProfileCallsign.matches(aliceOld, code))
        assertTrue(ProfileCallsign.matches(aliceOld, digits))
        assertTrue(ProfileCallsign.matches(aliceOld, "APU-$digits"))
        assertTrue(ProfileCallsign.matches(aliceOld, digits.take(3)))
        assertFalse(ProfileCallsign.matches(aliceOld, "@vladimir"))
    }

    @Test
    fun `android id normalization rejects empty zero and emulator constants`() {
        assertEquals("a1b2c3d4e5f60718", DeviceProfileAnchor.normalizeAndroidId("A1B2C3D4E5F60718"))
        assertNull(DeviceProfileAnchor.normalizeAndroidId(null))
        assertNull(DeviceProfileAnchor.normalizeAndroidId(""))
        assertNull(DeviceProfileAnchor.normalizeAndroidId("0000000000000000"))
        assertNull(DeviceProfileAnchor.normalizeAndroidId("9774d56d682e549c"))
        assertNull(DeviceProfileAnchor.normalizeAndroidId("short"))
    }

    @Test
    fun `encrypted device hint roundtrips for same android id and fails for foreign device`() {
        val phoneA = "a1b2c3d4e5f60718"
        val phoneB = "f0e1d2c3b4a59687"

        val shelfA1 = DeviceProfileAnchor.deviceShelf(phoneA)
        val shelfA2 = DeviceProfileAnchor.deviceShelf(phoneA.uppercase())
        val shelfB = DeviceProfileAnchor.deviceShelf(phoneB)
        assertNotNull(shelfA1)
        assertEquals(shelfA1, shelfA2)
        assertEquals(64, shelfA1!!.length)
        assertNotEquals(shelfA1, shelfB)

        val record = DeviceProfileAnchor.DeviceHintRecord(
            nodeId = aliceNew,
            nickname = "@Vladimir",
            displayName = "Владимир",
            callsign = "",
            registeredAtMs = 1_762_000_000_000L,
            updatedAtMs = 1_762_000_005_000L,
            anchorPublicKeyHex = "",
        )
        val sealed = DeviceProfileAnchor.sealHint(record, phoneA)
        assertNotNull(sealed)
        assertFalse(
            "Ciphertext must not leak username",
            String(sealed!!, Charsets.ISO_8859_1).contains("vladimir", ignoreCase = true),
        )

        val restored = DeviceProfileAnchor.openHint(sealed, phoneA)
        assertNotNull(restored)
        assertEquals(aliceNew, restored!!.nodeId)
        assertEquals("vladimir", restored.nickname)
        assertEquals("Владимир", restored.displayName)
        assertEquals(ProfileCallsign.of(aliceNew), restored.callsign)
        assertEquals(DeviceProfileAnchor.anchorPublicKeyHex(phoneA), restored.anchorPublicKeyHex)

        // Foreign phone cannot decrypt the device shelf hint.
        assertNull(DeviceProfileAnchor.openHint(sealed, phoneB))
    }

    @Test
    fun `signed wire packets verify and ledger proves reinstall replacement without trusting strangers`() {
        val alicePhone = "a1b2c3d4e5f60718"
        val attackerPhone = "1122334455667788"
        val now = 1_762_500_000_000L

        val bindOldRaw = DeviceProfileAnchor.Wire.createBind(aliceOld, alicePhone, now)!!
        val bindOld = DeviceProfileAnchor.Wire.parseVerified(bindOldRaw) as? DeviceProfileAnchor.Wire.Packet.Bind
        assertNotNull(bindOld)

        val ledger = DeviceProfileAnchor.PeerDeviceAnchorLedger()
        assertTrue(ledger.pinOrVerify(bindOld!!.ownerId, bindOld.anchorPubHex))

        // Stranger from another phone cannot claim to be the same physical device.
        val attackerReplaceRaw = DeviceProfileAnchor.Wire.createReplace(
            oldOwnerId = aliceOld,
            newOwnerId = bob,
            nickname = "vladimir",
            displayName = "Fake",
            androidId = attackerPhone,
            timestampMs = now + 1_000L,
        )!!
        val attackerReplace = DeviceProfileAnchor.Wire.parseVerified(attackerReplaceRaw)
            as DeviceProfileAnchor.Wire.Packet.Replace
        assertFalse(
            ledger.acceptVerifiedReplacement(
                attackerReplace.oldOwnerId,
                attackerReplace.newOwnerId,
                attackerReplace.anchorPubHex,
            ),
        )
        assertFalse(ledger.areSameVerifiedDevice(aliceOld, bob))
        assertNull(ledger.replacementOf(aliceOld))

        // Genuine reinstall on the SAME physical phone proves ownership of the same anchor key.
        val genuineReplaceRaw = DeviceProfileAnchor.Wire.createReplace(
            oldOwnerId = aliceOld,
            newOwnerId = aliceNew,
            nickname = "vladimir",
            displayName = "Владимир",
            androidId = alicePhone,
            timestampMs = now + 2_000L,
            hops = 1,
        )!!
        val genuineReplace = DeviceProfileAnchor.Wire.parseVerified(genuineReplaceRaw)
            as DeviceProfileAnchor.Wire.Packet.Replace
        assertEquals(1, genuineReplace.hops)
        assertTrue(
            ledger.acceptVerifiedReplacement(
                genuineReplace.oldOwnerId,
                genuineReplace.newOwnerId,
                genuineReplace.anchorPubHex,
            ),
        )
        assertTrue(ledger.areSameVerifiedDevice(aliceOld, aliceNew))
        assertEquals(aliceNew, ledger.replacementOf(aliceOld))

        // Snapshot serialize/deserialize preserves verified device anchor and replacement links.
        val restored = DeviceProfileAnchor.PeerDeviceAnchorLedger.deserialize(ledger.serialize())
        assertTrue(restored.areSameVerifiedDevice(aliceOld, aliceNew))
        assertEquals(aliceNew, restored.replacementOf(aliceOld))
        assertFalse(restored.areSameVerifiedDevice(aliceNew, bob))

        // Tampered wire payload must fail signature verification.
        val tampered = genuineReplaceRaw.replace(aliceNew, bob)
        assertNull(DeviceProfileAnchor.Wire.parseVerified(tampered))
    }
}
