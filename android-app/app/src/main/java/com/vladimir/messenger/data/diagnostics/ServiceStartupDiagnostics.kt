package com.vladimir.messenger.data.diagnostics

import android.os.SystemClock

/** Never accesses the engine or Keystore; safe to read while either is stalled. */
internal object ServiceStartupDiagnostics {
    val progress = ServiceStartupProgress { SystemClock.elapsedRealtime() }

    fun report(): String = ServiceStartupReport.render(progress.snapshot())
}
