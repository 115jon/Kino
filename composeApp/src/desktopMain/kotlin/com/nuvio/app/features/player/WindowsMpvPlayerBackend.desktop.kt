package com.nuvio.app.features.player

import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color as ComposeColor
import com.nuvio.app.core.ui.appTheme
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioTypeScale
import com.nuvio.app.features.player.compose.WindowsComposeVideoSurface
import com.nuvio.app.features.player.compose.WindowsMpvPerfSampler
import com.nuvio.app.features.player.compose.isComposeWindowsVideoSurfaceSelected
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import javax.swing.SwingUtilities

internal class WindowsMpvPlayerBackend(
    private val urlResolver: MpvUrlResolver = defaultMpvUrlResolver,
) : DesktopPlaybackBackend {
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
        initialPositionMs: Long?,
        initialPositionRequestKey: String?,
        onInitialPositionHandled: (key: String, handled: Boolean) -> Unit,
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
        val useComposeVideoSurface = remember { isComposeWindowsVideoSurfaceSelected() }
        val useNativeVideoSurface = !useComposeVideoSurface && windowsVideoSurface() == WindowsVideoSurface.Native
        val currentOnSnapshot = rememberUpdatedState(onSnapshot)
        val currentOnError = rememberUpdatedState(onError)
        val currentOverlayContent = rememberUpdatedState(overlayContent)
        val currentInitialPosition = rememberUpdatedState(initialPositionRequestKey to initialPositionMs)
        val currentOnInitialPositionHandled = rememberUpdatedState(onInitialPositionHandled)
        val backendPerfStats = remember { DesktopBackendPerfCollector() }
        val playerStateSignals = remember {
            MutableSharedFlow<Unit>(
                replay = 0,
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        }
        val callbacks = remember { WindowsPlayerCallbacks() }
        val windowState = remember {
            WindowsPlayerWindowState(
                MpvEngine(
                    tracePrefix = when {
                        useComposeVideoSurface -> "compose panel"
                        useNativeVideoSurface -> "native panel"
                        else -> "embedded panel"
                    },
                    clientFactory = ::createJnaMpvClient,
                    videoOutputTarget = if (useNativeVideoSurface) {
                        WindowsVideoOutputTarget.Display
                    } else {
                        WindowsVideoOutputTarget.SdrTexture
                    },
                    urlResolver = urlResolver,
                ).apply { perfCollector = backendPerfStats },
            )
        }
        val engine = windowState.engine
        val playbackHeaders = remember(sourceHeaders) { sanitizePlaybackHeaders(sourceHeaders) }
        val panelBackground = remember(colorScheme) { windowsComposeColor(colorScheme.surface) }

        WindowsMpvPerfSampler(windowState, System.getProperty("kino.windows.video-surface") ?: "native")
        if (useComposeVideoSurface) {
            WindowsComposeVideoSurface(
                state = windowState,
                modifier = modifier,
                overlayContent = { currentOverlayContent.value() },
                onInitializationError = { message -> currentOnError.value(message) },
                onPlayerStateChanged = { playerStateSignals.tryEmit(Unit) },
                onSurfaceInteraction = onSurfaceInteraction,
                onSurfaceExit = onSurfaceExit,
                onWindowFocusChanged = onWindowFocusChanged,
                perfCollector = backendPerfStats,
            )
        } else SwingPanel(
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
                        panelBackground = panelBackground,
                        overlayColorScheme = colorScheme,
                        overlayTypography = overlayTypography,
                        overlayNuvioTokens = overlayNuvioTokens,
                        overlayTypeScale = overlayTypeScale,
                        overlayAppTheme = overlayAppTheme,
                        overlayRippleConfiguration = overlayRippleConfiguration,
                        state = windowState,
                        onClose = {
                            windowState.isClosed = true
                            callbacks.onClose?.invoke()
                        },
                        onInitializationError = { message -> currentOnError.value(message) },
                        onPlayerStateChanged = {
                            playerStateSignals.tryEmit(Unit)
                        },
                        onSurfaceInteraction = onSurfaceInteraction,
                        onSurfaceExit = onSurfaceExit,
                        onWindowFocusChanged = onWindowFocusChanged,
                        overlayContent = { currentOverlayContent.value() },
                    )
                } else {
                    EmbeddedWindowsPlayerPanel(
                        state = windowState,
                        onClose = {
                            windowState.isClosed = true
                            callbacks.onClose?.invoke()
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
            update = {},
        )

        DisposableEffect(Unit) {
            onDispose {
                SwingUtilities.invokeLater {
                    windowState.panelRef?.dispose()
                }
            }
        }

        LaunchedEffect(sourceUrl, sourceAudioUrl, playbackHeaders) {
            desktopPlayerTrace(
                "source load effect started host=${playbackUrlForLog(sourceUrl)} " +
                    "audioHost=${playbackUrlForLog(sourceAudioUrl)} " +
                    "playerReady=${engine.isReady} closed=${windowState.isClosed}"
            )
            while (!engine.isReady && !windowState.isClosed) {
                delay(50)
            }
            if (windowState.isClosed) {
                desktopPlayerTrace("source load effect aborted reason=window-closed host=${playbackUrlForLog(sourceUrl)}")
            } else if (!engine.isReady) {
                desktopPlayerTrace("source load effect aborted reason=player-unavailable host=${playbackUrlForLog(sourceUrl)}")
            } else {
                desktopPlayerTrace(
                    "windows backend load source host=${playbackUrlForLog(sourceUrl)} " +
                        "audioHost=${playbackUrlForLog(sourceAudioUrl)} " +
                        "headerKeys=${playbackHeaders.keys.joinToString()} " +
                        "responseHeaderKeys=${sanitizePlaybackResponseHeaders(sourceResponseHeaders).keys.joinToString()}"
                )
                windowState.playback.currentSourceUrl = sourceUrl
                windowState.playback.currentSourceAudioUrl = sourceAudioUrl
                windowState.playback.currentHeaders = playbackHeaders
                windowState.playback.lastTrackPollEpochMs = 0L
                val (startKey, startPositionMs) = currentInitialPosition.value
                val startRequestKey = startKey?.takeIf { startPositionMs != null && startPositionMs > 0L }
                windowState.panelRef?.loadFile(
                    url = sourceUrl,
                    audioUrl = sourceAudioUrl,
                    headers = playbackHeaders,
                    onCommitted = startRequestKey?.let { key ->
                        { _: Long -> SwingUtilities.invokeLater { currentOnInitialPositionHandled.value(key, true) } }
                    },
                    startPositionMs = startPositionMs.takeIf { startRequestKey != null },
                )
                backendPerfStats.recordSourceLoadSignal()
                playerStateSignals.tryEmit(Unit)
            }
        }

        LaunchedEffect(playWhenReady) {
            while (!engine.isReady && !windowState.isClosed) {
                delay(50)
            }
            engine.setPaused(!playWhenReady)
        }

        LaunchedEffect(resizeMode) {
            while (!engine.isReady && !windowState.isClosed) {
                delay(50)
            }
            if (engine.isReady) {
                SwingUtilities.invokeLater {
                    windowState.panelRef?.applyResizeMode(resizeMode)
                }
            }
        }

        val controller = remember { WindowsPlayerEngineController(windowState, callbacks) }

        LaunchedEffect(controller, sourceUrl, sourceAudioUrl, playbackHeaders) {
            onControllerReady(controller)
        }

        LaunchedEffect(windowState) {
            while (!windowState.isClosed) {
                delay(WindowsPlayerFallbackPollIntervalMs)
                if (engine.isReady) {
                    backendPerfStats.recordFallbackSignal()
                    playerStateSignals.tryEmit(Unit)
                }
            }
        }

        LaunchedEffect(windowState) {
            playerStateSignals.collect {
                if (windowState.isClosed) {
                    return@collect
                }
                val playback = windowState.playback
                val pollGeneration = playback.sourceGeneration
                val pollLoadRequestGeneration = playback.pendingLoadRequestGeneration
                val outcome = withContext(Dispatchers.IO) {
                    engine.pollPlayback(pollGeneration, pollLoadRequestGeneration)
                } ?: return@collect
                applyWindowsPlaybackPoll(
                    windowState = windowState,
                    outcome = outcome,
                    useNativeVideoSurface = useNativeVideoSurface,
                    onSnapshot = currentOnSnapshot.value,
                    onError = currentOnError.value,
                    perfStats = backendPerfStats,
                )
            }
        }
    }
}

private fun applyWindowsPlaybackPoll(
    windowState: WindowsPlayerWindowState,
    outcome: MpvPollOutcome,
    useNativeVideoSurface: Boolean,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
    perfStats: DesktopBackendPerfCollector,
) {
    val playback = windowState.playback
    val pollPayload = outcome.result
    if (pollPayload.generation != playback.sourceGeneration) return
    if (pollPayload.loadRequestGeneration != playback.pendingLoadRequestGeneration) return

    if (pollPayload.polledTracks) {
        playback.lastTrackPollEpochMs = System.currentTimeMillis()
    }
    playback.audioTracks = pollPayload.audioTracks
    playback.subtitleTracks = pollPayload.subtitleTracks
    playback.volumeLevel = pollPayload.volumeLevel
    if (useNativeVideoSurface) {
        (windowState.panelRef as? WindowsPlayerPanel)?.updateVideoOutputReady(pollPayload.videoOutputReady)
    }
    onSnapshot(pollPayload.snapshot)
    val playbackError = selectWindowsPlaybackError(
        mpvErrorMessage = pollPayload.errorMessage,
        hasLoadedMedia = playback.hasLoadedMedia,
        startupStalled = pollPayload.startupStalled,
        terminalPlaybackError = pollPayload.terminalPlaybackError,
    )
    if (playbackError == null) {
        if (playback.lastReportedPlaybackError != null) {
            playback.lastReportedPlaybackError = null
            onError(null)
        }
    } else if (playbackError != playback.lastReportedPlaybackError) {
        playback.lastReportedPlaybackError = playbackError
        onError(playbackError)
    }
    pollPayload.logMessage?.let(::desktopPlayerTrace)
    perfStats.recordPoll(
        durationNs = outcome.durationNs,
        playing = outcome.isPlaying,
        loading = outcome.isLoading,
        idle = outcome.isIdle,
    )
}
