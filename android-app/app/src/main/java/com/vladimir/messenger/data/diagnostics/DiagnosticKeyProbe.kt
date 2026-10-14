package com.vladimir.messenger.data.diagnostics

import android.content.Context
import com.vladimir.messenger.data.file.FileExchangeKeyStore
import com.vladimir.messenger.data.security.IdentitySigningKeyStore
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A stalled KeyMint must not stall Logs or spawn a new blocked thread per refresh. */
internal object DiagnosticKeyProbe {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "apu-diagnostic-key-mode").apply { isDaemon = true }
    }
    private var inFlight: Future<Pair<String, String>>? = null

    @Synchronized private fun request(context: Context): Future<Pair<String, String>> {
        val existing = inFlight
        if (existing != null && !existing.isDone) return existing
        val app = context.applicationContext
        return executor.submit<Pair<String, String>> {
            val ticket = RuntimeDiagnostics.operations.begin(DiagnosticOperation.KEY_MODE_PROBE)
            var outcome = DiagnosticOutcome.FAILED
            try {
                val signing = runCatching { IdentitySigningKeyStore.mode(app).name.lowercase() }.getOrDefault("unavailable")
                val exchange = runCatching { FileExchangeKeyStore.mode(app).name.lowercase() }.getOrDefault("unavailable")
                if (signing != "unavailable" && exchange != "unavailable") outcome = DiagnosticOutcome.OK
                signing to exchange
            } finally { RuntimeDiagnostics.operations.finish(ticket, outcome) }
        }.also { inFlight = it }
    }

    fun read(context: Context, budgetMs: Long): Pair<String, String> = try {
        request(context).get(budgetMs.coerceIn(1L, 1500L), TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        // Do not cancel and replace the Future: an uninterruptible provider could leak threads.
        "timeout" to "timeout"
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        "unavailable" to "unavailable"
    } catch (_: Exception) {
        "unavailable" to "unavailable"
    }
}
