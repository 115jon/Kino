package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
actual fun PlatformPlayerSurface(
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
) {
    val backend = remember { desktopPlaybackBackend() }
    val mediaKeySession = remember { DesktopMediaKeySession() }
    DisposableEffect(mediaKeySession) {
        onDispose { mediaKeySession.close() }
    }
    DisposableEffect(sourceUrl, sourceAudioUrl, sourceHeaders, sourceResponseHeaders) {
        mediaKeySession.sourceChanged()
        onDispose { }
    }
    backend.PlayerSurface(
        sourceUrl = sourceUrl,
        sourceAudioUrl = sourceAudioUrl,
        sourceHeaders = sourceHeaders,
        sourceResponseHeaders = sourceResponseHeaders,
        externalSubtitles = externalSubtitles,
        streamType = streamType,
        useYoutubeChunkedPlayback = useYoutubeChunkedPlayback,
        modifier = modifier,
        playWhenReady = playWhenReady,
        resizeMode = resizeMode,
        useNativeController = useNativeController,
        overlayContent = overlayContent,
        onControllerReady = { controller ->
            onControllerReady(mediaKeySession.bind(controller))
        },
        onSnapshot = { snapshot ->
            mediaKeySession.updatePlayback(snapshot)
            onSnapshot(snapshot)
        },
        onError = onError,
        onSurfaceInteraction = onSurfaceInteraction,
        onSurfaceExit = onSurfaceExit,
        onWindowFocusChanged = mediaKeySession::updateFocus,
    )
}

internal actual val supportsValidatedStartupFallback: Boolean =
    isValidatedStartupFallbackOs(System.getProperty("os.name").orEmpty())

internal actual fun platformPlayerSurfaceOwnsOverlay(): Boolean {
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    val surface = System.getProperty("kino.windows.video-surface")
    return osName.contains("win") && shouldUseNativeWindowsVideoSurface(surface)
}

internal interface DesktopPlaybackBackend {
    @Composable
    fun PlayerSurface(
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
    )
}

private fun desktopPlaybackBackend(): DesktopPlaybackBackend {
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    return if (osName.contains("mac")) {
        MacOSMpvPlayerBackend
    } else if (osName.contains("win")) {
        WindowsMpvPlayerBackend
    } else {
        UnsupportedDesktopPlaybackBackend(osName.ifBlank { "unknown" })
    }
}

private class UnsupportedDesktopPlaybackBackend(
    private val osName: String,
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
        overlayContent: @Composable () -> Unit,
        onControllerReady: (PlayerEngineController) -> Unit,
        onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
        onError: (String?) -> Unit,
        onSurfaceInteraction: (Boolean) -> Unit,
        onSurfaceExit: () -> Unit,
        onWindowFocusChanged: (Boolean, Long?) -> Unit,
    ) {
        overlayContent
        onSurfaceInteraction
        onSurfaceExit
        onWindowFocusChanged
        LaunchedEffect(osName) {
            onError("Desktop playback is not implemented for $osName")
        }
        Box(modifier = modifier.background(Color.Black))
    }
}
