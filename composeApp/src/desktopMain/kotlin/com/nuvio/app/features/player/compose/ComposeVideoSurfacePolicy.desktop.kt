package com.nuvio.app.features.player.compose

import com.nuvio.app.features.player.WindowsPlayerWindowState

private val composeSurfaceNames = setOf("compose", "skia")

internal fun isComposeWindowsVideoSurface(surface: String?): Boolean =
    surface?.trim()?.lowercase() in composeSurfaceNames

internal fun isComposeWindowsVideoSurfaceSelected(): Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("win") &&
        isComposeWindowsVideoSurface(System.getProperty("kino.windows.video-surface"))

internal fun configureWindowsComposeVideoRenderApi() {
    if (!isComposeWindowsVideoSurfaceSelected()) return
    if (System.getProperty("skiko.renderApi") == null) System.setProperty("skiko.renderApi", "OPENGL")
}

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

    fun shouldReveal(state: WindowsPlayerWindowState): Boolean = shouldRevealComposeVideo(
        armedRestartGeneration = armedGeneration,
        loadPending = state.pendingLoadRequestGeneration != null,
        activePlaylistEntryGeneration = state.activePlaylistEntryGeneration,
        sourceGeneration = state.sourceGeneration,
    )
}
