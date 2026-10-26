package com.nuvio.app.features.player

import kotlinx.coroutines.Dispatchers
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
    urlResolver: MpvUrlResolver = defaultMpvUrlResolver,
    resolutionContext: CoroutineContext = Dispatchers.IO,
) {
    val callLock = ReentrantLock()
    val loadRequestGate = WindowsPlaybackLoadRequestGate()
    val playback = MpvPlaybackState()
    val properties = MpvPropertyCache()

    @Volatile
    var isClosed = false

    @Volatile
    var perfCollector: DesktopBackendPerfCollector? = null

    @Volatile
    var onStateChanged: () -> Unit = {}

    @Volatile
    var onLoadCommitted: () -> Unit = {}

    @Volatile
    var onFatalError: () -> Unit = {}

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
            mpvEngineOptions(output).forEach { option ->
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
            pump.request()
        } catch (error: Throwable) {
            eventPump?.closeAndAwait()
            eventPump = null
            runCatching { output.detach(created) }
            created.terminateDestroy()
            throw error
        }
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
    ) {
        loader.load(url, audioUrl, headers, onCommitted, forceResolve)
    }

    fun currentPositionSeconds(): Double {
        val current = client ?: return 0.0
        if (isClosed) return 0.0
        return runCatching { current.getProperty("time-pos") }.getOrNull()?.toDoubleOrNull() ?: 0.0
    }

    fun restorePositionAfterLoad(loadGeneration: Long, positionSeconds: Double) {
        val expectedClient = client ?: return
        dispatcher.postDelayed(500) {
            withClient { current ->
                if (current === expectedClient && playback.sourceGeneration == loadGeneration) {
                    current.command("seek", String.format("%.3f", positionSeconds), "absolute")
                }
            }
        }
    }

    fun setPaused(paused: Boolean) {
        setProperty("pause", if (paused) "yes" else "no")
    }

    fun togglePause() {
        command("cycle", "pause")
    }

    fun seekTo(positionMs: Long) {
        val seconds = positionMs / 1000.0
        postCommand { current ->
            val result = current.command("seek", String.format("%.3f", seconds), "absolute")
            desktopPlayerTrace("seek command targetMs=$positionMs result=$result")
        }
    }

    fun seekToKeyframe(positionMs: Long) {
        val seconds = positionMs / 1000.0
        postCommand { current ->
            val result = current.command("seek", String.format("%.3f", seconds), "absolute", "keyframes")
            if (result == 0) {
                playback.initialSeekRequestedAtMs = System.currentTimeMillis()
                playback.initialSeekTargetMs = positionMs
                playback.initialSeekRecoveryIssued = false
            } else {
                playback.initialSeekRequestedAtMs = 0L
                playback.initialSeekTargetMs = 0L
            }
            desktopPlayerTrace("keyframe seek command targetMs=$positionMs result=$result")
        }
    }

    fun seekBy(offsetMs: Long) {
        val seconds = offsetMs / 1000.0
        command("seek", String.format("%.3f", seconds), "relative")
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

    fun setAudioDelayMs(delayMs: Long) {
        setProperty("audio-delay", (delayMs / 1000.0).toString())
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
                        if (event.id == MPV_EVENT_FILE_LOADED) {
                            desktopPlayerTrace("mpv event id=${event.id} error=${event.error} applying subtitle style")
                            applyWindowsMpvSubtitleStyle(current, playback.subtitleStyle)
                            configureWindowsVideoPipeline(current, playback, "file-loaded")
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
