package com.nuvio.app.features.settings

import com.nuvio.app.core.diagnostics.LogbookResult
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.send_logs_error_network
import nuvio.composeapp.generated.resources.send_logs_error_rate_limited
import nuvio.composeapp.generated.resources.send_logs_error_rejected
import nuvio.composeapp.generated.resources.send_logs_error_server
import nuvio.composeapp.generated.resources.send_logs_error_too_large
import org.jetbrains.compose.resources.StringResource

internal sealed interface SendLogsPhase {
    data object Editing : SendLogsPhase
    data object Sending : SendLogsPhase
    data class Sent(val reportId: String) : SendLogsPhase
    data class Failed(val message: StringResource) : SendLogsPhase
}

internal fun LogbookResult.toSendLogsPhase(): SendLogsPhase = when (this) {
    is LogbookResult.Sent -> SendLogsPhase.Sent(id)
    is LogbookResult.RateLimited -> SendLogsPhase.Failed(Res.string.send_logs_error_rate_limited)
    LogbookResult.TooLarge -> SendLogsPhase.Failed(Res.string.send_logs_error_too_large)
    LogbookResult.Unauthorized, LogbookResult.NotConfigured -> SendLogsPhase.Failed(Res.string.send_logs_error_rejected)
    is LogbookResult.ServerError -> SendLogsPhase.Failed(Res.string.send_logs_error_server)
    is LogbookResult.NetworkError -> SendLogsPhase.Failed(Res.string.send_logs_error_network)
}
