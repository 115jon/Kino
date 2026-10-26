package com.nuvio.app.features.player.compose

import com.nuvio.app.features.player.MpvEvent
import com.nuvio.app.features.player.WindowsMpvLibrary
import com.nuvio.app.features.player.WindowsPlayerCachePauseWaitSeconds
import com.nuvio.app.features.player.WindowsPlayerMaxBackCacheBytes
import com.nuvio.app.features.player.WindowsPlayerMaxCacheBytes
import com.nuvio.app.features.player.WindowsPlayerReadAheadSeconds
import com.nuvio.app.features.player.WindowsPlayerWindowState
import com.nuvio.app.features.player.applyWindowsMpvSubtitleStyle
import com.nuvio.app.features.player.configureWindowsVideoPipeline
import com.nuvio.app.features.player.handleWindowsMpvEndFileEvent
import com.nuvio.app.features.player.handleWindowsMpvStartFileEvent
import com.nuvio.app.features.player.markWindowsAudioDiagnosticsRequested
import com.nuvio.app.features.player.recordWindowsAudioUnderrun
import com.nuvio.app.features.player.traceMpvLogMessage
import com.sun.jna.Pointer

private const val MPV_EVENT_NONE = 0
private const val MPV_EVENT_SHUTDOWN = 1
private const val MPV_EVENT_LOG_MESSAGE = 2
private const val MPV_EVENT_START_FILE = 6
private const val MPV_EVENT_END_FILE = 7
private const val MPV_EVENT_FILE_LOADED = 8
private const val MPV_EVENT_VIDEO_RECONFIG = 17
private const val MPV_EVENT_PLAYBACK_RESTART = 21
private const val MPV_EVENT_PROPERTY_CHANGE = 22
private const val MPV_FORMAT_FLAG = 3
private const val MPV_FORMAT_INT64 = 4
private const val MPV_FORMAT_DOUBLE = 5

internal fun composeMpvHwdec(): String =
    System.getProperty("kino.windows.compose-hwdec")?.trim()?.takeIf { it.isNotEmpty() } ?: "auto-copy-safe"

internal fun applyComposeMpvOptions(lib: WindowsMpvLibrary, ptr: Pointer) {
    val options = listOf(
        "vo" to "libmpv",
        "hwdec" to composeMpvHwdec(),
        "input-media-keys" to "no",
        "media-controls" to "no",
        "subs-match-os-language" to "yes",
        "subs-fallback" to "yes",
        "sub-ass-override" to "force",
        "hdr-compute-peak" to "yes",
        "tone-mapping" to "auto",
        "keep-open" to "yes",
        "cache" to "yes",
        "cache-pause" to "yes",
        "cache-pause-wait" to WindowsPlayerCachePauseWaitSeconds,
        "demuxer-max-bytes" to WindowsPlayerMaxCacheBytes,
        "demuxer-max-back-bytes" to WindowsPlayerMaxBackCacheBytes,
        "demuxer-readahead-secs" to WindowsPlayerReadAheadSeconds,
    )
    val overrides = System.getProperty("kino.windows.compose-mpv-options").orEmpty()
        .split(',')
        .mapNotNull { entry -> entry.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
    (options + overrides).forEach { (name, value) ->
        val result = lib.mpv_set_option_string(ptr, name, value)
        if (result < 0) composeVideoTrace("compose mpv option rejected name=$name value=$value result=$result")
    }
}

internal fun observeComposeMpvProperties(lib: WindowsMpvLibrary, ptr: Pointer) {
    lib.mpv_request_log_messages(ptr, "info")
    listOf("pause", "paused-for-cache", "core-idle", "eof-reached", "seeking").forEach { name ->
        lib.mpv_observe_property(ptr, 0, name, MPV_FORMAT_FLAG)
    }
    listOf("track-list/count", "aid").forEach { name -> lib.mpv_observe_property(ptr, 0, name, MPV_FORMAT_INT64) }
    listOf("volume", "duration", "speed").forEach { name -> lib.mpv_observe_property(ptr, 0, name, MPV_FORMAT_DOUBLE) }
}

internal data class ComposeMpvDrainResult(val count: Int)

internal fun drainComposeMpvEvents(
    lib: WindowsMpvLibrary,
    ptr: Pointer,
    state: WindowsPlayerWindowState,
    reveal: ComposeVideoRevealTracker,
): ComposeMpvDrainResult {
    var count = 0
    while (!state.isClosed) {
        val eventPtr = lib.mpv_wait_event(ptr, 0.0) ?: break
        val event = MpvEvent(eventPtr).apply { read() }
        when (event.event_id) {
            MPV_EVENT_NONE -> break
            MPV_EVENT_SHUTDOWN -> break
            MPV_EVENT_LOG_MESSAGE -> {
                recordWindowsAudioUnderrun(state, event.data)
                traceMpvLogMessage(event.data)
            }
            MPV_EVENT_PROPERTY_CHANGE -> {
                markWindowsAudioDiagnosticsRequested(state, event.data)
                count += 1
            }
            MPV_EVENT_START_FILE -> {
                composeVideoTrace(handleWindowsMpvStartFileEvent(state, event.data))
                count += 1
            }
            MPV_EVENT_END_FILE -> {
                composeVideoTrace(handleWindowsMpvEndFileEvent(state, lib, event.data))
                count += 1
            }
            MPV_EVENT_FILE_LOADED -> {
                applyWindowsMpvSubtitleStyle(ptr, state.subtitleStyle)
                configureWindowsVideoPipeline(state, ptr, "file-loaded")
                count += 1
            }
            MPV_EVENT_VIDEO_RECONFIG, MPV_EVENT_PLAYBACK_RESTART -> {
                val current = isComposeMpvEventCurrent(
                    loadPending = state.pendingLoadRequestGeneration != null,
                    activePlaylistEntryGeneration = state.activePlaylistEntryGeneration,
                    sourceGeneration = state.sourceGeneration,
                )
                if (current && event.event_id == MPV_EVENT_PLAYBACK_RESTART) {
                    reveal.onPlaybackRestart(state.sourceGeneration)
                }
                composeVideoTrace("mpv event id=${event.event_id} generation=${state.sourceGeneration} current=$current")
                count += 1
            }
            else -> count += 1
        }
    }
    return ComposeMpvDrainResult(count)
}
