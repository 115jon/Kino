package com.nuvio.app.features.player.compose

import com.nuvio.app.features.player.WindowsAudioAttachmentResult
import com.nuvio.app.features.player.WindowsMpvLibrary
import com.nuvio.app.features.player.WindowsPlayerWindowState
import com.nuvio.app.features.player.WindowsResolvedPlaybackUrls
import com.nuvio.app.features.player.applyWindowsMpvSubtitleStyle
import com.nuvio.app.features.player.discardPendingWindowsMpvEvents
import com.nuvio.app.features.player.encodeWindowsPlaybackHeaders
import com.nuvio.app.features.player.readMpvPropertyString
import com.nuvio.app.features.player.reapplyWindowsMpvSubtitleStyleLater
import com.nuvio.app.features.player.redactDesktopPlaybackUrlForLog
import com.nuvio.app.features.player.resetWindowsVideoPipeline
import com.nuvio.app.features.player.resolveWindowsPlaybackUrls
import com.nuvio.app.features.player.scheduleWindowsSwingAction
import com.nuvio.app.features.player.selectWindowsPlaybackCommandError
import com.nuvio.app.features.player.shouldAbortWindowsPlaybackLoad
import com.nuvio.app.features.player.shouldScheduleWindowsAudioAdd
import com.nuvio.app.features.player.withWindowsAudioAddIfCurrent
import com.sun.jna.Pointer
import kotlin.concurrent.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

internal class WindowsComposeMpvLoader(
    private val state: WindowsPlayerWindowState,
    private val isOwner: () -> Boolean,
    private val notifyStateChanged: () -> Unit,
    private val onUnrecoverable: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun load(
        url: String,
        audioUrl: String?,
        headers: Map<String, String>,
        onCommitted: ((Long) -> Unit)?,
        forceResolve: Boolean,
    ) {
        val ptr = state.playerPtr ?: return
        if (!isOwner()) return
        val requestGeneration = state.loadRequestGate.allocate()
        state.mpvCallLock.withLock {
            state.sourceGeneration += 1L
            state.lastTrackMetadataSignature = null
            state.audioDiagnosticsRequested = false
            state.pendingLoadRequestGeneration = requestGeneration
            state.hasLoadedMedia = false
            state.startupStallSinceMs = 0L
            state.startupStalled = false
            state.lastReportedPlaybackError = null
            state.terminalPlaybackError = null
            state.terminalPlaybackErrorGeneration = null
            state.terminalPlaybackEndGeneration = null
            state.pendingPlaylistEntryId = null
            state.pendingPlaylistEntryGeneration = null
        }
        job?.cancel()
        job = scope.launch {
            val resolved = runInterruptible { resolveWindowsPlaybackUrls(url, audioUrl, headers, forceResolve) }
            if (!isActive) return@launch
            val committed = commit(requestGeneration, ptr, url, audioUrl, resolved)
            committed?.let { onCommitted?.invoke(it) }
        }
    }

    private fun canCommit(requestGeneration: Long, ptr: Pointer): Boolean =
        isOwner() && state.playerPtr == ptr && state.loadRequestGate.isCurrent(requestGeneration)

    private fun commit(
        requestGeneration: Long,
        ptr: Pointer,
        url: String,
        audioUrl: String?,
        resolved: WindowsResolvedPlaybackUrls,
    ): Long? = state.mpvCallLock.withLock {
        if (!canCommit(requestGeneration, ptr)) return@withLock null
        val lib = WindowsMpvLibrary.INSTANCE
        val loadGeneration = state.sourceGeneration
        state.pendingLoadRequestGeneration = null
        if (shouldAbortWindowsPlaybackLoad(resolved)) {
            state.terminalPlaybackError = "Playback URL resolution failed"
            state.terminalPlaybackErrorGeneration = loadGeneration
            composeVideoTrace("compose panel playback URL resolution failed generation=$loadGeneration")
            return@withLock null
        }
        state.lastReportedPlaybackError = null
        state.startupStallSinceMs = 0L
        state.startupStalled = false
        state.hasLoadedMedia = false
        state.activePlaylistEntryId = null
        state.activePlaylistEntryGeneration = null
        state.pendingPlaylistEntryId = null
        state.pendingPlaylistEntryGeneration = null
        state.terminalPlaybackError = null
        state.terminalPlaybackErrorGeneration = null
        state.terminalPlaybackEndGeneration = null
        state.initialSeekRequestedAtMs = 0L
        state.initialSeekTargetMs = 0L
        state.initialSeekRecoveryIssued = false
        val videoHeaders = encodeWindowsPlaybackHeaders(resolved.videoHeaders)
        val headerResult = runCatching { lib.mpv_set_property_string(ptr, "http-header-fields", videoHeaders) }
            .getOrDefault(-1)
        if (headerResult < 0) {
            state.terminalPlaybackError = "Failed to apply video headers"
            state.terminalPlaybackErrorGeneration = loadGeneration
            notifyStateChanged()
            composeVideoTrace("compose panel video header setup failed result=$headerResult")
            return@withLock null
        }
        resetWindowsVideoPipeline(state)
        composeVideoTrace(
            "compose panel loadFile host=${redactDesktopPlaybackUrlForLog(url)} " +
                "effectiveHost=${redactDesktopPlaybackUrlForLog(resolved.url)} " +
                "audioHost=${redactDesktopPlaybackUrlForLog(audioUrl)} " +
                "videoHeaderKeys=${resolved.videoHeaders.keys.joinToString()} " +
                "audioHeaderKeys=${resolved.audioHeaders.keys.joinToString()} generation=$loadGeneration",
        )
        applyWindowsMpvSubtitleStyle(ptr, state.subtitleStyle)
        discardPendingWindowsMpvEvents(lib, ptr)
        val loadResult = runCatching { lib.mpv_command(ptr, arrayOf("loadfile", resolved.url, "replace", null)) }
            .getOrDefault(-1)
        state.pendingPlaylistEntryId = loadResult.takeIf { it >= 0 }
            ?.let { readMpvPropertyString(lib, ptr, "playlist/0/id")?.toLongOrNull() }
        state.pendingPlaylistEntryGeneration = state.pendingPlaylistEntryId?.let { loadGeneration }
        selectWindowsPlaybackCommandError(loadResult)?.let { playbackError ->
            state.terminalPlaybackError = playbackError
            state.terminalPlaybackErrorGeneration = loadGeneration
            notifyStateChanged()
            composeVideoTrace("compose panel loadFile failed commandResult=$loadResult")
        }
        if (shouldScheduleWindowsAudioAdd(loadResult, true, false) && !resolved.audioUrl.isNullOrBlank()) {
            scheduleAudioAdd(requestGeneration, ptr, loadGeneration, loadResult, resolved)
        }
        reapplyWindowsMpvSubtitleStyleLater(state, ptr)
        loadGeneration.takeIf { loadResult >= 0 }
    }

    private fun scheduleAudioAdd(
        requestGeneration: Long,
        ptr: Pointer,
        loadGeneration: Long,
        loadResult: Int,
        resolved: WindowsResolvedPlaybackUrls,
    ) {
        val lib = WindowsMpvLibrary.INSTANCE
        fun isTerminal() = state.terminalPlaybackErrorGeneration == loadGeneration ||
            state.terminalPlaybackEndGeneration == loadGeneration
        fun isCurrent() = canCommit(requestGeneration, ptr) && state.sourceGeneration == loadGeneration
        scheduleWindowsSwingAction(500) {
            val audioResult = withWindowsAudioAddIfCurrent(
                lock = state.mpvCallLock,
                isCurrent = { shouldScheduleWindowsAudioAdd(loadResult, isCurrent(), isTerminal()) },
                videoHeaders = resolved.videoHeaders,
                audioHeaders = resolved.audioHeaders,
                setHeaders = { blob -> lib.mpv_set_property_string(ptr, "http-header-fields", blob) },
                audioAdd = { lib.mpv_command(ptr, arrayOf("audio-add", resolved.audioUrl, "select", null)) },
            )
            if (
                audioResult == WindowsAudioAttachmentResult.Success ||
                audioResult == WindowsAudioAttachmentResult.NotCurrent ||
                !isCurrent() ||
                isTerminal()
            ) {
                return@scheduleWindowsSwingAction
            }
            state.terminalPlaybackError = if (audioResult == WindowsAudioAttachmentResult.HeaderRestoreFailed) {
                val stopResult = runCatching { lib.mpv_command(ptr, arrayOf("stop", null)) }.getOrDefault(-1)
                if (stopResult < 0) {
                    state.loadRequestGate.invalidate()
                    state.pendingLoadRequestGeneration = null
                    onUnrecoverable()
                }
                "Failed to restore video headers"
            } else {
                "Failed to add audio track"
            }
            state.terminalPlaybackErrorGeneration = loadGeneration
            notifyStateChanged()
            composeVideoTrace("compose panel audio-add failed generation=$loadGeneration result=$audioResult")
        }
    }

    fun close() {
        scope.cancel()
        job = null
    }
}
