package com.vladimir.messenger.data.diagnostics

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import java.util.Locale

internal object ThermalCpuDiagnostics {
    private val cpu = ProcessCpuAccounting {
        ProcessCpuAccounting.Sample(Process.getElapsedCpuTime(), SystemClock.uptimeMillis())
    }

    fun setForeground(visible: Boolean) = cpu.setForeground(visible)

    fun report(context: Context): String {
        val usage = cpu.snapshot()
        val temperature = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE }
                ?.let { String.format(Locale.ROOT, "%.1f", it / 10.0) }
        }.getOrNull()
        val thermalStatus = if (Build.VERSION.SDK_INT >= 29) runCatching {
            when (context.getSystemService(PowerManager::class.java)?.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "none"
                PowerManager.THERMAL_STATUS_LIGHT -> "light"
                PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
                else -> null
            }
        }.getOrNull() else null
        return buildString {
            appendLine("[температура и CPU]")
            appendLine("battery_temperature_c=${temperature ?: "нет данных"} · android_thermal_status=${thermalStatus ?: "нет данных"}")
            appendLine("Температура батареи и тепловой статус Android на момент отчёта; это не температура процессора.")
            appendLine(cpuLine("foreground", usage.foreground))
            appendLine(cpuLine("background", usage.background))
            appendLine("CPU текущего процесса APU, включая Rust, в среднем за время без глубокого сна; 100% = одно ядро, выше 100% возможно. Не общая нагрузка телефона.")
        }
    }

    private fun cpuLine(label: String, usage: ProcessCpuAccounting.Usage) =
        "${label}_cpu_ms=${usage.cpuMs} · ${label}_awake_ms=${usage.awakeMs} · ${label}_cpu_percent_one_core=${usage.percentOfOneCore()}"
}
