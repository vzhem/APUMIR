package com.vladimir.messenger.data.heartgame

import com.vladimir.messenger.data.swarm.Ed25519
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class HeartGameWireTest {
    private val seed = ByteArray(32) { it.toByte() }
    private val id = "pk_" + "ab".repeat(32)
    private val now = 1_792_200_000_000L
    // Host test supplies the binding validator; Rust verifies real identity bindings in production.
    private fun binding(): ByteArray = ByteBuffer.allocate(1 + 2 + id.length + 32 + 8 + 64)
        .put(1.toByte()).putShort(id.length.toShort()).put(id.toByteArray())
        .put(Ed25519.keyPairFromSeed(seed).publicKey).putLong(now - 1000).put(ByteArray(64)).array()
    private fun record(score: Long = 7, time: Long = now, name: String = "Игрок") =
        HeartGameWire.sign(binding(), seed, score, time, name)

    /** Запись от другого узла: переустановка приложения или другая личность. */
    private fun otherNode(entry: HeartGameWire.Entry, node: String): HeartGameWire.Entry =
        entry.copy(nodeId = node)
    private fun verify(text: String) = HeartGameWire.verify(text, now) { true }

    @Test fun signedResultCanTravelThroughAnyRelayUnchanged() {
        val wire = record()
        val parsed = verify(wire)!!
        assertEquals(id, parsed.nodeId)
        assertEquals("Игрок", parsed.name)
        assertEquals(7L, parsed.score)
        assertEquals(wire, parsed.record)
        assertNull(HeartGameWire.verify(wire, now) { false })
    }
    @Test fun modifyingScoreTimestampNameOrKeyBreaksSignature() {
        val parts = record().split('|')
        for ((field, replacement) in listOf(1 to "8", 2 to (now + 1).toString(), 3 to "QWxpY2U")) {
            val changed = parts.toMutableList(); changed[field] = replacement
            assertNull(verify(changed.joinToString("|")))
        }
        val otherSeed = ByteArray(32) { 42 }
        assertNull(verify(HeartGameWire.sign(binding(), otherSeed, 7, now, "Игрок")))
    }
    @Test fun claimedLegacyIdCannotReplaceAnotherSigningIdentity() {
        val old = verify(record())!!
        val forgedBinding = binding()
        val secondSeed = ByteArray(32) { 42 }
        Ed25519.keyPairFromSeed(secondSeed).publicKey.copyInto(forgedBinding, 3 + id.length)
        val other = verify(HeartGameWire.sign(forgedBinding, secondSeed, 99, now + 1, "Игрок"))!!
        assertEquals(old.nodeId, other.nodeId)
        assertNotEquals(old.keyId, other.keyId)
        assertFalse(HeartGameWire.newer(old, other))
    }
    @Test fun limitsAgeAndMalformedPacketsAreRejected() {
        assertNull(verify(record(time = now - HeartGameWire.RETENTION_MS - 1)))
        assertNull(verify(record(time = now + HeartGameWire.FUTURE_MS + 1)))
        for (bad in listOf("", "x|x|x|x|x", "a".repeat(1201), record() + "|extra")) assertNull(verify(bad))
        assertNotNull(verify(record(score = HeartGamePolicy.MAX_SCORE)))
    }
    @Test fun replayRollbackAndTieOrderingAreDeterministic() {
        val old = verify(record())!!
        assertFalse(HeartGameWire.newer(old, old))
        assertFalse(HeartGameWire.newer(old, verify(record(6, now + 1))!!))
        assertTrue(HeartGameWire.newer(old, verify(record(8, now + 1))!!))
        val other = old.copy(keyId = "00".repeat(32))
        assertEquals(listOf(other, old), HeartGameWire.ranked(listOf(old, other)))
    }

    @Test fun scoreRequestRoundTrip() {
        val packet = HeartGameWire.buildRequest("nodeA", now)
        assertEquals("nodeA", HeartGameWire.parseRequest(packet, now))
        assertEquals("nodeA", HeartGameWire.parseRequest(packet, now + 9 * 60 * 1000))
    }

    @Test fun scoreRequestRejectsStaleForgedAndMalformed() {
        assertNull(HeartGameWire.parseRequest(HeartGameWire.buildRequest("nodeA", now - 11 * 60 * 1000), now))
        assertNull(HeartGameWire.parseRequest(HeartGameWire.buildRequest("nodeA", now + 6 * 60 * 1000), now))
        assertNull(HeartGameWire.parseRequest("APUTYP1|APUGAMEREQ1||$now", now))
        assertNull(HeartGameWire.parseRequest("APUTYP1|APUGAMEREQ1|nodeA", now))
        assertNull(HeartGameWire.parseRequest("APUTYP1|APUGAMEREQ1|nodeA|abc", now))
        assertNull(HeartGameWire.parseRequest("APUTYP1|APUGAME1|nodeA|$now", now))
    }

    @Test fun placeCountsFromKnownRowsWithOwnEntry() {
        // Имена разные: в таблице это разные участники, а не склейка одного.
        val first = verify(record(score = 20, name = "Первый"))!!
        val second = otherNode(first, "pk_" + "cd".repeat(32))
            .copy(keyId = "11".repeat(32), score = 10, name = "Второй")
        val own = otherNode(first, "pk_" + "ef".repeat(32)).copy(keyId = "22".repeat(32), score = 15, name = "Я")
        // 20 > 15 > 10: своё место - второе.
        assertEquals(2, HeartGameWire.placeOf(listOf(first, second), own.score, own))
    }

    @Test fun placeStillCountsWithoutSigningBinding() {
        // Подписи личности нет (own == null) - место обязано быть числом, а не
        // прочерком: сравниваем свой счёт с известными результатами.
        val first = verify(record(score = 20, name = "Первый"))!!
        val second = otherNode(first, "pk_" + "cd".repeat(32))
            .copy(keyId = "11".repeat(32), score = 10, name = "Второй")
        assertEquals(2, HeartGameWire.placeOf(listOf(first, second), 15, null))
        assertEquals(1, HeartGameWire.placeOf(listOf(first, second), 20, null))
        assertEquals(3, HeartGameWire.placeOf(listOf(first, second), 0, null))
        assertEquals(1, HeartGameWire.placeOf(emptyList(), 0, null))
    }

    @Test fun ownEntryIsIncludedExactlyOnce() {
        val own = verify(record(score = 7))!!.copy(keyId = "33".repeat(32))
        val same = own.copy(score = 7, updated = own.updated + 1)
        // Своя строка уже лежит в таблице - она не должна считаться дважды.
        assertEquals(1, HeartGameWire.placeOf(listOf(own, same), own.score, own))
        assertEquals(1, HeartGameWire.table(listOf(own, same), own.keyId).size)
    }

    // ------------------------------------------------------------------
    // Склейка одного участника: владелец, скриншот 28.10.2026 - «В таблице
    // один и тот же участник задвоился» (server7 дважды подряд).
    // ------------------------------------------------------------------

    @Test fun oneNameWithTwoSigningKeysTakesASingleRowWithTheBestScore() {
        val fresh = verify(record(score = 3282, name = "server7"))!!
        val older = fresh.copy(keyId = "11".repeat(32), score = 3271, updated = now - 86_000)
        val table = HeartGameWire.table(listOf(older, fresh), ownKey = null)
        assertEquals(1, table.size)
        assertEquals(3282L, table[0].entry.score)
        assertEquals(2, table[0].merged)
    }

    @Test fun twoDevicesOfOneIdentityAreOneRowEvenWithoutAName() {
        // Телефон и компьютер одного человека: узел общий, ключи подписи разные,
        // имя в записи может быть пустым. Владелец, 29.10.2026: в таблице
        // появился «Участник» - это была его же старая строка без имени.
        val deviceA = verify(record(score = 3255, name = ""))!!
        val deviceB = deviceA.copy(keyId = "11".repeat(32), score = 1192, updated = now - 86_000)
        val table = HeartGameWire.table(listOf(deviceA, deviceB), ownKey = null)
        assertEquals(1, table.size)
        assertEquals(3255L, table[0].entry.score)
        assertEquals(2, table[0].merged)
    }

    @Test fun ownOldKeyIsFoldedIntoTheOwnRow() {
        // Своя строка берётся по ключу, а старая запись того же узла (прежний
        // ключ подписи) не должна висеть отдельной безымянной строкой.
        val own = verify(record(score = 3255, name = "Проводник"))!!.copy(keyId = "22".repeat(32))
        val oldKey = own.copy(keyId = "33".repeat(32), score = 1192, name = "")
        val table = HeartGameWire.table(listOf(own, oldKey), own.keyId)
        assertEquals(1, table.size)
        assertEquals(own.keyId, table[0].entry.keyId)
        assertEquals(3255L, table[0].entry.score)
        assertEquals(1, HeartGameWire.placeOf(listOf(own, oldKey), own.score, own))
    }

    @Test fun twoDifferentNodesWithDifferentNamesStayApart() {
        val a = verify(record(score = 20, name = "Первый"))!!
        val b = otherNode(a, "pk_" + "cd".repeat(32)).copy(keyId = "11".repeat(32), score = 10, name = "Второй")
        val table = HeartGameWire.table(listOf(a, b), ownKey = null)
        assertEquals(2, table.size)
        assertEquals(listOf("Первый", "Второй"), table.map { it.entry.name })
    }

    @Test fun ownRowStaysOwnEvenWhenAnotherKeyUnderTheSameNameClaimsMore() {
        val own = verify(record(score = 3246, name = "Проводник"))!!.copy(keyId = "22".repeat(32))
        // Другой человек (другой узел), который назвался тем же именем.
        val stranger = otherNode(own, "pk_" + "cd".repeat(32)).copy(keyId = "33".repeat(32), score = 9999)
        val table = HeartGameWire.table(listOf(own, stranger), own.keyId)
        assertEquals(1, table.size)
        // Своя строка не подменяется чужим счётом: в шапке и в таблице одно число.
        assertEquals(3246L, table[0].entry.score)
        assertEquals(own.keyId, table[0].entry.keyId)
        assertEquals(1, HeartGameWire.placeOf(listOf(own, stranger), own.score, own))
    }

    @Test fun differentNamesStayDifferentRows() {
        val first = verify(record(score = 20, name = "server7"))!!
        val second = otherNode(first, "pk_" + "cd".repeat(32))
            .copy(keyId = "11".repeat(32), score = 10, name = "server3")
        val table = HeartGameWire.table(listOf(first, second), ownKey = null)
        assertEquals(listOf("server7", "server3"), table.map { it.entry.name })
        assertEquals(listOf(1, 1), table.map { it.merged })
    }

    @Test fun namelessParticipantsOfDifferentNodesAreNeverMerged() {
        // Без имени стоят разные люди: сводить их в одного нельзя. Один узел -
        // один человек, поэтому ключ склейки здесь именно узел.
        val first = verify(record(score = 20, name = ""))!!
        val other = otherNode(first, "pk_" + "cd".repeat(32)).copy(keyId = "11".repeat(32), score = 10)
        assertEquals(2, HeartGameWire.table(listOf(first, other), ownKey = null).size)
    }

    @Test fun caseAndOuterSpacesDoNotSplitOneName() {
        // Разные узлы (переустановка), одно имя с разным регистром и пробелами.
        val first = verify(record(score = 20, name = "server7"))!!
        val second = otherNode(first, "pk_" + "cd".repeat(32))
            .copy(keyId = "11".repeat(32), score = 10, name = "  Server7 ")
        val table = HeartGameWire.table(listOf(first, second), ownKey = null)
        assertEquals(1, table.size)
        assertEquals(2, table[0].merged)
    }

    @Test fun mergedTableKeepsPlacesConsistentWithTheHeader() {
        val a = verify(record(score = 20, name = "Первый"))!!
        val aOld = a.copy(keyId = "11".repeat(32), score = 12)
        val b = otherNode(a, "pk_" + "cd".repeat(32)).copy(keyId = "22".repeat(32), score = 15, name = "Второй")
        val own = otherNode(a, "pk_" + "ef".repeat(32)).copy(keyId = "33".repeat(32), score = 7, name = "Я")
        // 20 (склеено из двух записей), 15, 7: своё место - третье.
        assertEquals(3, HeartGameWire.placeOf(listOf(a, aOld, b), own.score, own))
        assertEquals(3, HeartGameWire.table(listOf(a, aOld, b, own), own.keyId).size)
    }
}
