package com.nuvio.app.features.player

internal const val WindowsHdrCompatibilityFilter = "libplacebo=apply_filmgrain=1:peak_detect=1:tonemapping=auto"
internal const val WindowsDolbyVisionCompatibilityFilter = "libplacebo=apply_dolbyvision=1:apply_filmgrain=1:peak_detect=1:tonemapping=auto"

internal data class WindowsVideoPipelinePlan(
    val decision: DesktopVideoPipelineDecision,
    val filterSpec: String,
    val signature: String,
)

internal fun windowsVideoPipelineFilter(mode: DesktopVideoPipelineMode): String = when (mode) {
    DesktopVideoPipelineMode.Standard -> ""
    DesktopVideoPipelineMode.HdrCompatibility -> WindowsHdrCompatibilityFilter
    DesktopVideoPipelineMode.DolbyVisionCompatibility -> WindowsDolbyVisionCompatibilityFilter
}

internal fun planWindowsVideoPipeline(
    diagnostics: MpvVideoDiagnostics,
    state: MpvPlaybackState,
): WindowsVideoPipelinePlan? {
    if (diagnostics.pixelFormat.isNullOrBlank()) return null
    val signature = diagnostics.signature()
    if (signature == state.lastVideoDiagnosticsSignature) return null
    val decision = selectDesktopVideoPipeline(diagnostics.toMetadata(state))
    return WindowsVideoPipelinePlan(
        decision = decision,
        filterSpec = windowsVideoPipelineFilter(decision.mode),
        signature = signature,
    )
}

internal fun configureWindowsVideoPipeline(
    client: MpvClient,
    state: MpvPlaybackState,
    trigger: String,
): String? {
    val diagnostics = readMpvVideoDiagnostics(client)
    val plan = planWindowsVideoPipeline(diagnostics, state) ?: return null
    val result = client.setProperty("vf", plan.filterSpec)
    state.lastVideoDiagnosticsSignature = plan.signature
    state.videoPipelineMode = plan.decision.mode
    val logMessage =
        "video pipeline trigger=$trigger mode=${plan.decision.mode} reason=${plan.decision.reason} " +
            "filter=${plan.filterSpec.ifBlank { "none" }} result=$result ${diagnostics.logValue()}"
    desktopPlayerTrace(logMessage)
    return logMessage
}

internal fun resetWindowsVideoPipeline(state: MpvPlaybackState) {
    state.lastVideoDiagnosticsSignature = null
    state.videoPipelineMode = DesktopVideoPipelineMode.Standard
    desktopPlayerTrace("video pipeline reset")
}
