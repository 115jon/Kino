package com.nuvio.app.features.player

internal const val WindowsHdrCompatibilityFilter = "libplacebo=apply_filmgrain=1:peak_detect=1:tonemapping=auto"
internal const val WindowsFilterCopyHwdec = "nvdec-copy,d3d11va-copy,dxva2-copy"
internal const val WindowsDolbyVisionCompatibilityFilter = "libplacebo=apply_dolbyvision=1:apply_filmgrain=1:peak_detect=1:tonemapping=auto"
internal const val WindowsDolbyVisionSdrFilter =
    "$WindowsDolbyVisionCompatibilityFilter:colorspace=bt709:color_primaries=bt709:color_trc=bt709:range=tv:format=yuv420p"

internal enum class WindowsVideoOutputTarget {
    Display,
    SdrTexture,
}

internal data class WindowsVideoPipelinePlan(
    val decision: DesktopVideoPipelineDecision,
    val filterSpec: String,
    val signature: String,
)

internal fun windowsVideoPipelineFilter(
    mode: DesktopVideoPipelineMode,
    target: WindowsVideoOutputTarget,
): String = when (mode) {
    DesktopVideoPipelineMode.Standard -> ""
    DesktopVideoPipelineMode.HdrCompatibility ->
        if (target == WindowsVideoOutputTarget.SdrTexture) "" else WindowsHdrCompatibilityFilter
    DesktopVideoPipelineMode.DolbyVisionCompatibility ->
        if (target == WindowsVideoOutputTarget.SdrTexture) WindowsDolbyVisionSdrFilter else WindowsDolbyVisionCompatibilityFilter
}

internal fun planWindowsVideoPipeline(
    diagnostics: MpvVideoDiagnostics,
    state: MpvPlaybackState,
    target: WindowsVideoOutputTarget,
): WindowsVideoPipelinePlan? {
    if (diagnostics.pixelFormat.isNullOrBlank()) return null
    val signature = diagnostics.signature()
    if (signature == state.lastVideoDiagnosticsSignature) return null
    val decision = selectDesktopVideoPipeline(diagnostics.toMetadata(state))
    return WindowsVideoPipelinePlan(
        decision = decision,
        filterSpec = windowsVideoPipelineFilter(decision.mode, target),
        signature = signature,
    )
}

internal fun configureWindowsVideoPipeline(
    client: MpvClient,
    state: MpvPlaybackState,
    target: WindowsVideoOutputTarget,
    trigger: String,
): String? {
    val diagnostics = readMpvVideoDiagnostics(client)
    val plan = planWindowsVideoPipeline(diagnostics, state, target) ?: return null
    val forceCopy = shouldForceWindowsCopyHwdec(plan.filterSpec, diagnostics.hardwarePixelFormat, state.hwdecCopyForced)
    if (forceCopy) {
        state.hwdecCopyForced = true
        val hwdecResult = client.setProperty("hwdec", WindowsFilterCopyHwdec)
        desktopPlayerTrace("video pipeline hwdec=$WindowsFilterCopyHwdec reason=filter-needs-system-frames result=$hwdecResult")
    }
    val result = client.setProperty("vf", plan.filterSpec)
    state.lastVideoDiagnosticsSignature = plan.signature
    state.videoPipelineMode = plan.decision.mode
    val logMessage =
        "video pipeline trigger=$trigger mode=${plan.decision.mode} reason=${plan.decision.reason} " +
            "filter=${plan.filterSpec.ifBlank { "none" }} result=$result ${diagnostics.logValue()}"
    desktopPlayerTrace(logMessage)
    return logMessage
}

internal fun shouldForceWindowsCopyHwdec(
    filterSpec: String,
    hardwarePixelFormat: String?,
    alreadyForced: Boolean,
): Boolean = !alreadyForced && filterSpec.isNotBlank() && !hardwarePixelFormat.isNullOrBlank()

internal fun resetWindowsVideoPipeline(client: MpvClient, state: MpvPlaybackState) {
    if (state.hwdecCopyForced) {
        state.hwdecCopyForced = false
        state.configuredHwdec?.let { hwdec -> client.setProperty("hwdec", hwdec) }
    }
    state.lastVideoDiagnosticsSignature = null
    state.videoPipelineMode = DesktopVideoPipelineMode.Standard
    desktopPlayerTrace("video pipeline reset")
}
