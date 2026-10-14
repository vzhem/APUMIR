package com.vladimir.messenger.data.diagnostics

import org.junit.Assert.*
import org.junit.Test

class RelayStorageDiagnosticsTest {
    @Test fun physicalDatabaseIsNotReportedAsPendingPayload() {
        val table = RelayTableDiagnostic(RelayTableKind.ENCRYPTED, totalRows = 2, sampled = 2, bytes = 1234)
        val report = RelayStorageDiagnostics(listOf(RelayStoreDiagnostic(
            status = "ready", dbBytes = 1_073_741_824, walBytes = 4096, shmBytes = 32768,
            pageSize = 4096, pages = 262144, freePages = 260000, tables = listOf(table),
        )), status = "ready").render()
        assertTrue(report.contains("db_bytes=1073741824"))
        assertTrue(report.contains("reusable_page_bytes=1064960000"))
        assertTrue(report.contains("rows=2"))
        assertTrue(report.contains("sampled_bytes=1234"))
        assertTrue(report.contains("не объём ожидающих сообщений"))
    }

    @Test fun aliasesAreSharedWithinOneSnapshotButNoRawIdentitySurvives() {
        val acc = RelayStorageAccumulator("private-owner", 1000)
        val table = acc.table(RelayTableKind.LEGACY)
        table.add("private-id-1", "private-peer", "private-owner", 20, 100, 900)
        table.add("private-id-2", "private-owner", "private-peer", 10, 500, 1100)
        val result = table.finish(2, false)
        assertEquals("self", result.groups[0].sender)
        assertEquals(result.groups[0].recipient, result.groups[1].sender)
        assertEquals("self", result.groups[1].recipient)
        assertEquals(1, result.expired)
        assertEquals(900L, result.groups[0].oldestAgeMs)
        assertFalse(result.toString().contains("private-"))
    }

    @Test fun encryptedOriginAndAgeAreUnknownNotGuessedFromExpiry() {
        val acc = RelayStorageAccumulator("owner", 1000)
        val table = acc.table(RelayTableKind.ENCRYPTED)
        table.add("id", "owner", null, 42, null, 1100)
        val group = table.finish(1, false).groups.single()
        assertEquals("encrypted", group.sender)
        assertEquals("self", group.recipient)
        assertNull(group.oldestAgeMs)
    }

    @Test fun repeatedIdsAcrossTablesAreCandidatesNotPayloadEquality() {
        val acc = RelayStorageAccumulator("owner", 1000)
        val first = acc.table(RelayTableKind.LEGACY)
        first.add("same-private-id", "peer", "sender", 1, 10, 2000)
        val second = acc.table(RelayTableKind.QUARANTINE)
        second.add("same-private-id", "peer", null, 2, null, 2000)
        assertEquals(0, first.finish(1, false).repeatedIds)
        assertEquals(1, second.finish(1, false).repeatedIds)
    }

    @Test fun missingCountsAndLimitedSamplesAreNotPresentedAsAnEmptyQueue() {
        val table = RelayTableDiagnostic(RelayTableKind.ENCRYPTED, status = "timeout", limited = true, sampled = 1, bytes = 22)
        val report = RelayStorageDiagnostics(listOf(RelayStoreDiagnostic(status = "timeout", tables = listOf(table))), status = "partial").render()
        assertTrue(report.contains("rows=-1"))
        assertTrue(report.contains("limited=true"))
        assertTrue(report.contains("status=timeout"))
        assertTrue(report.contains("sampled=1"))
    }

    @Test fun arbitraryStringsCannotBeSmuggledIntoTheSafeReport() {
        val unsafe = "pk_deadbeef payload-text secret-filename https://private.invalid"
        val route = RelayRouteDiagnostic(unsafe, unsafe, 1, 1, 0, null)
        val table = RelayTableDiagnostic(RelayTableKind.LEGACY, status = unsafe, groups = listOf(route))
        val report = RelayStorageDiagnostics(listOf(RelayStoreDiagnostic(status = unsafe, tables = listOf(table))), status = unsafe).render()
        assertFalse(report.contains(unsafe))
        assertFalse(report.contains("pk_deadbeef"))
        assertTrue(report.contains("from=unknown -> to=unknown"))
    }

    @Test fun routeListIsBoundedAndOmissionsAreVisible() {
        val acc = RelayStorageAccumulator("owner", 1000)
        val table = acc.table(RelayTableKind.LEGACY)
        repeat(25) { table.add("id-$it", "recipient-$it", "owner", it.toLong(), 100, 2000) }
        val report = RelayStorageDiagnostics(listOf(RelayStoreDiagnostic(tables = listOf(table.finish(25, false))))).render()
        assertEquals(20, report.lineSequence().count { it.startsWith("  from=") })
        assertTrue(report.contains("groups_not_shown=5"))
        assertFalse(report.contains("recipient-"))
    }
}
