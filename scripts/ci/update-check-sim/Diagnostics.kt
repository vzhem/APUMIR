package com.vladimir.messenger.service
import android.content.Context
object UpdateDiagnosticsStore {
    class CheckTrace {
        var outcome = ""
        var manualWindow = ""
        var githubOutcome = "not_attempted"
        var relayOutcome = "not_attempted"
        var githubHttpStatus: Int? = null
        var relayHttpStatus: Int? = null
    }
    fun begin(context: Context, version: String, trigger: String) = CheckTrace()
    fun finish(context: Context, trace: CheckTrace) {}
    fun markHttp(trace: CheckTrace, source: String, code: Int) {
        if (source == "github") trace.githubHttpStatus = code else trace.relayHttpStatus = code
        markSource(trace, source, if (code == 200) "response_received" else "http_error")
    }
    fun markNetworkFailure(trace: CheckTrace, source: String) = markSource(trace, source, "network_error")
    fun markSource(trace: CheckTrace, source: String, result: String, version: String? = null) {
        if (source == "github") trace.githubOutcome = result else trace.relayOutcome = result
        trace.outcome = result
    }
}
