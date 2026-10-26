package com.nuvio.app.features.player

internal const val WindowsPlayerTrackPollIntervalMs = 1_000L
internal const val WindowsPlayerStartupTimeoutMs = 30_000L
internal const val WindowsPlayerInitialSeekTimeoutMs = 8_000L
internal const val WindowsPlayerFallbackPollIntervalMs = 500L
internal const val WindowsPlayerMaxCacheBytes = "536870912"
internal const val WindowsPlayerMaxBackCacheBytes = "268435456"
internal const val WindowsPlayerReadAheadSeconds = "30"
internal const val WindowsPlayerCachePauseWaitSeconds = "5"

internal enum class WindowsVideoSurface {
    Native,
    Embedded,
}

internal fun shouldUseNativeWindowsVideoSurface(surface: String?): Boolean =
    surface?.trim()?.lowercase() !in setOf("embedded", "gl", "opengl")

internal fun windowsPlayerInteropBackgroundArgb(): Long = 0xFF0C0C0CL

internal fun shouldUseTransparentWindowsPlayerOverlay(
    videoOutputConfigured: String?,
    videoWidth: Int,
    videoHeight: Int,
): Boolean = videoOutputConfigured?.trim()?.lowercase() == "yes" && videoWidth > 0 && videoHeight > 0

internal fun shouldPrimeWindowsPlayerOverlay(
    windowShowing: Boolean,
    overlayDisplayable: Boolean,
    renderApi: String?,
): Boolean {
    val normalizedRenderApi = renderApi?.trim()?.lowercase()
    return windowShowing && overlayDisplayable && !normalizedRenderApi.isNullOrEmpty() && normalizedRenderApi != "unknown"
}

internal fun shouldShowNativeWindowsVideoSurface(
    videoOutputConfigured: String?,
    videoWidth: Int,
    videoHeight: Int,
): Boolean = shouldUseTransparentWindowsPlayerOverlay(videoOutputConfigured, videoWidth, videoHeight)

internal fun windowsVideoSurface(): WindowsVideoSurface =
    if (shouldUseNativeWindowsVideoSurface(System.getProperty("kino.windows.video-surface"))) {
        WindowsVideoSurface.Native
    } else {
        WindowsVideoSurface.Embedded
    }
