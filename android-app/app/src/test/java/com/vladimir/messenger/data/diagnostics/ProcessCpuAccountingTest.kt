package com.vladimir.messenger.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessCpuAccountingTest {
    @Test fun separatesForegroundAndBackgroundWithoutCountingSnapshotsTwice() {
        var sample = ProcessCpuAccounting.Sample(10, 100)
        val tracker = ProcessCpuAccounting { sample }
        sample = ProcessCpuAccounting.Sample(20, 200)
        tracker.setForeground(true)
        sample = ProcessCpuAccounting.Sample(170, 300)
        tracker.setForeground(true)
        val visible = tracker.snapshot()
        assertEquals("150.0", visible.foreground.percentOfOneCore())
        assertEquals(visible, tracker.snapshot())
        tracker.setForeground(false)
        sample = ProcessCpuAccounting.Sample(180, 400)
        assertEquals(visible.foreground, tracker.snapshot().foreground)
        assertEquals(ProcessCpuAccounting.Usage(20, 200), tracker.snapshot().background)
    }

    @Test fun zeroWindowDoesNotProduceAPercentage() {
        assertEquals("нет данных", ProcessCpuAccounting.Usage().percentOfOneCore())
    }
}
