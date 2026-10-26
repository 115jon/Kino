package com.nuvio.app.features.player

internal class MpvPlaybackState {
    var currentSourceUrl = ""
    @Volatile
    var sourceGeneration = 0L
    @Volatile
    var pendingLoadRequestGeneration: Long? = null
    var startupStallSinceMs = 0L
    @Volatile
    var startupStalled = false
    @Volatile
    var hasLoadedMedia = false
    @Volatile
    var activePlaylistEntryId: Long? = null
    @Volatile
    var activePlaylistEntryGeneration: Long? = null
    @Volatile
    var pendingPlaylistEntryId: Long? = null
    @Volatile
    var lastObservedPlaylistEntryId: Long? = null
    @Volatile
    var pendingPlaylistEntryGeneration: Long? = null
    @Volatile
    var terminalPlaybackError: String? = null
    @Volatile
    var terminalPlaybackErrorGeneration: Long? = null
    @Volatile
    var terminalPlaybackEndGeneration: Long? = null
    @Volatile
    var initialSeekRequestedAtMs = 0L
    @Volatile
    var initialSeekTargetMs = 0L
    @Volatile
    var initialSeekRecoveryIssued = false
    var currentSourceAudioUrl: String? = null
    var currentHeaders = mapOf<String, String>()
    var volumeLevel = PlayerAudioLevel(fraction = 1f, isMuted = false)
    var streamProfileSummary: String? = null
    var streamIsHdrLike = false
    var streamHasDolbyVision = false
    var streamHasHdrFallback = false
    var lastVideoDiagnosticsSignature: String? = null
    var videoPipelineMode = DesktopVideoPipelineMode.Standard
    val audioDiagnosticsRateLimiter = WindowsAudioDiagnosticsRateLimiter()
    val audioUnderrunTracker = WindowsAudioUnderrunTracker()
    var audioDiagnosticsRequested = false
    var lastTrackMetadataSignature: String? = null
    var lastReportedPlaybackError: String? = null
    var subtitleStyle = SubtitleStyleState.DEFAULT
    @Volatile
    var lastTrackPollEpochMs = 0L
    var audioTracks = emptyList<AudioTrack>()
    var subtitleTracks = emptyList<SubtitleTrack>()

    fun beginLoadRequest(requestGeneration: Long) {
        sourceGeneration += 1L
        lastTrackMetadataSignature = null
        audioDiagnosticsRequested = false
        pendingLoadRequestGeneration = requestGeneration
        hasLoadedMedia = false
        startupStallSinceMs = 0L
        startupStalled = false
        lastReportedPlaybackError = null
        terminalPlaybackError = null
        terminalPlaybackErrorGeneration = null
        terminalPlaybackEndGeneration = null
        pendingPlaylistEntryId = null
        pendingPlaylistEntryGeneration = null
    }

    fun beginLoadCommit() {
        pendingLoadRequestGeneration = null
        lastReportedPlaybackError = null
        startupStallSinceMs = 0L
        startupStalled = false
        hasLoadedMedia = false
        activePlaylistEntryId = null
        activePlaylistEntryGeneration = null
        pendingPlaylistEntryId = null
        pendingPlaylistEntryGeneration = null
        terminalPlaybackError = null
        terminalPlaybackErrorGeneration = null
        terminalPlaybackEndGeneration = null
        initialSeekRequestedAtMs = 0L
        initialSeekTargetMs = 0L
        initialSeekRecoveryIssued = false
    }

    fun failLoad(message: String, generation: Long) {
        pendingLoadRequestGeneration = null
        terminalPlaybackError = message
        terminalPlaybackErrorGeneration = generation
    }

    fun markTerminalError(message: String, generation: Long) {
        terminalPlaybackError = message
        terminalPlaybackErrorGeneration = generation
    }

    fun isLoadTerminated(generation: Long): Boolean =
        terminalPlaybackErrorGeneration == generation || terminalPlaybackEndGeneration == generation
}
