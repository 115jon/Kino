package com.nuvio.app.features.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlin.concurrent.withLock
import kotlin.coroutines.CoroutineContext

internal class MpvLoadSequencer(
    private val engine: MpvEngine,
    private val tracePrefix: String,
    private val dispatcher: MpvEngineDispatcher,
    private val urlResolver: MpvUrlResolver,
    resolutionContext: CoroutineContext,
) {
    private val resolutionScope = CoroutineScope(SupervisorJob() + resolutionContext)
    private var resolutionJob: Job? = null

    fun load(
        url: String,
        audioUrl: String?,
        headers: Map<String, String>,
        onCommitted: ((Long) -> Unit)?,
        forceResolve: Boolean,
        startPositionMs: Long?,
    ) {
        val client = engine.activeClient ?: return
        if (engine.isClosed) return
        val requestGeneration = engine.loadRequestGate.allocate()
        engine.callLock.withLock {
            engine.playback.beginLoadRequest(requestGeneration)
        }
        resolutionJob?.cancel()
        resolutionJob = resolutionScope.launch {
            val resolved = runInterruptible {
                urlResolver.resolve(url, audioUrl, headers, forceResolve)
            }
            if (!isActive) return@launch
            dispatcher.post {
                commit(url, audioUrl, resolved, requestGeneration, client, onCommitted, startPositionMs)
            }
        }
    }

    fun cancel() {
        resolutionScope.cancel()
        resolutionJob = null
    }

    private fun canCommit(requestGeneration: Long, client: MpvClient): Boolean =
        !engine.isClosed &&
            engine.activeClient === client &&
            engine.loadRequestGate.isCurrent(requestGeneration)

    private fun commit(
        url: String,
        audioUrl: String?,
        resolved: WindowsResolvedPlaybackUrls,
        requestGeneration: Long,
        client: MpvClient,
        onCommitted: ((Long) -> Unit)?,
        startPositionMs: Long?,
    ) {
        if (!canCommit(requestGeneration, client)) return
        var committedGeneration: Long? = null
        engine.callLock.withLock {
            if (!canCommit(requestGeneration, client)) return@withLock
            val playback = engine.playback
            val loadGeneration = playback.sourceGeneration
            if (shouldAbortWindowsPlaybackLoad(resolved)) {
                playback.failLoad("Playback URL resolution failed", loadGeneration)
                desktopPlayerTrace("$tracePrefix playback URL resolution failed generation=$loadGeneration")
                return@withLock
            }
            playback.beginLoadCommit()
            val videoHeadersStr = encodeWindowsPlaybackHeaders(resolved.videoHeaders)
            val videoHeadersResult = runCatching {
                client.setProperty("http-header-fields", videoHeadersStr)
            }.getOrDefault(-1)
            if (videoHeadersResult < 0) {
                playback.failLoad("Failed to apply video headers", loadGeneration)
                engine.notifyStateChanged()
                desktopPlayerTrace("$tracePrefix video header setup failed result=$videoHeadersResult")
                return@withLock
            }
            resetWindowsVideoPipeline(playback)
            desktopPlayerTrace(
                "$tracePrefix loadFile host=${playbackUrlForLog(url)} " +
                    "effectiveHost=${playbackUrlForLog(resolved.url)} " +
                    "audioHost=${playbackUrlForLog(audioUrl)} " +
                    "effectiveAudioHost=${playbackUrlForLog(resolved.audioUrl)} " +
                    "videoHeaderKeys=${resolved.videoHeaders.keys.joinToString()} " +
                    "audioHeaderKeys=${resolved.audioHeaders.keys.joinToString()} " +
                    "videoHeaderBlobLength=${videoHeadersStr.length} startMs=${startPositionMs ?: 0L}"
            )
            applyWindowsMpvSubtitleStyle(client, playback.subtitleStyle)
            val discardedEvents = discardPendingWindowsMpvEvents(client, engine.properties)
            if (discardedEvents > 0) {
                desktopPlayerTrace("$tracePrefix discarded pending mpv events count=$discardedEvents")
            }
            val loadResult = runCatching {
                client.command(*mpvLoadFileArgs(resolved.url, startPositionMs))
            }.getOrDefault(-1)
            if (loadResult >= 0) {
                committedGeneration = loadGeneration
            }
            playback.pendingPlaylistEntryId = loadResult.takeIf { it >= 0 }
                ?.let { client.readNonBlankProperty("playlist/0/id")?.toLongOrNull() }
            playback.pendingPlaylistEntryGeneration = playback.pendingPlaylistEntryId?.let { loadGeneration }
            selectWindowsPlaybackCommandError(loadResult)?.let { playbackError ->
                playback.markTerminalError(playbackError, loadGeneration)
                engine.notifyStateChanged()
                desktopPlayerTrace("$tracePrefix loadFile failed commandResult=$loadResult")
            }
            val resolvedAudioUrl = resolved.audioUrl
            if (shouldScheduleWindowsAudioAdd(loadResult, true, false) && !resolvedAudioUrl.isNullOrBlank()) {
                dispatcher.postDelayed(500) {
                    attachAudio(client, requestGeneration, loadGeneration, loadResult, resolved, resolvedAudioUrl)
                }
            }
            engine.reapplySubtitleStyleLater(client)
            engine.onLoadCommitted()
        }
        committedGeneration?.let { onCommitted?.invoke(it) }
    }

    private fun attachAudio(
        client: MpvClient,
        requestGeneration: Long,
        loadGeneration: Long,
        loadResult: Int,
        resolved: WindowsResolvedPlaybackUrls,
        audioUrl: String,
    ) {
        val playback = engine.playback
        fun isCurrentLoad(): Boolean = canCommit(requestGeneration, client) && playback.sourceGeneration == loadGeneration
        val audioResult = withWindowsAudioAddIfCurrent(
            lock = engine.callLock,
            isCurrent = {
                shouldScheduleWindowsAudioAdd(
                    loadResult = loadResult,
                    isCurrent = isCurrentLoad(),
                    hasTerminalPlaybackError = playback.isLoadTerminated(loadGeneration),
                )
            },
            videoHeaders = resolved.videoHeaders,
            audioHeaders = resolved.audioHeaders,
            setHeaders = { headerBlob -> client.setProperty("http-header-fields", headerBlob) },
            audioAdd = { client.command("audio-add", audioUrl, "select") },
        )
        if (
            audioResult == WindowsAudioAttachmentResult.Success ||
            audioResult == WindowsAudioAttachmentResult.NotCurrent
        ) {
            return
        }
        engine.callLock.withLock {
            if (!isCurrentLoad() || playback.isLoadTerminated(loadGeneration)) return@withLock
            playback.terminalPlaybackError = if (audioResult == WindowsAudioAttachmentResult.HeaderRestoreFailed) {
                val stopResult = runCatching { client.command("stop") }.getOrDefault(-1)
                if (stopResult < 0) {
                    engine.loadRequestGate.invalidate()
                    playback.pendingLoadRequestGeneration = null
                    engine.onFatalError()
                }
                "Failed to restore video headers"
            } else {
                "Failed to add audio track"
            }
            playback.terminalPlaybackErrorGeneration = loadGeneration
            engine.notifyStateChanged()
            desktopPlayerTrace("$tracePrefix audio-add failed generation=$loadGeneration")
        }
    }
}

internal fun mpvLoadFileArgs(url: String, startPositionMs: Long?): Array<String> {
    val startMs = startPositionMs?.takeIf { it > 0L } ?: return arrayOf("loadfile", url, "replace")
    return arrayOf("loadfile", url, "replace", "-1", "start=" + String.format(java.util.Locale.ROOT, "%.3f", startMs / 1000.0))
}
