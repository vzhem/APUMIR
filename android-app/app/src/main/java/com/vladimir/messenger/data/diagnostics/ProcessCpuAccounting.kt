package com.vladimir.messenger.data.diagnostics

import java.util.Locale

/** Accumulates CPU and awake time from lifecycle boundaries, not from a timer. */
internal class ProcessCpuAccounting(private val sample: () -> Sample) {
    data class Sample(val cpuMs: Long, val awakeMs: Long)
    data class Usage(val cpuMs: Long = 0, val awakeMs: Long = 0) {
        operator fun plus(other: Usage) = Usage(cpuMs + other.cpuMs, awakeMs + other.awakeMs)
        fun percentOfOneCore(): String = if (awakeMs > 0) {
            String.format(Locale.ROOT, "%.1f", cpuMs * 100.0 / awakeMs)
        } else "нет данных"
    }
    data class Snapshot(val foreground: Usage, val background: Usage)

    private var start = sample()
    private var visible = false
    private var foreground = Usage()
    private var background = Usage()

    @Synchronized
    fun setForeground(value: Boolean) {
        if (visible == value) return
        val end = sample()
        val delta = delta(end)
        if (visible) foreground += delta else background += delta
        start = end
        visible = value
    }

    @Synchronized
    fun snapshot(): Snapshot {
        val delta = delta(sample())
        return if (visible) Snapshot(foreground + delta, background)
        else Snapshot(foreground, background + delta)
    }

    private fun delta(end: Sample) = Usage(
        (end.cpuMs - start.cpuMs).coerceAtLeast(0),
        (end.awakeMs - start.awakeMs).coerceAtLeast(0),
    )
}
