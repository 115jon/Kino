package com.nuvio.app.features.player

import kotlin.concurrent.withLock

internal data class WindowsPlaybackPollResult(
    val generation: Long,
    val loadRequestGeneration: Long?,
    val snapshot: PlayerPlaybackSnapshot,
    val audioTracks: List<AudioTrack>,
    val subtitleTracks: List<SubtitleTrack>,
    val volumeLevel: PlayerAudioLevel,
    val videoOutputReady: Boolean,
    val polledTracks: Boolean,
    val errorMessage: String?,
    val terminalPlaybackError: String?,
    val startupStalled: Boolean,
    val logMessage: String?,
)

internal data class MpvPollOutcome(
    val result: WindowsPlaybackPollResult,
    val isPlaying: Boolean,
    val isLoading: Boolean,
    val isIdle: Boolean,
    val durationNs: Long,
)

internal fun toPlayerAudioLevel(volumePercent: Double): PlayerAudioLevel {
    val fraction = (volumePercent / 100.0).coerceIn(0.0, 1.0).toFloat()
    return PlayerAudioLevel(
        fraction = fraction,
        isMuted = fraction <= 0f,
    )
}

internal fun MpvEngine.pollPlayback(
    pollGeneration: Long,
    pollLoadRequestGeneration: Long?,
): MpvPollOutcome? = callLock.withLock {
    val pollStartNs = System.nanoTime()
    val client = activeClient ?: return@withLock null
    val state = playback
    val cache = properties

    fun getString(name: String): String? = client.getProperty(name)

    val posSec = cache.double("time-pos") ?: 0.0
    val durSec = cache.double("duration") ?: 0.0
    val cacheSec = cache.double("demuxer-cache-time") ?: 0.0
    val speed = cache.double("speed") ?: 1.0
    val paused = cache.flag("pause")
    val idle = cache.flag("core-idle")
    val eofReached = cache.flag("eof-reached")
    val seeking = cache.flag("seeking")
    val bufferingCache = cache.flag("paused-for-cache")
    val volumePercent = cache.double("volume") ?: (state.volumeLevel.fraction * 100f).toDouble()
    val path = cache.string("path")
    val mediaTitle = cache.string("media-title")
    val fileFormat = cache.string("file-format")
    val error = getString("error")
    val videoOutputConfigured = cache.flagString("vo-configured")
    val videoWidth = cache.long("video-params/w")?.toInt() ?: 0
    val videoHeight = cache.long("video-params/h")?.toInt() ?: 0
    val videoOutputReady = shouldRevealWindowsVideoSurface(
        loadPending = pollLoadRequestGeneration != null,
        activePlaylistEntryGeneration = state.activePlaylistEntryGeneration,
        sourceGeneration = pollGeneration,
        videoOutputConfigured = videoOutputConfigured,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
    )

    val durationMs = (durSec * 1000).toLong()
    val positionMs = (posSec.coerceAtLeast(0.0) * 1000).toLong()
    val bufferedMs = ((posSec + cacheSec).coerceAtLeast(0.0) * 1000).toLong()

    val isPlayerLoading = isWindowsPlaybackLoading(
        path = path,
        idle = idle,
        paused = paused,
        eofReached = eofReached,
        seeking = seeking,
        bufferingCache = bufferingCache,
    )
    val videoOutputDiagnostics = if (path != null && (positionMs == 0L || isPlayerLoading)) {
        listOf(
            "vo=${videoOutputConfigured ?: ""}",
            "hwdec=${getString("hwdec-current") ?: ""}",
            "video=${videoWidth}x${videoHeight}",
            "pixelformat=${getString("video-params/pixelformat") ?: ""}",
        ).joinToString(" ")
    } else {
        null
    }
    val isPlayerPlaying = isWindowsPlaybackPlaying(
        path = path,
        paused = paused,
        idle = idle,
        eofReached = eofReached,
        durationMs = durationMs,
    )
    val isPlayerEnded = isWindowsPlaybackEnded(
        path = path,
        durationMs = durationMs,
        eofReached = eofReached,
    )
    val videoPipelineLog = if (isPlayerLoading || positionMs == 0L) {
        configureWindowsVideoPipeline(client, state, "poll")
    } else {
        null
    }

    if (pollGeneration != state.sourceGeneration) return@withLock null
    val nowEpochMs = System.currentTimeMillis()
    val initialSeekElapsedMs = nowEpochMs - state.initialSeekRequestedAtMs
    val initialSeekSettled =
        !seeking &&
            (state.initialSeekTargetMs == 0L ||
                kotlin.math.abs(positionMs - state.initialSeekTargetMs) <= 5000L)
    if (initialSeekSettled) {
        state.initialSeekRequestedAtMs = 0L
        state.initialSeekTargetMs = 0L
    } else if (
        state.initialSeekRequestedAtMs > 0L &&
            initialSeekElapsedMs >= WindowsPlayerInitialSeekTimeoutMs &&
            !state.initialSeekRecoveryIssued
    ) {
        val result = client.command("seek", "0", "absolute", "keyframes")
        state.initialSeekRecoveryIssued = true
        state.initialSeekRequestedAtMs = 0L
        state.initialSeekTargetMs = 0L
        desktopPlayerTrace("initial seek recovery target=0 result=$result elapsedMs=$initialSeekElapsedMs")
    }
    val pendingLoad = pollLoadRequestGeneration != null
    val hasLoadedMedia = !pendingLoad && PlayerPlaybackSnapshot(
        isLoading = isPlayerLoading,
        isPlaying = isPlayerPlaying,
        durationMs = durationMs,
        positionMs = positionMs,
    ).hasLoadedMedia()
    val startupState = reduceWindowsPlaybackStartupState(
        state = WindowsPlaybackStartupState(
            stallSinceMs = state.startupStallSinceMs,
            isStalled = state.startupStalled,
        ),
        path = path,
        durationMs = durationMs,
        idle = idle,
        paused = paused,
        nowMs = nowEpochMs,
        timeoutMs = WindowsPlayerStartupTimeoutMs,
        hasLoadedMedia = hasLoadedMedia,
        loadPending = pendingLoad,
    )
    state.startupStallSinceMs = startupState.stallSinceMs
    state.startupStalled = startupState.isStalled
    state.hasLoadedMedia = state.hasLoadedMedia || hasLoadedMedia
    val startupStalled = startupState.isStalled
    val shouldPollTracks =
        nowEpochMs - state.lastTrackPollEpochMs >= WindowsPlayerTrackPollIntervalMs
    val audioDiagnostics = if (
        (!pendingLoad && path != null && (shouldPollTracks || isPlayerLoading || positionMs == 0L)) ||
        (!pendingLoad && state.audioDiagnosticsRequested) ||
        !error.isNullOrBlank() ||
        state.terminalPlaybackError != null
    ) {
        state.audioDiagnosticsRequested = false
        runCatching { readMpvAudioDiagnostics(client, state) }.getOrNull()
    } else {
        null
    }

    val tracks = if (shouldPollTracks) {
        pollMpvTracks(client, state)
    } else {
        MpvTrackPoll(state.audioTracks, state.subtitleTracks, null)
    }

    val snapshot = PlayerPlaybackSnapshot(
        isLoading = pendingLoad || isPlayerLoading,
        isStartupStalled = startupStalled,
        mediaLoaded = !pendingLoad && state.hasLoadedMedia,
        isPlaying = !pendingLoad && isPlayerPlaying,
        isEnded = !pendingLoad && isPlayerEnded,
        positionMs = positionMs,
        durationMs = durationMs,
        bufferedPositionMs = bufferedMs,
        playbackSpeed = speed.toFloat(),
    )
    val audioDiagnosticsLog = audioDiagnostics?.let { diagnostics ->
        val trigger = when {
            !error.isNullOrBlank() || state.terminalPlaybackError != null -> "failure"
            isPlayerLoading || positionMs == 0L -> "load"
            else -> "track-change"
        }
        if (state.audioDiagnosticsRateLimiter.shouldEmit(pollGeneration, diagnostics)) {
            formatWindowsAudioDiagnostics(trigger, diagnostics)
        } else {
            null
        }
    }

    MpvPollOutcome(
        result = WindowsPlaybackPollResult(
            generation = pollGeneration,
            loadRequestGeneration = pollLoadRequestGeneration,
            snapshot = snapshot,
            audioTracks = tracks.audioTracks,
            subtitleTracks = tracks.subtitleTracks,
            volumeLevel = toPlayerAudioLevel(volumePercent),
            videoOutputReady = videoOutputReady,
            polledTracks = shouldPollTracks,
            errorMessage = if (pendingLoad) null else error?.takeIf { it.isNotBlank() },
            terminalPlaybackError = state.terminalPlaybackError
                ?.takeIf { state.terminalPlaybackErrorGeneration == pollGeneration },
            startupStalled = startupStalled,
            logMessage = listOfNotNull(
                if (positionMs == 0L || isPlayerLoading || !error.isNullOrBlank()) {
                    "mpv snapshot path=${playbackUrlForLog(path)} " +
                        "mediaTitle=${playbackMetadataForLog(mediaTitle)} " +
                        "format=${playbackMetadataForLog(fileFormat)} " +
                        "error=${playbackMetadataForLog(error)} " +
                        "durationMs=$durationMs positionMs=$positionMs bufferedMs=$bufferedMs " +
                        "paused=$paused idle=$idle eof=$eofReached seeking=$seeking buffering=$bufferingCache"
                } else {
                    null
                },
                videoOutputDiagnostics,
                if (startupStalled) {
                    "mpv startup stalled path=${playbackUrlForLog(path)} durationMs=$durationMs paused=$paused idle=$idle eof=$eofReached"
                } else {
                    null
                },
                tracks.metadataLog,
                audioDiagnosticsLog,
                videoPipelineLog,
            ).joinToString(" | ")
                .take(MaxWindowsMpvLogMessageLength)
                .takeIf { it.isNotBlank() },
        ),
        isPlaying = isPlayerPlaying,
        isLoading = isPlayerLoading,
        isIdle = idle,
        durationNs = System.nanoTime() - pollStartNs,
    )
}
