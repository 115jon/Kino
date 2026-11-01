package com.nuvio.app.features.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt

internal fun interface MpvUrlResolver {
    fun resolve(
        url: String,
        audioUrl: String?,
        headers: Map<String, String>,
        forceRefresh: Boolean,
    ): WindowsResolvedPlaybackUrls
}

internal val defaultMpvUrlResolver = MpvUrlResolver { url, audioUrl, headers, forceRefresh ->
    resolveWindowsPlaybackUrls(url, audioUrl, headers, forceRefresh)
}

internal class MpvEngine(
    private val tracePrefix: String,
    private val clientFactory: () -> MpvClient,
    private val dispatcher: MpvEngineDispatcher = MpvCommandThread(),
    val videoOutputTarget: WindowsVideoOutputTarget = WindowsVideoOutputTarget.Display,
    urlResolver: MpvUrlResolver = defaultMpvUrlResolver,
    resolutionContext: CoroutineContext = Dispatchers.IO,
) {
    val callLock = ReentrantLock()
    val loadRequestGate = WindowsPlaybackLoadRequestGate()
    val playback = MpvPlaybackState()
    val properties = MpvPropertyCache()
    val seekJournal = MpvSeekJournal()

    private val settled = MutableStateFlow(false)

    @Volatile
    var isClosed = false
        set(value) {
            field = value
            if (value) settled.value = true
        }

    @Volatile
    var perfCollector: DesktopBackendPerfCollector? = null

    @Volatile
    var onStateChanged: () -> Unit = {}

    @Volatile
    var onLoadCommitted: () -> Unit = {}

    @Volatile
    var onFatalError: () -> Unit = {}

    @Volatile
    var onPlaybackRestart: (Long) -> Unit = {}

    @Volatile
    private var client: MpvClient? = null
    private var output: MpvVideoOutput? = null
    private var eventPump: MpvEventPump? = null
    private val pendingVolumePercent = AtomicReference<Int?>(null)
    private val volumeUpdateScheduled = AtomicBoolean(false)
    private val loader = MpvLoadSequencer(this, tracePrefix, dispatcher, urlResolver, resolutionContext)

    val isReady: Boolean get() = client != null

    val activeClient: MpvClient? get() = client

    fun start(output: MpvVideoOutput) {
        val created = clientFactory()
        try {
            val options = mpvEngineOptions(output)
            options.forEach { option ->
                val result = created.setOption(option.name, option.value)
                desktopPlayerTrace("mpv option name=${option.name} value=${option.value} result=$result")
                if (option.required && result < 0) {
                    throw RuntimeException("Unsupported libmpv option ${option.name}=${option.value} (error $result)")
                }
            }
            val ret = created.initialize()
            if (ret < 0) {
                throw RuntimeException("Failed to initialize libmpv: error code $ret")
            }
            created.requestLogMessages(output.logLevel)
            output.attach(created)
            mpvObservedProperties.forEach { property ->
                created.observeProperty(property.name, property.format)
            }
            val pump = MpvEventPump(::drainEvents)
            eventPump = pump
            created.setWakeupListener { pump.request() }
            this.output = output
            client = created
            settled.value = true
            pump.request()
        } catch (error: Throwable) {
            eventPump?.closeAndAwait()
            eventPump = null
            runCatching { output.detach(created) }
            created.terminateDestroy()
            dispatcher.shutdown()
            throw error
        }
    }

    suspend fun awaitStartedOrClosed() {
        settled.first { it }
    }

    fun closeEvents() {
        eventPump?.closeAndAwait()
        callLock.withLock {
            client?.setWakeupListener(null)
        }
    }

    fun shutdown() {
        eventPump?.closeAndAwait()
        callLock.withLock {
            val current = client ?: return@withLock
            desktopPlayerTrace("$tracePrefix mpv shutdown start")
            client = null
            current.setWakeupListener(null)
            output?.detach(current)
            output = null
            current.terminateDestroy()
            desktopPlayerTrace("$tracePrefix mpv shutdown complete")
        }
        dispatcher.shutdown()
    }

    fun close() {
        loadRequestGate.invalidate()
        isClosed = true
        loader.cancel()
        dispatcher.shutdown()
    }

    fun notifyStateChanged() {
        onStateChanged()
    }

    fun withClient(block: (MpvClient) -> Unit) {
        callLock.withLock {
            val current = client ?: return@withLock
            if (isClosed) return@withLock
            block(current)
        }
    }

    fun postCommand(block: (MpvClient) -> Unit) {
        if (client == null) return
        dispatcher.post { withClient(block) }
    }

    fun setProperty(name: String, value: String) {
        postCommand { current -> current.setProperty(name, value) }
    }

    fun command(vararg args: String) {
        postCommand { current -> current.command(*args) }
    }

    fun load(
        url: String,
        audioUrl: String?,
        headers: Map<String, String>,
        onCommitted: ((Long) -> Unit)? = null,
        forceResolve: Boolean = false,
        startPositionMs: Long? = null,
    ) {
        desktopPlayerTrace(
            "$tracePrefix load request cause=${mpvSeekCallSite()} host=${playbackUrlForLog(url)} " +
                "startMs=${startPositionMs ?: 0L} forceResolve=$forceResolve",
        )
        loader.load(url, audioUrl, headers, onCommitted, forceResolve, startPositionMs)
    }

    fun currentPositionSeconds(): Double {
        val current = client ?: return 0.0
        if (isClosed) return 0.0
        return runCatching { current.getProperty("time-pos") }.getOrNull()?.toDoubleOrNull() ?: 0.0
    }

    fun restorePositionAfterLoad(loadGeneration: Long, positionSeconds: Double) {
        val expectedClient = client ?: return
        val cause = mpvSeekCallSite()
        dispatcher.postDelayed(500) {
            withClient { current ->
                if (current === expectedClient && playback.sourceGeneration == loadGeneration) {
                    runSeek(current, "restore", cause, (positionSeconds * 1000).toLong(), "seek", mpvSeconds(positionSeconds), "absolute")
                }
            }
        }
    }

    fun setPaused(paused: Boolean) {
        val cause = mpvSeekCallSite()
        postCommand { current ->
            val wasPaused = current.getProperty("pause") == "yes"
            val result = current.setProperty("pause", if (paused) "yes" else "no")
            if (wasPaused != paused) desktopPlayerTrace("pause request paused=$paused cause=$cause result=$result")
        }
    }

    fun togglePause() {
        val cause = mpvSeekCallSite()
        postCommand { current ->
            val result = current.command("cycle", "pause")
            desktopPlayerTrace("pause toggle cause=$cause result=$result")
        }
    }

    fun seekTo(positionMs: Long) {
        val cause = mpvSeekCallSite()
        postCommand { current ->
            runSeek(current, "absolute", cause, positionMs, "seek", mpvSeconds(positionMs / 1000.0), "absolute")
        }
    }

    fun seekToKeyframe(positionMs: Long) {
        val cause = mpvSeekCallSite()
        postCommand { current ->
            val result = runSeek(current, "keyframe", cause, positionMs, "seek", mpvSeconds(positionMs / 1000.0), "absolute", "keyframes")
            if (result == 0) {
                playback.initialSeekRequestedAtMs = System.currentTimeMillis()
                playback.initialSeekTargetMs = positionMs
                playback.initialSeekRecoveryIssued = false
            } else {
                playback.initialSeekRequestedAtMs = 0L
                playback.initialSeekTargetMs = 0L
            }
        }
    }

    fun seekBy(offsetMs: Long) {
        val cause = mpvSeekCallSite()
        postCommand { current ->
            runSeek(current, "relative", cause, null, "seek", mpvSeconds(offsetMs / 1000.0), "relative+exact")
        }
    }

    private fun runSeek(current: MpvClient, kind: String, cause: String, targetMs: Long?, vararg args: String): Int {
        val fromMs = current.getProperty("time-pos")?.toDoubleOrNull()?.let { (it * 1000).toLong() }
        val result = current.command(*args)
        seekJournal.record(kind, cause, targetMs, fromMs)
        desktopPlayerTrace(
            "seek command kind=$kind cause=$cause fromMs=${fromMs ?: -1L} " +
                "target=${targetMs ?: args.getOrNull(1)} generation=${playback.sourceGeneration} result=$result",
        )
        return result
    }

    fun setSpeed(speed: Float) {
        setProperty("speed", speed.toString())
    }

    fun setVolumeLevel(level: Float): PlayerAudioLevel? {
        val expectedClient = client ?: return null
        val clampedLevel = level.coerceIn(0f, 1f)
        val volumePercent = (clampedLevel * 100f).roundToInt()
        pendingVolumePercent.set(volumePercent)
        scheduleVolumeUpdate(expectedClient)
        return PlayerAudioLevel(
            fraction = clampedLevel,
            isMuted = volumePercent == 0,
        ).also { playback.volumeLevel = it }
    }

    fun setVolumeLevelNow(level: Float) {
        if (client == null) return
        val clampedLevel = level.coerceIn(0f, 1f)
        val volumePercent = (clampedLevel * 100f).roundToInt()
        setProperty("volume", volumePercent.toString())
        playback.volumeLevel = PlayerAudioLevel(
            fraction = clampedLevel,
            isMuted = volumePercent == 0,
        )
    }

    fun adjustVolumePercent(delta: Double) {
        postCommand { current ->
            val currentVolume = current.getProperty("volume")?.toDoubleOrNull() ?: 0.0
            val nextVolume = (currentVolume + delta).coerceIn(0.0, 100.0)
            current.setProperty("volume", nextVolume.toString())
        }
    }

    fun setSubtitleDelayMs(delayMs: Long) {
        setProperty("sub-delay", (delayMs / 1000.0).toString())
    }

    fun applyAudioLanguagePreferences(languages: List<String>) {
        val languageList = languages.joinToString(",")
        postCommand { current ->
            current.setProperty("alang", languageList)
            current.setProperty("aid", "auto")
        }
    }

    fun selectAudioTrack(index: Int) {
        val track = playback.audioTracks.getOrNull(index) ?: return
        setProperty("aid", track.id)
    }

    fun selectSubtitleTrack(index: Int) {
        if (index < 0) {
            setProperty("sid", "no")
        } else {
            val track = playback.subtitleTracks.getOrNull(index) ?: return
            setProperty("sid", track.id)
        }
    }

    fun addSubtitle(url: String, reapplyStyleLater: Boolean) {
        postCommand { current ->
            current.command("sub-add", url, "select")
            applyWindowsMpvSubtitleStyle(current, playback.subtitleStyle)
            if (reapplyStyleLater) {
                reapplySubtitleStyleLater(current)
            }
        }
    }

    fun clearExternalSubtitles() {
        postCommand(::removeExternalSubtitles)
    }

    fun clearExternalSubtitlesAndSelect(trackIndex: Int) {
        val track = playback.subtitleTracks.getOrNull(trackIndex)
        postCommand { current ->
            removeExternalSubtitles(current)
            if (track != null) {
                current.setProperty("sid", track.id)
            }
        }
    }

    fun applySubtitleStyle(style: SubtitleStyleState) {
        playback.subtitleStyle = style
        postCommand { current -> applyWindowsMpvSubtitleStyle(current, style) }
    }

    fun reapplySubtitleStyleLater(expectedClient: MpvClient) {
        val generation = playback.sourceGeneration
        dispatcher.postDelayed(300) {
            withClient { current ->
                if (current === expectedClient && playback.sourceGeneration == generation) {
                    applyWindowsMpvSubtitleStyle(current, playback.subtitleStyle)
                }
            }
        }
    }

    fun setResizeMode(resizeMode: PlayerResizeMode) {
        postCommand { current ->
            mpvResizeModeOptions(resizeMode).forEach { (name, value) -> current.setOption(name, value) }
        }
    }

    fun setStreamProfile(
        profileSummary: String?,
        isHdrLike: Boolean,
        hasDolbyVision: Boolean,
        hasHdrFallback: Boolean,
    ) {
        playback.streamProfileSummary = profileSummary
        playback.streamIsHdrLike = isHdrLike
        playback.streamHasDolbyVision = hasDolbyVision
        playback.streamHasHdrFallback = hasHdrFallback
        playback.lastVideoDiagnosticsSignature = null
        desktopPlayerTrace(
            "stream capability profile summary=${profileSummary ?: ""} hdr=$isHdrLike dolbyVision=$hasDolbyVision hdrFallback=$hasHdrFallback"
        )
    }

    private fun removeExternalSubtitles(current: MpvClient) {
        val count = boundedWindowsMpvTrackCount(current.getProperty("track-list/count"))
        for (i in count - 1 downTo 0) {
            val type = current.getProperty("track-list/$i/type") ?: ""
            val external = current.getProperty("track-list/$i/external") == "yes"
            if (type == "sub" && external) {
                val id = current.getProperty("track-list/$i/id") ?: ""
                current.command("sub-remove", id)
            }
        }
        current.setProperty("sid", "no")
    }

    private fun scheduleVolumeUpdate(expectedClient: MpvClient) {
        if (!volumeUpdateScheduled.compareAndSet(false, true)) return
        dispatcher.post {
            try {
                val volumePercent = pendingVolumePercent.getAndSet(null)
                if (volumePercent != null && client === expectedClient && !isClosed) {
                    withClient { current ->
                        if (current === expectedClient) {
                            current.setProperty("volume", volumePercent.toString())
                        }
                    }
                }
            } finally {
                volumeUpdateScheduled.set(false)
                if (pendingVolumePercent.get() != null && client === expectedClient && !isClosed) {
                    scheduleVolumeUpdate(expectedClient)
                }
            }
        }
    }

    fun isActiveEntryCurrent(): Boolean =
        playback.pendingLoadRequestGeneration == null &&
            playback.activePlaylistEntryGeneration == playback.sourceGeneration

    private fun drainEvents() {
        callLock.withLock {
            val current = client ?: return
            if (isClosed) return
            var hasUpdates = false
            var eventCount = 0
            while (!isClosed) {
                val event = current.waitEvent(0.0) ?: break
                when (event) {
                    is MpvClientEvent.Shutdown -> return
                    is MpvClientEvent.LogMessage -> {
                        recordWindowsAudioUnderrun(playback, event.text)
                        traceMpvLogMessage(event)
                    }
                    is MpvClientEvent.PropertyChange -> {
                        properties.update(event.name, event.value)
                        markWindowsAudioDiagnosticsRequested(playback, event.name)
                        if (mpvPropertyChangeAffectsTracks(event.name)) playback.trackRevision += 1
                        if (mpvPropertyChangeNotifiesState(event.name)) {
                            hasUpdates = true
                            eventCount += 1
                        }
                    }
                    is MpvClientEvent.StartFile -> {
                        desktopPlayerTrace(handleWindowsMpvStartFileEvent(playback, event.playlistEntryId))
                        hasUpdates = true
                        eventCount += 1
                    }
                    is MpvClientEvent.EndFile -> {
                        desktopPlayerTrace(handleWindowsMpvEndFileEvent(playback, event.data, current::errorString))
                        hasUpdates = true
                        eventCount += 1
                    }
                    is MpvClientEvent.Other -> {
                        if (event.id == MPV_EVENT_SEEK) {
                            desktopPlayerTrace("mpv event seek generation=${playback.sourceGeneration} ${seekJournal.describeLastSeek()}")
                        }
                        if (event.id == MPV_EVENT_PLAYBACK_RESTART && isActiveEntryCurrent()) {
                            onPlaybackRestart(playback.sourceGeneration)
                        }
                        if (event.id == MPV_EVENT_FILE_LOADED) {
                            desktopPlayerTrace("mpv event id=${event.id} error=${event.error} applying subtitle style")
                            applyWindowsMpvSubtitleStyle(current, playback.subtitleStyle)
                            configureWindowsVideoPipeline(current, playback, videoOutputTarget, "file-loaded")
                        }
                        hasUpdates = true
                        eventCount += 1
                    }
                }
            }
            if (hasUpdates) {
                perfCollector?.recordWakeup(eventCount)
                onStateChanged()
            }
        }
    }
}

internal fun mpvResizeModeOptions(resizeMode: PlayerResizeMode): List<Pair<String, String>> = when (resizeMode) {
    PlayerResizeMode.Fit -> listOf("panscan" to "0.0", "video-unscaled" to "no")
    PlayerResizeMode.Fill -> listOf("panscan" to "1.0", "video-unscaled" to "no")
    PlayerResizeMode.Zoom -> listOf("panscan" to "0.0", "video-unscaled" to "downscale-big")
}

private fun mpvSeconds(seconds: Double): String = String.format(java.util.Locale.ROOT, "%.3f", seconds)
