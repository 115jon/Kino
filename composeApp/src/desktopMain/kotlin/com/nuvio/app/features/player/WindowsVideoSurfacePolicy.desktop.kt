package com.nuvio.app.features.player

internal fun shouldRevealWindowsVideoSurface(
    loadPending: Boolean,
    activePlaylistEntryGeneration: Long?,
    sourceGeneration: Long,
    videoOutputConfigured: String?,
    videoWidth: Int,
    videoHeight: Int,
): Boolean =
    !loadPending &&
        activePlaylistEntryGeneration == sourceGeneration &&
        shouldShowNativeWindowsVideoSurface(videoOutputConfigured, videoWidth, videoHeight)
