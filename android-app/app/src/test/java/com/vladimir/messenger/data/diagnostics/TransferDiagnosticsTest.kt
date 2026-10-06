package com.vladimir.messenger.data.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferDiagnosticsTest {
    @Test
    fun copiedReportRedactsPinnedContactsAndNetworkAddresses() {
        val reportLine = TransferDiagnostics.redact(
            "F4 peer pk_${"ab".repeat(32)} at 192.168.10.25:7777 and 2001:db8:12::7",
        )

        assertTrue(reportLine.contains("[contact]"))
        assertTrue(reportLine.contains("[ip]"))
        assertTrue(reportLine.contains("[ipv6]"))
        assertFalse(reportLine.contains("192.168.10.25"))
        assertFalse(reportLine.contains("pk_${"ab".repeat(32)}"))
    }
}
