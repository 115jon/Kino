package com.nuvio.app.features.player

import com.sun.jna.Pointer

private const val WindowsHdrCompatibilityFilter = "libplacebo=apply_filmgrain=1:peak_detect=1:tonemapping=auto"
private const val WindowsDolbyVisionCompatibilityFilter = "libplacebo=apply_dolbyvision=1:apply_filmgrain=1:peak_detect=1:tonemapping=auto"

internal fun configureWindowsVideoPipeline(
    state: WindowsPlayerWindowState,
    ptr: Pointer,
    trigger: String,
): String? {
    val lib = WindowsMpvLibrary.INSTANCE
    val diagnostics = readMpvVideoDiagnostics(lib, ptr)
    if (diagnostics.pixelFormat.isNullOrBlank()) return null
    val signature = diagnostics.signature()
    if (signature == state.lastVideoDiagnosticsSignature) return null

    val decision = selectDesktopVideoPipeline(diagnostics.toMetadata(state))
    val filterSpec = when (decision.mode) {
        DesktopVideoPipelineMode.Standard -> ""
        DesktopVideoPipelineMode.HdrCompatibility -> WindowsHdrCompatibilityFilter
        DesktopVideoPipelineMode.DolbyVisionCompatibility -> WindowsDolbyVisionCompatibilityFilter
    }
    val result = lib.mpv_set_property_string(ptr, "vf", filterSpec)
    state.lastVideoDiagnosticsSignature = signature
    state.videoPipelineMode = decision.mode
    val logMessage =
        "video pipeline trigger=$trigger mode=${decision.mode} reason=${decision.reason} " +
            "filter=${filterSpec.ifBlank { "none" }} result=$result ${diagnostics.logValue()}"
    desktopPlayerTrace(logMessage)
    return logMessage
}

internal fun resetWindowsVideoPipeline(state: WindowsPlayerWindowState) {
    state.lastVideoDiagnosticsSignature = null
    state.videoPipelineMode = DesktopVideoPipelineMode.Standard
    desktopPlayerTrace("video pipeline reset")
}
