package com.nuvio.app.features.player.compose

import com.nuvio.app.features.player.MpvOption
import com.nuvio.app.features.player.MpvPlaybackState
import com.nuvio.app.features.settings.VideoOutputSettingsPlatform

private val composeSurfaceNames = setOf("compose", "skia")

internal const val ComposeVideoDefaultHwdec = "nvdec,d3d11va-copy,dxva2-copy"

internal fun isComposeWindowsVideoSurface(surface: String?): Boolean =
    surface?.trim()?.lowercase() in composeSurfaceNames

internal fun isComposeWindowsVideoSurfaceSelected(): Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("win") &&
        isComposeWindowsVideoSurface(System.getProperty(WindowsVideoSurfaceProperty))

private const val WindowsVideoSurfaceProperty = "kino.windows.video-surface"

internal fun defaultWindowsVideoSurface(hdrPassthrough: Boolean): String = if (hdrPassthrough) "native" else "compose"

internal fun configureWindowsVideoSurfaceSelection() {
    if (!System.getProperty("os.name").orEmpty().lowercase().contains("win")) return
    if (System.getProperty(WindowsVideoSurfaceProperty) != null) return
    val hdrPassthrough = runCatching { VideoOutputSettingsPlatform.loadHdrPassthroughEnabled() }.getOrDefault(false)
    System.setProperty(WindowsVideoSurfaceProperty, defaultWindowsVideoSurface(hdrPassthrough))
}

internal fun configureWindowsComposeVideoRenderApi() {
    if (!isComposeWindowsVideoSurfaceSelected()) return
    if (System.getProperty("skiko.renderApi") == null) System.setProperty("skiko.renderApi", "OPENGL")
}

internal fun composeVideoHwdec(override: String?): String =
    override?.trim()?.takeIf { it.isNotEmpty() } ?: ComposeVideoDefaultHwdec

internal fun parseComposeVideoMpvOverrides(value: String?): List<MpvOption> =
    value.orEmpty()
        .split(',')
        .mapNotNull { entry ->
            val parts = entry.split('=', limit = 2)
            if (parts.size != 2 || parts[0].isBlank()) null else MpvOption(parts[0].trim(), parts[1].trim())
        }

internal fun composeVideoMpvOptions(): List<MpvOption> {
    val overrides = parseComposeVideoMpvOverrides(System.getProperty("kino.windows.compose-mpv-options"))
    val overrideNames = overrides.mapTo(HashSet()) { it.name }
    val defaults = listOf(
        MpvOption("hwdec", composeVideoHwdec(System.getProperty("kino.windows.compose-hwdec"))),
    )
    return defaults.filterNot { it.name in overrideNames } + overrides
}

internal fun composeVideoReportsSwap(): Boolean = System.getProperty("kino.windows.compose-report-swap") == "true"

internal fun isComposeMpvEventCurrent(
    loadPending: Boolean,
    activePlaylistEntryGeneration: Long?,
    sourceGeneration: Long,
): Boolean = !loadPending && activePlaylistEntryGeneration == sourceGeneration

internal fun shouldRevealComposeVideo(
    armedRestartGeneration: Long,
    loadPending: Boolean,
    activePlaylistEntryGeneration: Long?,
    sourceGeneration: Long,
): Boolean =
    armedRestartGeneration == sourceGeneration &&
        isComposeMpvEventCurrent(loadPending, activePlaylistEntryGeneration, sourceGeneration)

internal class ComposeVideoRevealTracker {
    @Volatile
    private var restartGeneration = -1L

    @Volatile
    private var armedGeneration = -1L

    fun onPlaybackRestart(generation: Long) {
        restartGeneration = generation
    }

    fun armForRender() {
        armedGeneration = restartGeneration
    }

    fun reset() {
        restartGeneration = -1L
        armedGeneration = -1L
    }

    fun shouldReveal(playback: MpvPlaybackState): Boolean = shouldRevealComposeVideo(
        armedRestartGeneration = armedGeneration,
        loadPending = playback.pendingLoadRequestGeneration != null,
        activePlaylistEntryGeneration = playback.activePlaylistEntryGeneration,
        sourceGeneration = playback.sourceGeneration,
    )
}
