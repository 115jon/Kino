package com.nuvio.app.features.player

internal fun handleWindowsMpvStartFileEvent(
    state: MpvPlaybackState,
    playlistEntryId: Long?,
): String {
    if (playlistEntryId == null) {
        return "mpv event id=$MPV_EVENT_START_FILE data=unavailable"
    }
    val previousEntryId = state.lastObservedPlaylistEntryId
    if (state.pendingLoadRequestGeneration != null) {
        return "mpv event id=$MPV_EVENT_START_FILE playlistEntryId=${playlistEntryId} stale=true pendingLoad=true"
    }
    if (!isWindowsPlaybackStartFileCurrent(
            playlistEntryId = playlistEntryId,
            expectedPlaylistEntryId = state.pendingPlaylistEntryId,
            previousPlaylistEntryId = previousEntryId,
        )
    ) {
        return "mpv event id=$MPV_EVENT_START_FILE playlistEntryId=${playlistEntryId} stale=true"
    }
    state.lastObservedPlaylistEntryId = playlistEntryId
    state.pendingPlaylistEntryId = null
    state.pendingPlaylistEntryGeneration = null
    state.activePlaylistEntryId = playlistEntryId
    state.activePlaylistEntryGeneration = state.sourceGeneration
    return "mpv event id=$MPV_EVENT_START_FILE playlistEntryId=${playlistEntryId} generation=${state.sourceGeneration}"
}

internal fun discardPendingWindowsMpvEvents(client: MpvClient, properties: MpvPropertyCache): Int {
    var discarded = 0
    while (true) {
        val event = client.waitEvent(0.0) ?: break
        if (event is MpvClientEvent.PropertyChange) {
            properties.update(event.name, event.value)
        }
        discarded += 1
    }
    return discarded
}

internal fun handleWindowsMpvEndFileEvent(
    state: MpvPlaybackState,
    endFile: MpvEndFileData?,
    errorString: (Int) -> String?,
): String {
    if (endFile == null) {
        return "mpv event id=$MPV_EVENT_END_FILE data=unavailable"
    }
    val errorMessage = if (endFile.reason == WindowsMpvEndFileReasonError) {
        errorString(endFile.error)
    } else {
        null
    }
    val playbackError = selectWindowsPlaybackEndFileError(
        event = WindowsPlaybackEndFile(
            reason = endFile.reason,
            errorMessage = errorMessage,
            playlistEntryId = endFile.playlistEntryId,
        ),
        activePlaylistEntryId = state.activePlaylistEntryId,
        pendingPlaylistEntryId = state.pendingPlaylistEntryId,
        activePlaylistEntryGeneration = state.activePlaylistEntryGeneration,
        pendingPlaylistEntryGeneration = state.pendingPlaylistEntryGeneration,
        currentSourceGeneration = state.sourceGeneration,
        hasLoadedMedia = state.hasLoadedMedia,
    )
    if (playbackError != null) {
        state.terminalPlaybackError = playbackError
        state.terminalPlaybackErrorGeneration = state.sourceGeneration
    }
    val isCurrent = isCurrentWindowsMpvEndFile(
        playlistEntryId = endFile.playlistEntryId,
        activePlaylistEntryId = state.activePlaylistEntryId,
        pendingPlaylistEntryId = state.pendingPlaylistEntryId,
        activePlaylistEntryGeneration = state.activePlaylistEntryGeneration,
        pendingPlaylistEntryGeneration = state.pendingPlaylistEntryGeneration,
        sourceGeneration = state.sourceGeneration,
    )
    if (isCurrent && endFile.reason == WindowsMpvEndFileReasonRedirect) {
        state.lastObservedPlaylistEntryId = endFile.playlistEntryId
        state.pendingPlaylistEntryId = null
        state.pendingPlaylistEntryGeneration = null
    }
    if (isCurrent) {
        if (endFile.reason != WindowsMpvEndFileReasonRedirect) {
            state.terminalPlaybackEndGeneration = state.sourceGeneration
        }
    }
    return "mpv event id=$MPV_EVENT_END_FILE reason=${endFile.reason} error=${endFile.error} " +
        "playlistEntryId=${endFile.playlistEntryId} activePlaylistEntryId=${state.activePlaylistEntryId} " +
        "generation=${state.sourceGeneration} terminal=${playbackError != null}"
}

internal fun shouldScheduleWindowsAudioAdd(
    loadResult: Int,
    isCurrent: Boolean,
    hasTerminalPlaybackError: Boolean,
): Boolean = loadResult >= 0 && isCurrent && !hasTerminalPlaybackError

internal fun isCurrentWindowsMpvEndFile(
    playlistEntryId: Long,
    activePlaylistEntryId: Long?,
    pendingPlaylistEntryId: Long?,
    activePlaylistEntryGeneration: Long?,
    pendingPlaylistEntryGeneration: Long?,
    sourceGeneration: Long,
): Boolean =
    (activePlaylistEntryId == playlistEntryId && activePlaylistEntryGeneration == sourceGeneration) ||
        (pendingPlaylistEntryId == playlistEntryId && pendingPlaylistEntryGeneration == sourceGeneration)
