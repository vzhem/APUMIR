package com.vladimir.messenger.data.swarm

import com.vladimir.messenger.data.group.GroupWire
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Манифест поста (рой, этап 1).
 *
 * Первые тесты - вектора RFC 8032 §7.1: они доказывают, что Java-библиотека
 * из того же 32-байтового семени выводит тот же открытый ключ и ту же
 * подпись, что ed25519-dalek в ядре. Иначе подпись манифеста никто бы не
 * проверил.
 */
class PostManifestTest {

    private val seed1 = PostManifest.unhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")!!
    private val pub1 = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
    private val sig1 = "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"

    private val seed2 = PostManifest.unhex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")!!
    private val pub2 = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"
    private val sig2 = "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"

    private val seed3 = PostManifest.unhex("c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7")!!
    private val pub3 = "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025"
    private val sig3 = "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"

    @Test
    fun rfc8032Vector1PublicKeyAndSignature() {
        val pair = Ed25519.keyPairFromSeed(seed1)
        assertEquals(pub1, PostManifest.hex(pair.publicKey))
        assertEquals(sig1, PostManifest.hex(Ed25519.sign(pair.privateKey, ByteArray(0))))
        assertTrue(Ed25519.verify(pair.publicKey, ByteArray(0), PostManifest.unhex(sig1)!!))
    }

    @Test
    fun rfc8032Vector2OneByteMessage() {
        val pair = Ed25519.keyPairFromSeed(seed2)
        val message = byteArrayOf(0x72)
        assertEquals(pub2, PostManifest.hex(pair.publicKey))
        assertEquals(sig2, PostManifest.hex(Ed25519.sign(pair.privateKey, message)))
        assertTrue(Ed25519.verify(pair.publicKey, message, PostManifest.unhex(sig2)!!))
    }

    @Test
    fun rfc8032Vector3TwoByteMessage() {
        val pair = Ed25519.keyPairFromSeed(seed3)
        val message = PostManifest.unhex("af82")!!
        assertEquals(pub3, PostManifest.hex(pair.publicKey))
        assertEquals(sig3, PostManifest.hex(Ed25519.sign(pair.privateKey, message)))
    }

    @Test
    fun verifyRejectsWrongKeyAndTamperedMessage() {
        val pair = Ed25519.keyPairFromSeed(seed1)
        val other = Ed25519.keyPairFromSeed(seed2)
        val message = "пост".toByteArray()
        val sig = Ed25519.sign(pair.privateKey, message)
        assertTrue(Ed25519.verify(pair.publicKey, message, sig))
        assertFalse(Ed25519.verify(other.publicKey, message, sig))
        assertFalse(Ed25519.verify(pair.publicKey, "пост!".toByteArray(), sig))
        assertFalse(Ed25519.verify(pair.publicKey, message, sig.copyOf(63)))
        assertFalse(Ed25519.verify(ByteArray(31), message, sig))
    }

    private fun sample(): PostManifest = PostManifest.build(
        groupId = "grp-1",
        topicId = "topic-1",
        messageId = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0",
        authorId = "pk_" + "a".repeat(64),
        sentAtMs = 1_757_400_000_000L,
        text = "Первый пост | с трубой\nи второй строкой",
        parts = listOf(
            "part-1" to "APUIMGP1:1/1/2:AAAA",
            "part-2" to "APUIMGP1:1/2/2:BBBB",
        ),
    )

    @Test
    fun signedManifestVerifiesAndCarriesSignerKey() {
        val signed = sample().signed(seed1)
        assertTrue(signed.isSigned)
        assertEquals(pub1, PostManifest.hex(signed.signerPublicKey))
        assertTrue(signed.verifySignature())
        // Любое изменение поля ломает подпись.
        assertFalse(signed.copy(sentAtMs = signed.sentAtMs + 1).verifySignature())
        assertFalse(signed.copy(topicId = "topic-2").verifySignature())
        assertFalse(signed.copy(revision = 1).verifySignature())
        assertFalse(signed.copy(parts = signed.parts.take(1)).verifySignature())
        assertFalse(signed.copy(signerPublicKey = Ed25519.keyPairFromSeed(seed2).publicKey).verifySignature())
    }

    @Test
    fun unsignedManifestNeverVerifies() {
        assertFalse(sample().isSigned)
        assertFalse(sample().verifySignature())
    }

    @Test
    fun hashChecksAcceptOriginalTextAndRejectOthers() {
        val m = sample()
        assertTrue(m.matchesText("Первый пост | с трубой\nи второй строкой"))
        assertFalse(m.matchesText("Первый пост | с трубой\nи второй строкой."))
        assertTrue(m.matchesPart("part-2", "APUIMGP1:1/2/2:BBBB"))
        assertFalse(m.matchesPart("part-2", "APUIMGP1:1/2/2:BBBC"))
        assertFalse(m.matchesPart("part-9", "APUIMGP1:1/2/2:BBBB"))
    }

    @Test
    fun wireRoundTripKeepsEveryField() {
        val signed = sample().copy(revision = 3).signed(seed3)
        val envelope = GroupWire.buildPostManifest(signed)
        assertTrue(GroupWire.isGroupPacket(envelope))
        val packet = GroupWire.parse(envelope)
        assertTrue(packet is GroupWire.Packet.PostManifest)
        val parsed = (packet as GroupWire.Packet.PostManifest).manifest
        assertEquals(signed, parsed)
        assertEquals(3, parsed.revision)
        assertTrue(parsed.verifySignature())
        assertArrayEquals(signed.signature, parsed.signature)
    }

    /** Самый большой манифест - шесть фото по три куска - остаётся маленьким пакетом. */
    @Test
    fun largestManifestStaysSmall() {
        val parts = (1..18).map { java.util.UUID.randomUUID().toString() to "APUIMGP1:${(it - 1) / 3 + 1}/${(it - 1) % 3 + 1}/3:" + "Q".repeat(3200) }
        val signed = sample().copy(
            messageId = java.util.UUID.randomUUID().toString(),
            authorId = "pk_" + "f".repeat(64),
        ).let {
            PostManifest.build(it.groupId, it.topicId, it.messageId, it.authorId, it.sentAtMs, "текст", parts)
        }.signed(seed2)
        val envelope = GroupWire.buildPostManifest(signed)
        assertEquals(18, signed.parts.size)
        assertTrue("envelope is ${envelope.length} chars", envelope.length < 2_000)
        val parsed = (GroupWire.parse(envelope) as GroupWire.Packet.PostManifest).manifest
        assertEquals(signed, parsed)
        assertTrue(parsed.verifySignature())
        assertTrue(parsed.matchesPart(parts[17].first, parts[17].second))
    }

    @Test
    fun manifestWithoutPhotosRoundTrips() {
        val signed = sample().copy(parts = emptyList()).signed(seed1)
        val parsed = (GroupWire.parse(GroupWire.buildPostManifest(signed)) as GroupWire.Packet.PostManifest).manifest
        assertEquals(0, parsed.parts.size)
        assertTrue(parsed.verifySignature())
    }

    @Test
    fun malformedManifestEnvelopesAreDropped() {
        val good = GroupWire.buildPostManifest(sample().signed(seed1))
        val parts = good.split('|').toMutableList()
        // Короткая подпись.
        parts[11] = parts[11].dropLast(4)
        assertNull(GroupWire.parse(parts.joinToString("|")))
        // Битые части.
        val parts2 = good.split('|').toMutableList()
        parts2[9] = "part-1:zz,broken"
        assertNull(GroupWire.parse(parts2.joinToString("|")))
        // Лишнее поле.
        assertNull(GroupWire.parse("$good|extra"))
        // Отрицательная правка.
        val parts3 = good.split('|').toMutableList()
        parts3[7] = "-1"
        assertNull(GroupWire.parse(parts3.joinToString("|")))
    }

    @Test
    fun partsWireParsesAndLimits() {
        val many = (1..PostManifest.MAX_PARTS + 1).map { "p$it" to "text$it" }
        val m = PostManifest.build("g", "t", "m", "a", 1L, "x", many)
        assertEquals(PostManifest.MAX_PARTS, m.parts.size)
        assertEquals(m.parts, PostManifest.parseParts(m.partsWire()))
        assertEquals(emptyList<ManifestPart>(), PostManifest.parseParts(""))
        assertNull(PostManifest.parseParts("nocolon"))
        assertNull(PostManifest.parseParts(":AAAA"))
    }

    @Test
    fun postKeysRoundTrip() {
        val k1 = Ed25519.keyPairFromSeed(seed1).publicKey
        val k2 = Ed25519.keyPairFromSeed(seed2).publicKey
        val envelope = GroupWire.buildPostKeys("grp-1", listOf("pk_owner" to k1, "pk_admin" to k2))
        val packet = GroupWire.parse(envelope)
        assertTrue(packet is GroupWire.Packet.PostKeys)
        val keys = (packet as GroupWire.Packet.PostKeys).keys
        assertEquals(2, keys.size)
        assertEquals("pk_owner", keys[0].first)
        assertArrayEquals(k1, keys[0].second)
        assertArrayEquals(k2, keys[1].second)
        // Ключ не той длины отбрасывается сборкой, а не ломает конверт.
        val short = GroupWire.buildPostKeys("grp-1", listOf("pk_x" to ByteArray(31)))
        assertEquals(0, (GroupWire.parse(short) as GroupWire.Packet.PostKeys).keys.size)
        val req = GroupWire.parse(GroupWire.buildPostKeysRequest("grp-1"))
        assertTrue(req is GroupWire.Packet.PostKeysRequest)
        assertNotNull(req)
    }
}
