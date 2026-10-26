package com.nuvio.app.features.player

import com.sun.jna.Pointer

internal fun handleWindowsMpvStartFileEvent(
    state: WindowsPlayerWindowState,
    data: Pointer?,
): String {
    val startFile = readMpvStartFile(data)
    if (startFile == null) {
        return "mpv event id=$MPV_EVENT_START_FILE data=unavailable"
    }
    val previousEntryId = state.lastObservedPlaylistEntryId
    if (state.pendingLoadRequestGeneration != null) {
        return "mpv event id=$MPV_EVENT_START_FILE playlistEntryId=${startFile.playlist_entry_id} stale=true pendingLoad=true"
    }
    if (!isWindowsPlaybackStartFileCurrent(
            playlistEntryId = startFile.playlist_entry_id,
            expectedPlaylistEntryId = state.pendingPlaylistEntryId,
            previousPlaylistEntryId = previousEntryId,
        )
    ) {
        return "mpv event id=$MPV_EVENT_START_FILE playlistEntryId=${startFile.playlist_entry_id} stale=true"
    }
    state.lastObservedPlaylistEntryId = startFile.playlist_entry_id
    state.pendingPlaylistEntryId = null
    state.pendingPlaylistEntryGeneration = null
    state.activePlaylistEntryId = startFile.playlist_entry_id
    state.activePlaylistEntryGeneration = state.sourceGeneration
    return "mpv event id=$MPV_EVENT_START_FILE playlistEntryId=${startFile.playlist_entry_id} generation=${state.sourceGeneration}"
}

internal fun discardPendingWindowsMpvEvents(lib: WindowsMpvLibrary, ptr: Pointer): Int {
    var discarded = 0
    while (true) {
        val eventPtr = lib.mpv_wait_event(ptr, 0.0) ?: break
        val event = MpvEvent(eventPtr).apply { read() }
        if (event.event_id == MPV_EVENT_NONE) break
        discarded += 1
    }
    return discarded
}

internal fun handleWindowsMpvEndFileEvent(
    state: WindowsPlayerWindowState,
    lib: WindowsMpvLibrary,
    data: Pointer?,
): String {
    val endFile = readMpvEndFile(data)
    if (endFile == null) {
        return "mpv event id=$MPV_EVENT_END_FILE data=unavailable"
    }
    val errorMessage = if (endFile.reason == WindowsMpvEndFileReasonError) {
        readMpvErrorString(lib, endFile.error)
    } else {
        null
    }
    val playbackError = selectWindowsPlaybackEndFileError(
        event = WindowsPlaybackEndFile(
            reason = endFile.reason,
            errorMessage = errorMessage,
            playlistEntryId = endFile.playlist_entry_id,
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
        playlistEntryId = endFile.playlist_entry_id,
        activePlaylistEntryId = state.activePlaylistEntryId,
        pendingPlaylistEntryId = state.pendingPlaylistEntryId,
        activePlaylistEntryGeneration = state.activePlaylistEntryGeneration,
        pendingPlaylistEntryGeneration = state.pendingPlaylistEntryGeneration,
        sourceGeneration = state.sourceGeneration,
    )
    if (isCurrent && endFile.reason == WindowsMpvEndFileReasonRedirect) {
        state.lastObservedPlaylistEntryId = endFile.playlist_entry_id
        state.pendingPlaylistEntryId = null
        state.pendingPlaylistEntryGeneration = null
    }
    if (isCurrent) {
        if (endFile.reason != WindowsMpvEndFileReasonRedirect) {
            state.terminalPlaybackEndGeneration = state.sourceGeneration
        }
    }
    return "mpv event id=$MPV_EVENT_END_FILE reason=${endFile.reason} error=${endFile.error} " +
        "playlistEntryId=${endFile.playlist_entry_id} activePlaylistEntryId=${state.activePlaylistEntryId} " +
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
