package com.nuvio.app.features.player

import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamItem
import com.sun.jna.Pointer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// Window state storage class to share context across threads safely
internal class WindowsPlayerWindowState {
    val mpvCallLock = ReentrantLock()
    val loadRequestGate = WindowsPlaybackLoadRequestGate()
    @Volatile
    var playerPtr: Pointer? = null
    @Volatile
    var isClosed = false
    @Volatile
    var panelRef: WindowsPlaybackPanel? = null

    // Meta details
    var metaTitle = ""
    var metaStreamTitle = ""
    var metaProviderName = ""
    var metaSeason: Int? = null
    var metaEpisode: Int? = null
    var metaEpisodeTitle: String? = null
    var hasVideoId = false
    var isSeries = false
    var submitIntroEnabled = false

    // Playback preferences
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

    // Tracks
    var audioTracks = emptyList<AudioTrack>()
    var subtitleTracks = emptyList<SubtitleTrack>()
    var addonSubtitles = emptyList<AddonSubtitle>()
    var addonSubtitlesLoading = false

    // Skip controls
    var skipIntroType: String? = null
    var skipIntroEndTimeMs: Long? = null
    var nextEpisodeSeason: Int? = null
    var nextEpisodeEpisode: Int? = null
    var nextEpisodeTitle: String? = null

    // Source Selection Data
    var sourceStreams = emptyList<StreamItem>()
    var sourceGroups = emptyList<AddonStreamGroup>()
    var sourcesLoading = false
    var sourceFilter: String? = null
    var currentSourceStreamUrl: String? = null

    // Episode Selection Data
    var episodes = emptyList<MetaVideo>()
    var episodeStreams = emptyList<StreamItem>()
    var episodeGroups = emptyList<AddonStreamGroup>()
    var episodeLoading = false
    var episodeFilter: String? = null
    var currentEpisodeStreamUrl: String? = null
}

internal fun WindowsPlayerWindowState.withMpv(block: (Pointer) -> Unit) {
    mpvCallLock.withLock {
        val ptr = playerPtr ?: return@withLock
        if (isClosed) return@withLock
        block(ptr)
    }
}

internal interface WindowsPlaybackPanel {
    fun loadFile(
        url: String,
        audioUrl: String?,
        headers: Map<String, String>,
        onCommitted: ((Long) -> Unit)? = null,
        forceResolve: Boolean = false,
    )
    fun loadSubtitleUrl(url: String)
    fun applyResizeMode(resizeMode: PlayerResizeMode)
    fun toggleFullScreen() {}
    fun requestInteractionFocus() {}
    fun retryPlayback()
    fun clearExternalSubtitles()
    fun clearExternalSubtitlesAndSelect(trackIndex: Int)
    fun dispose()
}
