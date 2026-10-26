package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import com.nuvio.app.core.ui.appTheme
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioTypeScale
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamItem
import com.sun.jna.Native
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.*
import kotlin.concurrent.withLock
import kotlin.math.roundToInt

internal object WindowsMpvPlayerBackend : DesktopPlaybackBackend {


    @Composable
    override fun PlayerSurface(
        sourceUrl: String,
        sourceAudioUrl: String?,
        sourceHeaders: Map<String, String>,
        sourceResponseHeaders: Map<String, String>,
        externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle>,
        streamType: String?,
        useYoutubeChunkedPlayback: Boolean,
        modifier: Modifier,
        playWhenReady: Boolean,
        resizeMode: PlayerResizeMode,
        useNativeController: Boolean,
        overlayContent: @Composable () -> Unit,
        onControllerReady: (PlayerEngineController) -> Unit,
        onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
        onError: (String?) -> Unit,
        onSurfaceInteraction: (Boolean) -> Unit,
        onSurfaceExit: () -> Unit,
        onWindowFocusChanged: (Boolean, Long?) -> Unit,
    ) {
        val colorScheme = MaterialTheme.colorScheme
        val overlayTypography = MaterialTheme.typography
        val overlayNuvioTokens = MaterialTheme.nuvio
        val overlayTypeScale = MaterialTheme.nuvioTypeScale
        val overlayAppTheme = MaterialTheme.appTheme
        val overlayRippleConfiguration = LocalRippleConfiguration.current
        val useNativeVideoSurface = windowsVideoSurface() == WindowsVideoSurface.Native
        val currentOnSnapshot = rememberUpdatedState(onSnapshot)
        val currentOnError = rememberUpdatedState(onError)
        val currentOverlayContent = rememberUpdatedState(overlayContent)
        var lastTrackPollEpochMs by remember { mutableStateOf(0L) }
        val backendPerfStats = remember { DesktopBackendPerfCollector() }
        val playerStateSignals = remember {
            MutableSharedFlow<Unit>(
                replay = 0,
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        }
        var onCloseCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
        var onAddonSubtitlesFetchCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
        var onSourcesRequestedCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
        var onSourceStreamSelectedCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }
        var onSourceFilterChangedCallback by remember { mutableStateOf<((String?) -> Unit)?>(null) }
        var onSourceReloadCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
        var onEpisodesRequestedCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
        var onEpisodeSelectedCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }
        var onEpisodeStreamSelectedCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }
        var onEpisodeFilterChangedCallback by remember { mutableStateOf<((String?) -> Unit)?>(null) }
        var onEpisodeReloadCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
        var onEpisodeBackCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
        var onNextEpisodeRequestedCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
        var onSubmitIntroSubmittedCallback by remember {
            mutableStateOf<((String, Double, Double) -> Unit)?>(null)
        }

        // Shared states to communicate between Compose/Coroutines and the Swing Window
        val windowState = remember { WindowsPlayerWindowState() }
        val playbackHeaders = remember(sourceHeaders) { sanitizePlaybackHeaders(sourceHeaders) }
        val playerTheme = remember(colorScheme) {
            PlayerTheme(
                accentColor = windowsComposeColor(colorScheme.primary),
                panelBgColor = windowsComposeColor(colorScheme.surface),
                controlBgColor = windowsComposeColor(colorScheme.surface.copy(alpha = 0.92f)),
                buttonBgColor = windowsComposeColor(colorScheme.surfaceVariant.copy(alpha = 0.95f)),
                textMutedColor = windowsComposeColor(colorScheme.onSurfaceVariant),
            )
        }

        SwingPanel(
            background = ComposeColor(windowsPlayerInteropBackgroundArgb()),
            factory = {
                desktopPlayerTrace(
                    "windows presentation surface=${if (useNativeVideoSurface) "native-d3d11" else "embedded-opengl"} " +
                        "composeInteropBlending=${System.getProperty("compose.interop.blending")} " +
                        "nativeControls=false overlayWindow=true",
                )
                if (useNativeVideoSurface) {
                    desktopPlayerTrace("native panel factory start")
                    WindowsPlayerPanel(
                        playerTheme = playerTheme,
                        overlayColorScheme = colorScheme,
                        overlayTypography = overlayTypography,
                        overlayNuvioTokens = overlayNuvioTokens,
                        overlayTypeScale = overlayTypeScale,
                        overlayAppTheme = overlayAppTheme,
                        overlayRippleConfiguration = overlayRippleConfiguration,
                        state = windowState,
                        onClose = {
                            windowState.isClosed = true
                            onCloseCallback?.invoke()
                        },
                        onInitializationError = { message -> currentOnError.value(message) },
                        onPlayerStateChanged = {
                            playerStateSignals.tryEmit(Unit)
                        },
                        onSurfaceInteraction = onSurfaceInteraction,
                        onSurfaceExit = onSurfaceExit,
                        onWindowFocusChanged = onWindowFocusChanged,
                        perfCollector = backendPerfStats,
                        onAddonSubtitlesFetch = { onAddonSubtitlesFetchCallback?.invoke() },
                        onSourcesRequested = { onSourcesRequestedCallback?.invoke() },
                        onSourceSelected = { url -> onSourceStreamSelectedCallback?.invoke(url) },
                        onSourceReload = { onSourceReloadCallback?.invoke() },
                        onEpisodesRequested = { onEpisodesRequestedCallback?.invoke() },
                        onEpisodeSelected = { id -> onEpisodeSelectedCallback?.invoke(id) },
                        onEpisodeStreamSelected = { url -> onEpisodeStreamSelectedCallback?.invoke(url) },
                        onNextEpisodeRequested = { onNextEpisodeRequestedCallback?.invoke() },
                        onSubmitIntro = { segmentType, startSec, endSec ->
                            onSubmitIntroSubmittedCallback?.invoke(segmentType, startSec, endSec)
                        },
                        overlayContent = { currentOverlayContent.value() },
                        showNativeControls = false,
                    )
                } else {
                    EmbeddedWindowsPlayerPanel(
                        state = windowState,
                        onClose = {
                            windowState.isClosed = true
                            onCloseCallback?.invoke()
                        },
                        onInitializationError = { message -> currentOnError.value(message) },
                        onPlayerStateChanged = {
                            playerStateSignals.tryEmit(Unit)
                        },
                        onSurfaceInteraction = onSurfaceInteraction,
                        onSurfaceExit = onSurfaceExit,
                        onWindowFocusChanged = onWindowFocusChanged,
                        perfCollector = backendPerfStats,
                    )
                }
            },
            modifier = modifier,
            update = { panel ->
                // No-op or updates
            }
        )

        // Setup Player Window Lifecycle
        DisposableEffect(Unit) {
            onDispose {
                SwingUtilities.invokeLater {
                    windowState.panelRef?.dispose()
                }
            }
        }

        // Handle source loading changes
        LaunchedEffect(sourceUrl, sourceAudioUrl, playbackHeaders) {
            desktopPlayerTrace(
                "source load effect started host=${playbackUrlForLog(sourceUrl)} " +
                    "audioHost=${playbackUrlForLog(sourceAudioUrl)} " +
                    "playerReady=${windowState.playerPtr != null} closed=${windowState.isClosed}"
            )
            while (windowState.playerPtr == null && !windowState.isClosed) {
                delay(50)
            }
            val ptr = windowState.playerPtr
            if (windowState.isClosed) {
                desktopPlayerTrace("source load effect aborted reason=window-closed host=${playbackUrlForLog(sourceUrl)}")
            } else if (ptr == null) {
                desktopPlayerTrace("source load effect aborted reason=player-unavailable host=${playbackUrlForLog(sourceUrl)}")
            } else {
                desktopPlayerTrace(
                    "windows backend load source host=${playbackUrlForLog(sourceUrl)} " +
                        "audioHost=${playbackUrlForLog(sourceAudioUrl)} " +
                        "headerKeys=${playbackHeaders.keys.joinToString()} " +
                        "responseHeaderKeys=${sanitizePlaybackResponseHeaders(sourceResponseHeaders).keys.joinToString()}"
                )
                windowState.currentSourceUrl = sourceUrl
                windowState.currentSourceAudioUrl = sourceAudioUrl
                    windowState.currentHeaders = playbackHeaders
                    lastTrackPollEpochMs = 0L
                windowState.panelRef?.loadFile(sourceUrl, sourceAudioUrl, playbackHeaders)
                backendPerfStats.recordSourceLoadSignal()
                playerStateSignals.tryEmit(Unit)
            }
            }

        // Handle Play/Pause changes from Compose side
        LaunchedEffect(playWhenReady) {
            while (windowState.playerPtr == null && !windowState.isClosed) {
                delay(50)
            }
            windowState.withMpv { ptr ->
                WindowsMpvLibrary.INSTANCE.mpv_set_property_string(ptr, "pause", if (playWhenReady) "no" else "yes")
            }
        }

        // Handle resize changes from Compose side
        LaunchedEffect(resizeMode) {
            while (windowState.playerPtr == null && !windowState.isClosed) {
                delay(50)
            }
            val ptr = windowState.playerPtr
            if (ptr != null) {
                SwingUtilities.invokeLater {
                    windowState.panelRef?.applyResizeMode(resizeMode)
                }
            }
        }

        // Implement the PlayerEngineController
        val controller = remember {
            val pendingVolumePercent = AtomicReference<Int?>(null)
            val volumeUpdateScheduled = AtomicBoolean(false)
            object : PlayerEngineController {
                override fun play() {
                    windowState.withMpv { ptr ->
                        WindowsMpvLibrary.INSTANCE.mpv_set_property_string(ptr, "pause", "no")
                    }
                }

                override fun pause() {
                    windowState.withMpv { ptr ->
                        WindowsMpvLibrary.INSTANCE.mpv_set_property_string(ptr, "pause", "yes")
                    }
                }

                override fun togglePlayPause() {
                    windowState.withMpv { ptr ->
                        WindowsMpvLibrary.INSTANCE.mpv_command(ptr, arrayOf("cycle", "pause", null))
                    }
                }

                override fun seekTo(positionMs: Long) {
                    val seconds = positionMs / 1000.0
                    SwingUtilities.invokeLater {
                        windowState.withMpv { ptr ->
                            val result = WindowsMpvLibrary.INSTANCE.mpv_command(
                                ptr,
                                arrayOf("seek", String.format("%.3f", seconds), "absolute", null),
                            )
                            desktopPlayerTrace("seek command targetMs=$positionMs result=$result")
                        }
                    }
                }

                override fun seekToKeyframe(positionMs: Long) {
                    val seconds = positionMs / 1000.0
                    SwingUtilities.invokeLater {
                        windowState.withMpv { ptr ->
                            val result = WindowsMpvLibrary.INSTANCE.mpv_command(
                                ptr,
                                arrayOf("seek", String.format("%.3f", seconds), "absolute", "keyframes", null),
                            )
                            if (result == 0) {
                                windowState.initialSeekRequestedAtMs = System.currentTimeMillis()
                                windowState.initialSeekTargetMs = positionMs
                                windowState.initialSeekRecoveryIssued = false
                            } else {
                                windowState.initialSeekRequestedAtMs = 0L
                                windowState.initialSeekTargetMs = 0L
                            }
                            desktopPlayerTrace("keyframe seek command targetMs=$positionMs result=$result")
                        }
                    }
                }

                override fun seekBy(offsetMs: Long) {
                    val seconds = offsetMs / 1000.0
                    SwingUtilities.invokeLater {
                        windowState.withMpv { ptr ->
                            WindowsMpvLibrary.INSTANCE.mpv_command(
                                ptr,
                                arrayOf("seek", String.format("%.3f", seconds), "relative", null),
                            )
                        }
                    }
                }

                override fun supportsVolumeControl(): Boolean = true

                override fun currentVolumeLevel(): PlayerAudioLevel = windowState.volumeLevel

                override fun setVolumeLevel(level: Float): PlayerAudioLevel? {
                    val ptr = windowState.playerPtr ?: return null
                    val clampedLevel = level.coerceIn(0f, 1f)
                    val volumePercent = (clampedLevel * 100f).roundToInt()
                    pendingVolumePercent.set(volumePercent)
                    scheduleLatestWindowsVolumeUpdate(
                        state = windowState,
                        playerPtr = ptr,
                        pendingVolumePercent = pendingVolumePercent,
                        volumeUpdateScheduled = volumeUpdateScheduled,
                    )
                    return PlayerAudioLevel(
                        fraction = clampedLevel,
                        isMuted = volumePercent == 0,
                    ).also { windowState.volumeLevel = it }
                }

                override fun supportsFullscreenToggle(): Boolean = true

                override fun toggleFullscreen() {
                    SwingUtilities.invokeLater {
                        windowState.panelRef?.toggleFullScreen()
                    }
                }

                override fun requestInteractionFocus() {
                    SwingUtilities.invokeLater {
                        windowState.panelRef?.requestInteractionFocus()
                    }
                }

                override fun setStreamProfileInfo(
                    profileSummary: String?,
                    isHdrLike: Boolean,
                    hasDolbyVision: Boolean,
                    hasHdrFallback: Boolean,
                ) {
                    windowState.streamProfileSummary = profileSummary
                    windowState.streamIsHdrLike = isHdrLike
                    windowState.streamHasDolbyVision = hasDolbyVision
                    windowState.streamHasHdrFallback = hasHdrFallback
                    windowState.lastVideoDiagnosticsSignature = null
                    desktopPlayerTrace(
                        "stream capability profile summary=${profileSummary ?: ""} hdr=$isHdrLike dolbyVision=$hasDolbyVision hdrFallback=$hasHdrFallback"
                    )
                }

                override fun retry() {
                    SwingUtilities.invokeLater {
                        windowState.panelRef?.retryPlayback()
                    }
                }

                override fun setPlaybackSpeed(speed: Float) {
                    windowState.withMpv { ptr ->
                        WindowsMpvLibrary.INSTANCE.mpv_set_property_string(ptr, "speed", speed.toString())
                    }
                }

                override fun getAudioTracks(): List<AudioTrack> = windowState.audioTracks

                override fun getSubtitleTracks(): List<SubtitleTrack> = windowState.subtitleTracks

                override fun applyAudioLanguagePreferences(languages: List<String>) {
                    windowState.withMpv { ptr ->
                        WindowsMpvLibrary.INSTANCE.mpv_set_property_string(ptr, "alang", languages.joinToString(","))
                        WindowsMpvLibrary.INSTANCE.mpv_set_property_string(ptr, "aid", "auto")
                    }
                }

                override fun selectAudioTrack(index: Int) {
                    if (index in windowState.audioTracks.indices) {
                        val track = windowState.audioTracks[index]
                        windowState.withMpv { ptr ->
                            WindowsMpvLibrary.INSTANCE.mpv_set_property_string(ptr, "aid", track.id)
                        }
                    }
                }

                override fun selectSubtitleTrack(index: Int) {
                    windowState.withMpv { ptr ->
                        if (index < 0) {
                            WindowsMpvLibrary.INSTANCE.mpv_set_property_string(ptr, "sid", "no")
                        } else if (index in windowState.subtitleTracks.indices) {
                            val track = windowState.subtitleTracks[index]
                            WindowsMpvLibrary.INSTANCE.mpv_set_property_string(ptr, "sid", track.id)
                        }
                    }
                }

                override fun setSubtitleUri(url: String) {
                    windowState.withMpv { ptr ->
                        WindowsMpvLibrary.INSTANCE.mpv_command(ptr, arrayOf("sub-add", url, "select", null))
                        applyWindowsMpvSubtitleStyle(ptr, windowState.subtitleStyle)
                    }
                }

                override fun clearExternalSubtitle() {
                    SwingUtilities.invokeLater {
                        windowState.panelRef?.clearExternalSubtitles()
                    }
                }

                override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
                    SwingUtilities.invokeLater {
                        windowState.panelRef?.clearExternalSubtitlesAndSelect(trackIndex)
                    }
                }

                override fun applySubtitleStyle(style: SubtitleStyleState) {
                    windowState.subtitleStyle = style
                    windowState.withMpv { ptr ->
                        applyWindowsMpvSubtitleStyle(ptr, style)
                    }
                }

                override fun setMetadata(
                    title: String,
                    streamTitle: String,
                    providerName: String,
                    seasonNumber: Int?,
                    episodeNumber: Int?,
                    episodeTitle: String?,
                    artwork: String?,
                    logo: String?,
                ) {
                    windowState.metaTitle = title
                    windowState.metaStreamTitle = streamTitle
                    windowState.metaProviderName = providerName
                    windowState.metaSeason = seasonNumber
                    windowState.metaEpisode = episodeNumber
                    windowState.metaEpisodeTitle = episodeTitle
                }

                override fun setPlayerFlags(hasVideoId: Boolean, isSeries: Boolean) {
                    windowState.hasVideoId = hasVideoId
                    windowState.isSeries = isSeries
                }

                override fun setSubmitIntroEnabled(enabled: Boolean) {
                    windowState.submitIntroEnabled = enabled
                }

                override fun showSkipButton(type: String, endTimeMs: Long) {
                    windowState.skipIntroType = type
                    windowState.skipIntroEndTimeMs = endTimeMs
                    SwingUtilities.invokeLater {
                        (windowState.panelRef as? WindowsPlayerPanel)?.updateSkipIntroButtonVisibility(true)
                    }
                }

                override fun hideSkipButton() {
                    windowState.skipIntroEndTimeMs = null
                    SwingUtilities.invokeLater {
                        (windowState.panelRef as? WindowsPlayerPanel)?.updateSkipIntroButtonVisibility(false)
                    }
                }

                override fun showNextEpisode(
                    season: Int,
                    episode: Int,
                    title: String,
                    thumbnail: String?,
                    hasAired: Boolean,
                ) {
                    windowState.nextEpisodeSeason = season
                    windowState.nextEpisodeEpisode = episode
                    windowState.nextEpisodeTitle = title
                    SwingUtilities.invokeLater {
                        (windowState.panelRef as? WindowsPlayerPanel)?.updateNextEpisodeButtonVisibility(true)
                    }
                }

                override fun hideNextEpisode() {
                    windowState.nextEpisodeSeason = null
                    windowState.nextEpisodeEpisode = null
                    windowState.nextEpisodeTitle = null
                    SwingUtilities.invokeLater {
                        (windowState.panelRef as? WindowsPlayerPanel)?.updateNextEpisodeButtonVisibility(false)
                    }
                }

                override fun setOnNextEpisodeRequestedCallback(callback: () -> Unit) {
                    onNextEpisodeRequestedCallback = callback
                }

                override fun setOnSubmitIntroSubmittedCallback(callback: (String, Double, Double) -> Unit) {
                    onSubmitIntroSubmittedCallback = callback
                }

                override fun setOnCloseCallback(callback: () -> Unit) {
                    onCloseCallback = callback
                }

                override fun setOnAddonSubtitlesFetchCallback(callback: () -> Unit) {
                    onAddonSubtitlesFetchCallback = callback
                }

                override fun pushAddonSubtitles(subtitles: List<AddonSubtitle>, isLoading: Boolean) {
                    windowState.addonSubtitles = subtitles
                    windowState.addonSubtitlesLoading = isLoading
                }

                override fun setOnSourcesRequestedCallback(callback: () -> Unit) {
                    onSourcesRequestedCallback = callback
                }

                override fun setOnSourceStreamSelectedCallback(callback: (String) -> Unit) {
                    onSourceStreamSelectedCallback = callback
                }

                override fun setOnSourceFilterChangedCallback(callback: (String?) -> Unit) {
                    onSourceFilterChangedCallback = callback
                }

                override fun setOnSourceReloadCallback(callback: () -> Unit) {
                    onSourceReloadCallback = callback
                }

                override fun setOnEpisodesRequestedCallback(callback: () -> Unit) {
                    onEpisodesRequestedCallback = callback
                }

                override fun setOnEpisodeSelectedCallback(callback: (String) -> Unit) {
                    onEpisodeSelectedCallback = callback
                }

                override fun setOnEpisodeStreamSelectedCallback(callback: (String) -> Unit) {
                    onEpisodeStreamSelectedCallback = callback
                }

                override fun setOnEpisodeFilterChangedCallback(callback: (String?) -> Unit) {
                    onEpisodeFilterChangedCallback = callback
                }

                override fun setOnEpisodeReloadCallback(callback: () -> Unit) {
                    onEpisodeReloadCallback = callback
                }

                override fun setOnEpisodeBackCallback(callback: () -> Unit) {
                    onEpisodeBackCallback = callback
                }

                override fun pushSourceData(
                    streams: List<StreamItem>,
                    groups: List<AddonStreamGroup>,
                    loading: Boolean,
                    selectedFilter: String?,
                    currentStreamUrl: String?,
                ) {
                    windowState.sourceStreams = streams
                    windowState.sourceGroups = groups
                    windowState.sourcesLoading = loading
                    windowState.sourceFilter = selectedFilter
                    windowState.currentSourceStreamUrl = currentStreamUrl
                }

                override fun pushEpisodes(episodes: List<MetaVideo>) {
                    windowState.episodes = episodes
                }

                override fun pushEpisodeStreamsData(
                    streams: List<StreamItem>,
                    groups: List<AddonStreamGroup>,
                    loading: Boolean,
                    selectedFilter: String?,
                    currentStreamUrl: String?,
                ) {
                    windowState.episodeStreams = streams
                    windowState.episodeGroups = groups
                    windowState.episodeLoading = loading
                    windowState.episodeFilter = selectedFilter
                    windowState.currentEpisodeStreamUrl = currentStreamUrl
                }

                override fun showEpisodeStreamsView(season: Int?, episode: Int?, title: String?) {
                    // Supported inline in our Swing episodes list / stream selection
                }

                override fun dismissNativePanels() {
                    // Auto-dismisses on Swing popup click-outs
                }

                override fun switchSource(url: String, audioUrl: String?, headersJson: String?) {
                    val sanitizedHeaders = sanitizePlaybackHeaders(parseWindowsPlaybackHeaders(headersJson))
                    windowState.currentSourceUrl = url
                    windowState.currentSourceAudioUrl = audioUrl
                    windowState.currentHeaders = sanitizedHeaders
                    windowState.panelRef?.loadFile(url, audioUrl, sanitizedHeaders)
                }
            }
        }

        // Inform Compose controller is ready
        LaunchedEffect(controller, sourceUrl, sourceAudioUrl, playbackHeaders) {
            onControllerReady(controller)
        }

        LaunchedEffect(windowState) {
            while (!windowState.isClosed) {
                delay(WindowsPlayerFallbackPollIntervalMs)
                if (windowState.playerPtr != null) {
                    backendPerfStats.recordFallbackSignal()
                    playerStateSignals.tryEmit(Unit)
                }
            }
        }

        LaunchedEffect(windowState) {
            playerStateSignals.collect {
                val lib = WindowsMpvLibrary.INSTANCE
                if (windowState.isClosed) {
                    return@collect
                }
                val pollGeneration = windowState.sourceGeneration
                val pollLoadRequestGeneration = windowState.pendingLoadRequestGeneration
                val pollResult = withContext(Dispatchers.IO) {
                    windowState.mpvCallLock.withLock {
                    val pollStartNs = System.nanoTime()
                    val ptr = windowState.playerPtr ?: return@withContext null

                    fun getString(name: String): String? {
                        val property = runCatching { lib.mpv_get_property_string(ptr, name) }.getOrNull()
                            ?: return null
                        return try {
                            property.getString(0)
                        } catch (_: Throwable) {
                            null
                        } finally {
                            runCatching { lib.mpv_free(property) }
                        }
                    }

                    val posSec = getString("time-pos")?.toDoubleOrNull() ?: 0.0
                    val durSec = getString("duration")?.toDoubleOrNull() ?: 0.0
                    val cacheSec = getString("demuxer-cache-time")?.toDoubleOrNull() ?: 0.0
                    val speed = getString("speed")?.toDoubleOrNull() ?: 1.0
                    val paused = getString("pause") == "yes"
                    val idle = getString("core-idle") == "yes"
                    val eofReached = getString("eof-reached") == "yes"
                    val seeking = getString("seeking") == "yes"
                    val bufferingCache = getString("paused-for-cache") == "yes"
                    val volumePercent = getString("volume")?.toDoubleOrNull() ?: (windowState.volumeLevel.fraction * 100f).toDouble()
                    val path = getString("path")
                    val mediaTitle = getString("media-title")
                    val fileFormat = getString("file-format")
                    val error = getString("error")
                    val videoOutputConfigured = getString("vo-configured")
                    val videoWidth = getString("video-params/w")?.toIntOrNull() ?: 0
                    val videoHeight = getString("video-params/h")?.toIntOrNull() ?: 0
                    val videoOutputReady = shouldRevealWindowsVideoSurface(
                        loadPending = pollLoadRequestGeneration != null,
                        activePlaylistEntryGeneration = windowState.activePlaylistEntryGeneration,
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
                        configureWindowsVideoPipeline(windowState, ptr, "poll")
                    } else {
                        null
                    }

                    if (pollGeneration != windowState.sourceGeneration) return@withContext null
                    val nowEpochMs = System.currentTimeMillis()
                    val initialSeekElapsedMs = nowEpochMs - windowState.initialSeekRequestedAtMs
                    val initialSeekSettled =
                        !seeking &&
                            (windowState.initialSeekTargetMs == 0L ||
                                kotlin.math.abs(positionMs - windowState.initialSeekTargetMs) <= 5000L)
                    if (initialSeekSettled) {
                        windowState.initialSeekRequestedAtMs = 0L
                        windowState.initialSeekTargetMs = 0L
                    } else if (
                        windowState.initialSeekRequestedAtMs > 0L &&
                            initialSeekElapsedMs >= WindowsPlayerInitialSeekTimeoutMs &&
                            !windowState.initialSeekRecoveryIssued
                    ) {
                        val result = lib.mpv_command(
                            ptr,
                            arrayOf("seek", "0", "absolute", "keyframes", null),
                        )
                        windowState.initialSeekRecoveryIssued = true
                        windowState.initialSeekRequestedAtMs = 0L
                        windowState.initialSeekTargetMs = 0L
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
                            stallSinceMs = windowState.startupStallSinceMs,
                            isStalled = windowState.startupStalled,
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
                    windowState.startupStallSinceMs = startupState.stallSinceMs
                    windowState.startupStalled = startupState.isStalled
                    windowState.hasLoadedMedia = windowState.hasLoadedMedia || hasLoadedMedia
                    val startupStalled = startupState.isStalled
                    val shouldPollTracks =
                        nowEpochMs - lastTrackPollEpochMs >= WindowsPlayerTrackPollIntervalMs
                    val audioDiagnostics = if (
                        (!pendingLoad && path != null && (shouldPollTracks || isPlayerLoading || positionMs == 0L)) ||
                        (!pendingLoad && windowState.audioDiagnosticsRequested) ||
                        !error.isNullOrBlank() ||
                        windowState.terminalPlaybackError != null
                    ) {
                        windowState.audioDiagnosticsRequested = false
                        runCatching { readMpvAudioDiagnostics(lib, ptr, windowState) }.getOrNull()
                    } else {
                        null
                    }

                    val audioTracksList: List<AudioTrack>
                    val subtitleTracksList: List<SubtitleTrack>
                    val trackMetadataDiagnostics = mutableListOf<String>()
                    var trackMetadataLog: String? = null
                    if (shouldPollTracks) {
                        val trackCount = boundedWindowsMpvTrackCount(getString("track-list/count"))
                        val nextAudioTracks = mutableListOf<AudioTrack>()
                        val nextSubtitleTracks = mutableListOf<SubtitleTrack>()
                        var audioIdx = 0
                        var subIdx = 0

                        for (i in 0 until trackCount) {
                            val type = getString("track-list/$i/type") ?: ""
                            val metadata = windowsMpvTrackMetadataForModel(
                                id = getString("track-list/$i/id"),
                                title = getString("track-list/$i/title"),
                                language = getString("track-list/$i/lang"),
                                codec = getString("track-list/$i/codec"),
                            )
                            val id = metadata.id
                            val title = metadata.title
                            val lang = metadata.language
                            val codec = metadata.codec
                            val external = getString("track-list/$i/external") == "yes"
                            val selected = getString("track-list/$i/selected") == "yes"
                            trackMetadataDiagnostics +=
                                "${playbackMetadataForLog(type)}[$i] id=${playbackMetadataForLog(id)} " +
                                    "title=${playbackMetadataForLog(title).ifBlank { "<empty>" }} " +
                                    "lang=${playbackMetadataForLog(lang).ifBlank { "<empty>" }} " +
                                    "codec=${playbackMetadataForLog(codec).ifBlank { "<empty>" }} external=$external"

                            if (type == "audio") {
                                val sampleRate = parseWindowsAudioInt(getString("track-list/$i/demux-samplerate"))
                                val channelCount = parseWindowsAudioInt(getString("track-list/$i/demux-channel-count"))
                                val channelLayout = getString("track-list/$i/demux-channels")
                                    ?.take(MaxWindowsMpvChannelLayoutLength)
                                val bitrate = parseWindowsAudioLong(getString("track-list/$i/demux-bitrate"))
                                nextAudioTracks.add(
                                    AudioTrack(
                                        index = audioIdx++,
                                        id = id,
                                        label = title,
                                        language = lang,
                                        codec = codec.takeIf { it.isNotBlank() },
                                        sampleRate = sampleRate,
                                        channelCount = channelCount,
                                        channelLayout = channelLayout,
                                        bitrate = bitrate,
                                        isSelected = selected
                                    )
                                )
                            } else if (type == "sub") {
                                nextSubtitleTracks.add(
                                    SubtitleTrack(
                                        index = subIdx++,
                                        id = id,
                                        label = title,
                                        language = lang,
                                        isSelected = selected,
                                        isForced = inferForcedSubtitleTrack(title, lang, id)
                                    )
                                )
                            }
                        }
                        val trackMetadataSignature = trackMetadataDiagnostics
                            .joinToString("||")
                            .take(MaxWindowsMpvLogMessageLength)
                        if (trackMetadataSignature != windowState.lastTrackMetadataSignature) {
                            windowState.lastTrackMetadataSignature = trackMetadataSignature
                            trackMetadataLog = trackMetadataDiagnostics.takeIf { it.isNotEmpty() }?.joinToString(" || ") {
                                "mpv track metadata $it"
                            }?.take(MaxWindowsMpvLogMessageLength)
                        }
                        audioTracksList = nextAudioTracks
                        subtitleTracksList = nextSubtitleTracks
                    } else {
                        audioTracksList = windowState.audioTracks
                        subtitleTracksList = windowState.subtitleTracks
                    }

                    val snapshot = PlayerPlaybackSnapshot(
                        isLoading = pendingLoad || isPlayerLoading,
                        isStartupStalled = startupStalled,
                        mediaLoaded = !pendingLoad && windowState.hasLoadedMedia,
                        isPlaying = !pendingLoad && isPlayerPlaying,
                        isEnded = !pendingLoad && isPlayerEnded,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        bufferedPositionMs = bufferedMs,
                        playbackSpeed = speed.toFloat(),
                    )
                    val audioDiagnosticsLog = audioDiagnostics?.let { diagnostics ->
                        val trigger = when {
                            !error.isNullOrBlank() || windowState.terminalPlaybackError != null -> "failure"
                            isPlayerLoading || positionMs == 0L -> "load"
                            else -> "track-change"
                        }
                        if (windowState.audioDiagnosticsRateLimiter.shouldEmit(pollGeneration, diagnostics)) {
                            formatWindowsAudioDiagnostics(trigger, diagnostics)
                        } else {
                            null
                        }
                    }

                    Triple(
                        WindowsPlaybackPollResult(
                            generation = pollGeneration,
                            loadRequestGeneration = pollLoadRequestGeneration,
                            snapshot = snapshot,
                            audioTracks = audioTracksList,
                            subtitleTracks = subtitleTracksList,
                            volumeLevel = toPlayerAudioLevel(volumePercent),
                            videoOutputReady = videoOutputReady,
                            polledTracks = shouldPollTracks,
                            errorMessage = if (pendingLoad) null else error?.takeIf { it.isNotBlank() },
                            terminalPlaybackError = windowState.terminalPlaybackError
                                ?.takeIf { windowState.terminalPlaybackErrorGeneration == pollGeneration },
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
                                trackMetadataLog,
                                audioDiagnosticsLog,
                                videoPipelineLog,
                            ).joinToString(" | ")
                                .take(MaxWindowsMpvLogMessageLength)
                                .takeIf { it.isNotBlank() },
                        ),
                        Triple(isPlayerPlaying, isPlayerLoading, idle),
                        System.nanoTime() - pollStartNs,
                    )
                    }
                } ?: return@collect

                val (pollPayload, playbackFlags, pollDurationNs) = pollResult
                if (pollPayload.generation != windowState.sourceGeneration) {
                    return@collect
                }
                if (pollPayload.loadRequestGeneration != windowState.pendingLoadRequestGeneration) {
                    return@collect
                }
                val (isPlaying, isLoading, isIdle) = playbackFlags

                if (pollPayload.polledTracks) {
                    lastTrackPollEpochMs = System.currentTimeMillis()
                }
                windowState.audioTracks = pollPayload.audioTracks
                windowState.subtitleTracks = pollPayload.subtitleTracks
                windowState.volumeLevel = pollPayload.volumeLevel
                if (useNativeVideoSurface) {
                    (windowState.panelRef as? WindowsPlayerPanel)?.updatePlaybackState(
                        positionMs = pollPayload.snapshot.positionMs,
                        durationMs = pollPayload.snapshot.durationMs,
                        isPlaying = pollPayload.snapshot.isPlaying,
                        isLoading = pollPayload.snapshot.isLoading,
                        speed = pollPayload.snapshot.playbackSpeed,
                        volumePercent = pollPayload.volumeLevel.fraction * 100f,
                        videoOutputReady = pollPayload.videoOutputReady,
                    )
                }
                currentOnSnapshot.value(pollPayload.snapshot)
                val playbackError = selectWindowsPlaybackError(
                    mpvErrorMessage = pollPayload.errorMessage,
                    hasLoadedMedia = windowState.hasLoadedMedia,
                    startupStalled = pollPayload.startupStalled,
                    terminalPlaybackError = pollPayload.terminalPlaybackError,
                )
                if (playbackError == null) {
                    if (windowState.lastReportedPlaybackError != null) {
                        windowState.lastReportedPlaybackError = null
                        currentOnError.value(null)
                    }
                } else if (playbackError != windowState.lastReportedPlaybackError) {
                    windowState.lastReportedPlaybackError = playbackError
                    currentOnError.value(playbackError)
                }
                pollPayload.logMessage?.let(::desktopPlayerTrace)
                backendPerfStats.recordPoll(
                    durationNs = pollDurationNs,
                    playing = isPlaying,
                    loading = isLoading,
                    idle = isIdle,
                )
            }
        }

    }
}

private data class WindowsPlaybackPollResult(
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

private fun toPlayerAudioLevel(volumePercent: Double): PlayerAudioLevel {
    val fraction = (volumePercent / 100.0).coerceIn(0.0, 1.0).toFloat()
    return PlayerAudioLevel(
        fraction = fraction,
        isMuted = fraction <= 0f,
    )
}
