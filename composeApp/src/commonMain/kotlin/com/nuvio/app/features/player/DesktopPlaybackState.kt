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
): Boolean =
    !path.isNullOrBlank() &&
        durationMs <= 0L &&
        idle &&
        !paused &&
        startedAtMs > 0L &&
        nowMs - startedAtMs >= timeoutMs
