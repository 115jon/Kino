package com.nuvio.app.features.player

internal fun isWindowsPlaybackLoaded(path: String?, durationMs: Long): Boolean =
    !path.isNullOrBlank() && durationMs > 0L

internal fun isWindowsPlaybackLoading(
    path: String?,
    idle: Boolean,
    paused: Boolean,
    eofReached: Boolean,
    seeking: Boolean,
    bufferingCache: Boolean,
): Boolean =
    path.isNullOrBlank() || (idle && !paused && !eofReached) || seeking || bufferingCache

internal fun isWindowsPlaybackPlaying(
    path: String?,
    paused: Boolean,
    idle: Boolean,
    eofReached: Boolean,
    durationMs: Long,
): Boolean =
    isWindowsPlaybackLoaded(path, durationMs) && !paused && !idle && !eofReached

internal fun isWindowsPlaybackEnded(path: String?, durationMs: Long, eofReached: Boolean): Boolean =
    isWindowsPlaybackLoaded(path, durationMs) && eofReached

internal fun isWindowsPlaybackStartupStalled(
    path: String?,
    durationMs: Long,
    idle: Boolean,
    paused: Boolean,
    startedAtMs: Long,
    nowMs: Long,
    timeoutMs: Long,
    loadPending: Boolean = false,
): Boolean =
    (loadPending || !path.isNullOrBlank()) &&
        durationMs <= 0L &&
        idle &&
        !paused &&
        startedAtMs > 0L &&
        nowMs - startedAtMs >= timeoutMs

internal fun isWindowsPlaybackStartupStallCandidate(
    path: String?,
    durationMs: Long,
    idle: Boolean,
    paused: Boolean,
    loadPending: Boolean = false,
): Boolean =
    (loadPending || !path.isNullOrBlank()) && durationMs <= 0L && idle && !paused

internal fun isWindowsPlaybackStartFileCurrent(
    playlistEntryId: Long,
    expectedPlaylistEntryId: Long?,
    previousPlaylistEntryId: Long?,
): Boolean = when {
    expectedPlaylistEntryId != null -> playlistEntryId == expectedPlaylistEntryId
    previousPlaylistEntryId != null -> playlistEntryId > previousPlaylistEntryId
    else -> true
}

internal data class WindowsPlaybackStartupState(
    val stallSinceMs: Long = 0L,
    val isStalled: Boolean = false,
)

internal fun reduceWindowsPlaybackStartupState(
    state: WindowsPlaybackStartupState,
    path: String?,
    durationMs: Long,
    idle: Boolean,
    paused: Boolean,
    nowMs: Long,
    timeoutMs: Long,
    hasLoadedMedia: Boolean,
    loadPending: Boolean = false,
): WindowsPlaybackStartupState {
    if (hasLoadedMedia) return WindowsPlaybackStartupState()
    if (state.isStalled) return state

    val candidate = isWindowsPlaybackStartupStallCandidate(
        path = path,
        durationMs = durationMs,
        idle = idle,
        paused = paused,
        loadPending = loadPending,
    )
    val stallSinceMs = if (candidate) {
        state.stallSinceMs.takeIf { it > 0L } ?: nowMs
    } else {
        0L
    }
    return WindowsPlaybackStartupState(
        stallSinceMs = stallSinceMs,
        isStalled = isWindowsPlaybackStartupStalled(
            path = path,
            durationMs = durationMs,
            idle = idle,
            paused = paused,
            startedAtMs = stallSinceMs,
            nowMs = nowMs,
            timeoutMs = timeoutMs,
            loadPending = loadPending,
        ),
    )
}

internal const val WindowsMpvEndFileReasonEof = 0
internal const val WindowsMpvEndFileReasonStop = 2
internal const val WindowsMpvEndFileReasonError = 4
internal const val WindowsMpvEndFileReasonRedirect = 5

internal data class WindowsPlaybackEndFile(
    val reason: Int,
    val errorMessage: String?,
    val playlistEntryId: Long,
)

internal fun selectWindowsPlaybackEndFileError(
    event: WindowsPlaybackEndFile,
    activePlaylistEntryId: Long?,
    activePlaylistEntryGeneration: Long?,
    currentSourceGeneration: Long,
    hasLoadedMedia: Boolean,
): String? {
    if (event.playlistEntryId != activePlaylistEntryId) return null
    if (activePlaylistEntryGeneration != currentSourceGeneration) return null

    return when (event.reason) {
        WindowsMpvEndFileReasonError -> event.errorMessage ?: "Failed to open stream"
        WindowsMpvEndFileReasonEof -> if (hasLoadedMedia) null else "Failed to open stream"
        else -> null
    }
}

internal fun selectWindowsPlaybackCommandError(commandResult: Int): String? =
    commandResult.takeIf { it < 0 }?.let { "Failed to open stream" }

internal fun selectWindowsPlaybackError(
    mpvErrorMessage: String?,
    hasLoadedMedia: Boolean,
    startupStalled: Boolean,
    terminalPlaybackError: String? = null,
): String? {
    terminalPlaybackError?.let { return it }
    if (startupStalled && mpvErrorMessage.isNullOrBlank()) return null
    return mpvErrorMessage?.takeUnless { hasLoadedMedia }
}
