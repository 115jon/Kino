package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.material3.MaterialTheme
import co.touchlab.kermit.Logger
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamItem
import com.jogamp.opengl.GL
import com.jogamp.opengl.GLAutoDrawable
import com.jogamp.opengl.GLCapabilities
import com.jogamp.opengl.GLEventListener
import com.jogamp.opengl.GLProfile
import com.jogamp.opengl.GLRunnable
import com.jogamp.opengl.awt.GLJPanel
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.awt.*
import java.awt.event.*
import java.awt.image.BufferedImage
import java.net.URI
import java.util.Timer
import java.util.TimerTask
import javax.swing.*
import javax.swing.plaf.basic.BasicSliderUI
import kotlin.concurrent.schedule
import kotlin.math.roundToInt

private val desktopPlayerLog = Logger.withTag("DesktopPlayerTrace")

private fun desktopPlayerTrace(message: String) {
    desktopPlayerLog.i { message }
}

private fun playbackUrlForLog(url: String?): String =
    url.orEmpty().let { value ->
        runCatching { URI(value).host }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "<unknown>"
    }

private val desktopPlayerPerfLog = Logger.withTag("DesktopPlayerPerf")

private class DesktopBackendPerfStats {
    private var lastLogNs = System.nanoTime()
    private var wakeupBursts = 0L
    private var wakeupEvents = 0L
    private var updateCallbacks = 0L
    private var fallbackSignals = 0L
    private var sourceLoadSignals = 0L
    private var polls = 0L
    private var pollDurationNsTotal = 0L

    @Synchronized
    fun recordWakeup(events: Int) {
        wakeupBursts += 1
        wakeupEvents += events.toLong()
        maybeLog()
    }

    @Synchronized
    fun recordRenderUpdateCallback() {
        updateCallbacks += 1
        maybeLog()
    }

    @Synchronized
    fun recordFallbackSignal() {
        fallbackSignals += 1
        maybeLog()
    }

    @Synchronized
    fun recordSourceLoadSignal() {
        sourceLoadSignals += 1
        maybeLog()
    }

    @Synchronized
    fun recordPoll(durationNs: Long, playing: Boolean, loading: Boolean, idle: Boolean) {
        polls += 1
        pollDurationNsTotal += durationNs
        maybeLog(playing, loading, idle)
    }

    @Synchronized
    private fun maybeLog(
        playing: Boolean = false,
        loading: Boolean = false,
        idle: Boolean = false,
    ) {
        val nowNs = System.nanoTime()
        if (nowNs - lastLogNs < 2_000_000_000L) {
            return
        }
        val averagePollMs = if (polls > 0L) {
            (pollDurationNsTotal.toDouble() / polls.toDouble()) / 1_000_000.0
        } else {
            0.0
        }
        desktopPlayerPerfLog.i {
            "backend wakeupBursts=$wakeupBursts wakeupEvents=$wakeupEvents updateCallbacks=$updateCallbacks fallbackSignals=$fallbackSignals sourceLoadSignals=$sourceLoadSignals polls=$polls avgPollMs=${"%.2f".format(averagePollMs)} playing=$playing loading=$loading idle=$idle"
        }
        lastLogNs = nowNs
        wakeupBursts = 0L
        wakeupEvents = 0L
        updateCallbacks = 0L
        fallbackSignals = 0L
        sourceLoadSignals = 0L
        polls = 0L
        pollDurationNsTotal = 0L
    }
}


// JNA Bindings to libmpv
internal interface WindowsMpvLibrary : Library {
    companion object {
        val INSTANCE: WindowsMpvLibrary by lazy {
            WindowsMpvNativeLoader.load()
        }
    }

    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_set_option_string(ctx: Pointer, name: String, value: String): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, value: String): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_free(data: Pointer)
    fun mpv_command(ctx: Pointer, args: Array<String?>): Int
    fun mpv_set_wakeup_callback(ctx: Pointer, callback: MpvWakeupCallback?, callbackCtx: Pointer?)
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer?
    fun mpv_request_log_messages(ctx: Pointer, minLevel: String): Int
    fun mpv_observe_property(ctx: Pointer, replyUserdata: Long, name: String, format: Int): Int
    fun mpv_render_context_create(res: PointerByReference, ctx: Pointer, params: Pointer): Int
    fun mpv_render_context_set_update_callback(ctx: Pointer, callback: MpvRenderUpdateCallback?, callbackCtx: Pointer?)
    fun mpv_render_context_update(ctx: Pointer): Long
    fun mpv_render_context_render(ctx: Pointer, params: Pointer): Int
    fun mpv_render_context_free(ctx: Pointer)
}

private const val MPV_RENDER_PARAM_INVALID = 0
private const val MPV_RENDER_PARAM_API_TYPE = 1
private const val MPV_RENDER_PARAM_OPENGL_INIT_PARAMS = 2
private const val MPV_RENDER_PARAM_OPENGL_FBO = 3
private const val MPV_RENDER_PARAM_FLIP_Y = 4
private const val MPV_RENDER_UPDATE_FRAME = 1L
private const val MPV_RENDER_API_TYPE_OPENGL = "opengl"
private const val WindowsPlayerTrackPollIntervalMs = 1_000L
private const val WindowsPlayerFallbackPollIntervalMs = 250L
private const val WindowsPlayerMaxCacheBytes = "536870912"
private const val WindowsPlayerMaxBackCacheBytes = "268435456"
private const val WindowsPlayerReadAheadSeconds = "30"
private const val WindowsPlayerCachePauseWaitSeconds = "5"
private const val WindowsHdrCompatibilityFilter = "libplacebo=apply_dolbyvision=1:apply_filmgrain=1:peak_detect=1:tonemapping=auto"
private const val MPV_EVENT_NONE = 0
private const val MPV_EVENT_SHUTDOWN = 1
private const val MPV_EVENT_LOG_MESSAGE = 2
private const val MPV_EVENT_START_FILE = 6
private const val MPV_EVENT_END_FILE = 7
private const val MPV_EVENT_FILE_LOADED = 8
private const val MPV_FORMAT_FLAG = 3
private const val MPV_FORMAT_INT64 = 4
private const val MPV_FORMAT_DOUBLE = 5

internal class DesktopBackendPerfCollector {
    private val stats = DesktopBackendPerfStats()

    fun recordWakeup(events: Int) = stats.recordWakeup(events)

    fun recordRenderUpdateCallback() = stats.recordRenderUpdateCallback()

    fun recordFallbackSignal() = stats.recordFallbackSignal()

    fun recordSourceLoadSignal() = stats.recordSourceLoadSignal()

    fun recordPoll(durationNs: Long, playing: Boolean, loading: Boolean, idle: Boolean) =
        stats.recordPoll(durationNs, playing, loading, idle)
}

private data class WindowsPlaybackPollResult(
    val snapshot: PlayerPlaybackSnapshot,
    val audioTracks: List<AudioTrack>,
    val subtitleTracks: List<SubtitleTrack>,
    val volumeLevel: PlayerAudioLevel,
    val polledTracks: Boolean,
    val logMessage: String?,
)

private fun toPlayerAudioLevel(volumePercent: Double): PlayerAudioLevel {
    val fraction = (volumePercent / 100.0).coerceIn(0.0, 1.0).toFloat()
    return PlayerAudioLevel(
        fraction = fraction,
        isMuted = fraction <= 0f,
    )
}

private data class WindowedPresentationState(
    val bounds: Rectangle,
    val extendedState: Int?,
)

private class FullscreenWindowState {
    var activeDevice: GraphicsDevice? = null
    var windowedState: WindowedPresentationState? = null

    fun enter(window: Window) {
        val targetDevice = window.graphicsConfiguration?.device
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        if (activeDevice === targetDevice && targetDevice.fullScreenWindow === window) {
            return
        }
        exit(window)
        windowedState = WindowedPresentationState(
            bounds = window.bounds,
            extendedState = (window as? Frame)?.extendedState,
        )
        activeDevice = targetDevice
        targetDevice.fullScreenWindow = window
    }

    fun exit(window: Window?) {
        val activeWindow = window ?: activeDevice?.fullScreenWindow
        val device = activeDevice
        if (device != null && activeWindow != null && device.fullScreenWindow === activeWindow) {
            device.fullScreenWindow = null
        } else if (device != null && device.fullScreenWindow != null) {
            device.fullScreenWindow = null
        }
        if (activeWindow != null) {
            windowedState?.let { previous ->
                val frame = activeWindow as? Frame
                frame?.extendedState = Frame.NORMAL
                activeWindow.bounds = previous.bounds
                val targetExtendedState = previous.extendedState
                if (frame != null && targetExtendedState != null && targetExtendedState != Frame.NORMAL) {
                    frame.extendedState = targetExtendedState
                }
            }
        }
        activeDevice = null
        windowedState = null
    }

    fun isActive(window: Window): Boolean {
        val device = activeDevice
        return device != null && device.fullScreenWindow === window
    }
}

internal interface MpvRenderUpdateCallback : Callback {
    fun invoke(ctx: Pointer?)
}

internal interface MpvWakeupCallback : Callback {
    fun invoke(ctx: Pointer?)
}

interface MpvOpenGlGetProcAddressCallback : Callback {
    fun invoke(ctx: Pointer?, name: String?): Pointer?
}

@Structure.FieldOrder("type", "data")
open class MpvRenderParam : Structure() {
    @JvmField
    var type: Int = MPV_RENDER_PARAM_INVALID

    @JvmField
    var data: Pointer? = null
}

@Structure.FieldOrder("get_proc_address", "get_proc_address_ctx")
open class MpvOpenGlInitParams : Structure() {
    @JvmField
    var get_proc_address: MpvOpenGlGetProcAddressCallback? = null

    @JvmField
    var get_proc_address_ctx: Pointer? = null
}

@Structure.FieldOrder("fbo", "w", "h", "internal_format")
open class MpvOpenGlFbo : Structure() {
    @JvmField
    var fbo: Int = 0

    @JvmField
    var w: Int = 0

    @JvmField
    var h: Int = 0

    @JvmField
    var internal_format: Int = 0
}

@Structure.FieldOrder("event_id", "error", "reply_userdata", "data")
open class MpvEvent(pointer: Pointer? = null) : Structure(pointer) {
    @JvmField
    var event_id: Int = 0

    @JvmField
    var error: Int = 0

    @JvmField
    var reply_userdata: Long = 0

    @JvmField
    var data: Pointer? = null
}

@Structure.FieldOrder("prefix", "level", "text", "log_level")
private class MpvLogMessage(pointer: Pointer? = null) : Structure(pointer) {
    @JvmField
    var prefix: Pointer? = null

    @JvmField
    var level: Pointer? = null

    @JvmField
    var text: Pointer? = null

    @JvmField
    var log_level: Pointer? = null
}

private fun mpvLogText(pointer: Pointer?): String = pointer?.getString(0).orEmpty()

private fun redactMpvLogText(value: String): String =
    Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE).replace(value) { match ->
        playbackUrlForLog(match.value)
    }

private fun createRenderParams(vararg values: Pair<Int, Pointer?>): Array<MpvRenderParam> {
    @Suppress("UNCHECKED_CAST")
    val params = MpvRenderParam().toArray(values.size + 1) as Array<MpvRenderParam>
    values.forEachIndexed { index, (type, data) ->
        params[index].type = type
        params[index].data = data
        params[index].write()
    }
    params[values.size].type = MPV_RENDER_PARAM_INVALID
    params[values.size].data = null
    params[values.size].write()
    return params
}

private fun cStringMemory(value: String): Memory {
    val bytes = value.toByteArray(Charsets.UTF_8)
    return Memory((bytes.size + 1).toLong()).apply {
        write(0, bytes, 0, bytes.size)
        setByte(bytes.size.toLong(), 0)
    }
}

private fun intPairMemory(first: Int, second: Int): Memory =
    Memory(8L).apply {
        setInt(0L, first)
        setInt(4L, second)
    }

private fun longMemory(value: Long): Memory =
    Memory(8L).apply {
        setLong(0L, value)
    }

private fun intMemory(value: Int): Memory =
    Memory(4L).apply {
        setInt(0L, value)
    }

private interface WglLibrary : Library {
    companion object {
        val INSTANCE: WglLibrary by lazy { Native.load("opengl32", WglLibrary::class.java) }
    }

    fun wglGetProcAddress(name: String): Pointer?
}

private interface Kernel32Library : Library {
    companion object {
        val INSTANCE: Kernel32Library by lazy { Native.load("kernel32", Kernel32Library::class.java) }
    }

    fun GetModuleHandleA(name: String): Pointer?
    fun LoadLibraryA(name: String): Pointer?
    fun GetProcAddress(module: Pointer, name: String): Pointer?
}

private fun resolveOpenGlProcAddress(name: String): Pointer? {
    val wglAddress = WglLibrary.INSTANCE.wglGetProcAddress(name)
    val nativeAddress = wglAddress?.let(Pointer::nativeValue) ?: 0L
    if (nativeAddress != 0L && nativeAddress != 1L && nativeAddress != 2L && nativeAddress != 3L && nativeAddress != -1L) {
        return wglAddress
    }
    val kernel32 = Kernel32Library.INSTANCE
    val module = kernel32.GetModuleHandleA("opengl32.dll") ?: kernel32.LoadLibraryA("opengl32.dll") ?: return null
    return kernel32.GetProcAddress(module, name)
}

private fun createGlCapabilities(): GLCapabilities {
    WindowsJoglNativeLoader.ensureConfigured()
    GLProfile.initSingleton()
    return GLCapabilities(GLProfile.getDefault())
}

internal class PlayerTheme(
    val accentColor: java.awt.Color,
    val panelBgColor: java.awt.Color,
    val controlBgColor: java.awt.Color,
    val buttonBgColor: java.awt.Color,
    val textMutedColor: java.awt.Color,
)

private fun windowsComposeColor(color: androidx.compose.ui.graphics.Color): java.awt.Color =
    java.awt.Color(
        (color.red * 255f).roundToInt().coerceIn(0, 255),
        (color.green * 255f).roundToInt().coerceIn(0, 255),
        (color.blue * 255f).roundToInt().coerceIn(0, 255),
        (color.alpha * 255f).roundToInt().coerceIn(0, 255),
    )

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
        onControllerReady: (PlayerEngineController) -> Unit,
        onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
        onError: (String?) -> Unit,
        onSurfaceInteraction: (Boolean) -> Unit,
    ) {
        val colorScheme = MaterialTheme.colorScheme
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
            background = ComposeColor.Black,
            factory = {
                if (useNativeController) {
                    WindowsPlayerPanel(
                        playerTheme = playerTheme,
                        state = windowState,
                        onClose = {
                            windowState.isClosed = true
                            onCloseCallback?.invoke()
                        },
                        onPlayerStateChanged = {
                            playerStateSignals.tryEmit(Unit)
                        },
                        onSurfaceInteraction = onSurfaceInteraction,
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
                        showNativeControls = true,
                    )
                } else {
                    EmbeddedWindowsPlayerPanel(
                        state = windowState,
                        onClose = {
                            windowState.isClosed = true
                            onCloseCallback?.invoke()
                        },
                        onPlayerStateChanged = {
                            playerStateSignals.tryEmit(Unit)
                        },
                        onSurfaceInteraction = onSurfaceInteraction,
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
            while (windowState.playerPtr == null && !windowState.isClosed) {
                delay(50)
            }
            val ptr = windowState.playerPtr
            if (ptr != null) {
                desktopPlayerTrace(
                    "windows backend load source host=${playbackUrlForLog(sourceUrl)} " +
                        "audioHost=${playbackUrlForLog(sourceAudioUrl)} " +
                        "headerKeys=${playbackHeaders.keys.joinToString()} " +
                        "responseHeaderKeys=${sanitizePlaybackResponseHeaders(sourceResponseHeaders).keys.joinToString()}"
                )
                windowState.currentSourceUrl = sourceUrl
                windowState.currentSourceAudioUrl = sourceAudioUrl
                windowState.currentHeaders = playbackHeaders
                SwingUtilities.invokeLater {
                    windowState.panelRef?.loadFile(sourceUrl, sourceAudioUrl, playbackHeaders)
                    backendPerfStats.recordSourceLoadSignal()
                    playerStateSignals.tryEmit(Unit)
                }
            }
        }

        // Handle Play/Pause changes from Compose side
        LaunchedEffect(playWhenReady) {
            while (windowState.playerPtr == null && !windowState.isClosed) {
                delay(50)
            }
            val ptr = windowState.playerPtr
            if (ptr != null) {
                val lib = WindowsMpvLibrary.INSTANCE
                lib.mpv_set_property_string(ptr, "pause", if (playWhenReady) "no" else "yes")
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
            object : PlayerEngineController {
                override fun play() {
                    val ptr = windowState.playerPtr ?: return
                    WindowsMpvLibrary.INSTANCE.mpv_set_property_string(ptr, "pause", "no")
                }

                override fun pause() {
                    val ptr = windowState.playerPtr ?: return
                    WindowsMpvLibrary.INSTANCE.mpv_set_property_string(ptr, "pause", "yes")
                }

                override fun seekTo(positionMs: Long) {
                    val ptr = windowState.playerPtr ?: return
                    val seconds = positionMs / 1000.0
                    WindowsMpvLibrary.INSTANCE.mpv_command(ptr, arrayOf("seek", String.format("%.3f", seconds), "absolute", null))
                }

                override fun seekBy(offsetMs: Long) {
                    val ptr = windowState.playerPtr ?: return
                    val seconds = offsetMs / 1000.0
                    WindowsMpvLibrary.INSTANCE.mpv_command(ptr, arrayOf("seek", String.format("%.3f", seconds), "relative", null))
                }

                override fun supportsVolumeControl(): Boolean = true

                override fun currentVolumeLevel(): PlayerAudioLevel = windowState.volumeLevel

                override fun setVolumeLevel(level: Float): PlayerAudioLevel? {
                    val ptr = windowState.playerPtr ?: return null
                    val clampedLevel = level.coerceIn(0f, 1f)
                    val volumePercent = (clampedLevel * 100f).roundToInt()
                    WindowsMpvLibrary.INSTANCE.mpv_set_property_string(ptr, "volume", volumePercent.toString())
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
                    val ptr = windowState.playerPtr ?: return
                    WindowsMpvLibrary.INSTANCE.mpv_set_property_string(ptr, "speed", speed.toString())
                }

                override fun getAudioTracks(): List<AudioTrack> = windowState.audioTracks

                override fun getSubtitleTracks(): List<SubtitleTrack> = windowState.subtitleTracks

                override fun selectAudioTrack(index: Int) {
                    val ptr = windowState.playerPtr ?: return
                    if (index in windowState.audioTracks.indices) {
                        val track = windowState.audioTracks[index]
                        WindowsMpvLibrary.INSTANCE.mpv_set_property_string(ptr, "aid", track.id)
                    }
                }

                override fun selectSubtitleTrack(index: Int) {
                    val ptr = windowState.playerPtr ?: return
                    if (index < 0) {
                        WindowsMpvLibrary.INSTANCE.mpv_set_property_string(ptr, "sid", "no")
                    } else if (index in windowState.subtitleTracks.indices) {
                        val track = windowState.subtitleTracks[index]
                        WindowsMpvLibrary.INSTANCE.mpv_set_property_string(ptr, "sid", track.id)
                    }
                }

                override fun setSubtitleUri(url: String) {
                    val ptr = windowState.playerPtr ?: return
                    WindowsMpvLibrary.INSTANCE.mpv_command(ptr, arrayOf("sub-add", url, "select", null))
                    applyWindowsMpvSubtitleStyle(ptr, windowState.subtitleStyle)
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
                    val ptr = windowState.playerPtr ?: return
                    applyWindowsMpvSubtitleStyle(ptr, style)
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
                }

                override fun hideSkipButton() {
                    windowState.skipIntroEndTimeMs = null
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
                }

                override fun hideNextEpisode() {
                    windowState.nextEpisodeSeason = null
                    windowState.nextEpisodeEpisode = null
                    windowState.nextEpisodeTitle = null
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
                    val sanitizedHeaders = sanitizePlaybackHeaders(windowState.currentHeaders)
                    SwingUtilities.invokeLater {
                        windowState.panelRef?.loadFile(url, audioUrl, sanitizedHeaders)
                    }
                }
            }
        }

        // Inform Compose controller is ready
        LaunchedEffect(controller) {
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
            playerStateSignals.collectLatest {
                val lib = WindowsMpvLibrary.INSTANCE
                if (windowState.isClosed) {
                    return@collectLatest
                }
                val pollResult = withContext(Dispatchers.IO) {
                    val pollStartNs = System.nanoTime()
                    val ptr = windowState.playerPtr ?: return@withContext null

                    fun getString(name: String): String? {
                        val p = lib.mpv_get_property_string(ptr, name)
                        if (p != null) {
                            val str = p.getString(0)
                            lib.mpv_free(p)
                            return str
                        }
                        return null
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

                    val durationMs = (durSec * 1000).toLong()
                    val positionMs = (posSec.coerceAtLeast(0.0) * 1000).toLong()
                    val bufferedMs = ((posSec + cacheSec).coerceAtLeast(0.0) * 1000).toLong()

                    val isPlayerLoading = (idle && !paused && !eofReached) || seeking || bufferingCache
                    val isPlayerPlaying = !paused && !idle && !eofReached
                    val isPlayerEnded = eofReached

                    val nowEpochMs = System.currentTimeMillis()
                    val shouldPollTracks =
                        nowEpochMs - lastTrackPollEpochMs >= WindowsPlayerTrackPollIntervalMs ||
                            isPlayerLoading ||
                            windowState.audioTracks.isEmpty() ||
                            windowState.subtitleTracks.isEmpty()

                    val audioTracksList: List<AudioTrack>
                    val subtitleTracksList: List<SubtitleTrack>
                    if (shouldPollTracks) {
                        val trackCount = getString("track-list/count")?.toIntOrNull() ?: 0
                        val nextAudioTracks = mutableListOf<AudioTrack>()
                        val nextSubtitleTracks = mutableListOf<SubtitleTrack>()
                        var audioIdx = 0
                        var subIdx = 0

                        for (i in 0 until trackCount) {
                            val type = getString("track-list/$i/type") ?: ""
                            val id = getString("track-list/$i/id") ?: ""
                            val title = getString("track-list/$i/title") ?: ""
                            val lang = getString("track-list/$i/lang") ?: ""
                            val selected = getString("track-list/$i/selected") == "yes"

                            if (type == "audio") {
                                nextAudioTracks.add(
                                    AudioTrack(
                                        index = audioIdx++,
                                        id = id,
                                        label = title,
                                        language = lang,
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
                        audioTracksList = nextAudioTracks
                        subtitleTracksList = nextSubtitleTracks
                    } else {
                        audioTracksList = windowState.audioTracks
                        subtitleTracksList = windowState.subtitleTracks
                    }

                    val snapshot = PlayerPlaybackSnapshot(
                        isLoading = isPlayerLoading,
                        isPlaying = isPlayerPlaying,
                        isEnded = isPlayerEnded,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        bufferedPositionMs = bufferedMs,
                        playbackSpeed = speed.toFloat(),
                    )

                    Triple(
                        WindowsPlaybackPollResult(
                            snapshot = snapshot,
                            audioTracks = audioTracksList,
                            subtitleTracks = subtitleTracksList,
                            volumeLevel = toPlayerAudioLevel(volumePercent),
                            polledTracks = shouldPollTracks,
                            logMessage = if (positionMs == 0L || isPlayerLoading || !error.isNullOrBlank()) {
                                "mpv snapshot path=${path?.take(240)} mediaTitle=${mediaTitle ?: ""} format=${fileFormat ?: ""} error=${error ?: ""} durationMs=$durationMs positionMs=$positionMs bufferedMs=$bufferedMs paused=$paused idle=$idle eof=$eofReached seeking=$seeking buffering=$bufferingCache"
                            } else {
                                null
                            },
                        ),
                        Triple(isPlayerPlaying, isPlayerLoading, idle),
                        System.nanoTime() - pollStartNs,
                    )
                } ?: return@collectLatest

                val (pollPayload, playbackFlags, pollDurationNs) = pollResult
                val (isPlaying, isLoading, isIdle) = playbackFlags

                if (pollPayload.polledTracks) {
                    lastTrackPollEpochMs = System.currentTimeMillis()
                }
                windowState.audioTracks = pollPayload.audioTracks
                windowState.subtitleTracks = pollPayload.subtitleTracks
                windowState.volumeLevel = pollPayload.volumeLevel
                (windowState.panelRef as? WindowsPlayerPanel)?.updatePlaybackState(
                    positionMs = pollPayload.snapshot.positionMs,
                    durationMs = pollPayload.snapshot.durationMs,
                    isPlaying = pollPayload.snapshot.isPlaying,
                    isLoading = pollPayload.snapshot.isLoading,
                    speed = pollPayload.snapshot.playbackSpeed,
                )
                onSnapshot(pollPayload.snapshot)
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

// Window state storage class to share context across threads safely
internal class WindowsPlayerWindowState {
    var playerPtr: Pointer? = null
    var isClosed = false
    var panelRef: WindowsPlaybackPanel? = null

    // Meta details
    var metaTitle = ""
    var metaStreamTitle = ""
    var metaProviderName = ""
    var metaSeason: Int? = null
    var metaEpisode: Int? = null
    var metaEpisodeTitle: String? = null
    var hasVideoId = false
    var isSeries = false
    var submitIntroEnabled = false

    // Playback preferences
    var currentSourceUrl = ""
    var currentSourceAudioUrl: String? = null
    var currentHeaders = mapOf<String, String>()
    var volumeLevel = PlayerAudioLevel(fraction = 1f, isMuted = false)
    var streamProfileSummary: String? = null
    var streamIsHdrLike = false
    var streamHasDolbyVision = false
    var streamHasHdrFallback = false
    var subtitleStyle = SubtitleStyleState.DEFAULT

    // Tracks
    var audioTracks = emptyList<AudioTrack>()
    var subtitleTracks = emptyList<SubtitleTrack>()
    var addonSubtitles = emptyList<AddonSubtitle>()
    var addonSubtitlesLoading = false

    // Skip controls
    var skipIntroType: String? = null
    var skipIntroEndTimeMs: Long? = null
    var nextEpisodeSeason: Int? = null
    var nextEpisodeEpisode: Int? = null
    var nextEpisodeTitle: String? = null

    // Source Selection Data
    var sourceStreams = emptyList<StreamItem>()
    var sourceGroups = emptyList<AddonStreamGroup>()
    var sourcesLoading = false
    var sourceFilter: String? = null
    var currentSourceStreamUrl: String? = null

    // Episode Selection Data
    var episodes = emptyList<MetaVideo>()
    var episodeStreams = emptyList<StreamItem>()
    var episodeGroups = emptyList<AddonStreamGroup>()
    var episodeLoading = false
    var episodeFilter: String? = null
    var currentEpisodeStreamUrl: String? = null
}

internal interface WindowsPlaybackPanel {
    fun loadFile(url: String, audioUrl: String?, headers: Map<String, String>)
    fun loadSubtitleUrl(url: String)
    fun applyResizeMode(resizeMode: PlayerResizeMode)
    fun toggleFullScreen() {}
    fun requestInteractionFocus() {}
    fun retryPlayback()
    fun clearExternalSubtitles()
    fun clearExternalSubtitlesAndSelect(trackIndex: Int)
    fun dispose()
}

internal class EmbeddedWindowsPlayerPanel(
    private val state: WindowsPlayerWindowState,
    private val onClose: () -> Unit,
    private val onPlayerStateChanged: () -> Unit,
    private val perfCollector: DesktopBackendPerfCollector? = null,
    private val onSurfaceInteraction: (Boolean) -> Unit = {},
) : JPanel(BorderLayout()), WindowsPlaybackPanel {
    private val glPanel = GLJPanel(createGlCapabilities())
    private var mpvInitialized = false
    private var playerDisposed = false
    private val fullscreenState = FullscreenWindowState()
    private var renderContext: Pointer? = null
    private var renderUpdateCallback: MpvRenderUpdateCallback? = null
    private var wakeupCallback: MpvWakeupCallback? = null
    private var glProcAddressCallback: MpvOpenGlGetProcAddressCallback? = null
    private var glInitParams: MpvOpenGlInitParams? = null
    private val renderStateLock = Any()
    private var renderScheduled = false
    private var forceRenderRequested = false

    init {
        state.panelRef = this
        background = java.awt.Color.BLACK
        isFocusable = true
        focusTraversalKeysEnabled = false
        glPanel.background = java.awt.Color.BLACK
        glPanel.isFocusable = true
        glPanel.focusTraversalKeysEnabled = false
        glPanel.addGLEventListener(object : GLEventListener {
            override fun init(drawable: GLAutoDrawable) {
                if (!mpvInitialized) {
                    mpvInitialized = true
                    initializeMpv(drawable)
                }
            }

    override fun display(drawable: GLAutoDrawable) {
        renderOnGlThread(drawable)
    }

            override fun reshape(drawable: GLAutoDrawable, x: Int, y: Int, width: Int, height: Int) {
                scheduleRender(force = true)
            }

            override fun dispose(drawable: GLAutoDrawable) {
                desktopPlayerTrace("embedded panel gl dispose callback")
                releasePlayerResources()
            }
        })
        add(glPanel, BorderLayout.CENTER)
        val keyListener = object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_SPACE -> togglePlayPause()
                    KeyEvent.VK_LEFT -> seekByOffset(-10_000)
                    KeyEvent.VK_RIGHT -> seekByOffset(10_000)
                    KeyEvent.VK_UP -> adjustVolume(0.05f)
                    KeyEvent.VK_DOWN -> adjustVolume(-0.05f)
                    KeyEvent.VK_F -> toggleFullScreen()
                    KeyEvent.VK_ESCAPE -> closePlayer()
                    else -> Unit
                }
            }
        }
        addKeyListener(keyListener)
        glPanel.addKeyListener(keyListener)
        val focusRequester = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                requestFocusInWindow()
                glPanel.requestFocusInWindow()
            }

            override fun mouseClicked(e: MouseEvent) {
                onSurfaceInteraction(true)
                if (e.clickCount == 2) {
                    toggleFullScreen()
                }
            }

            override fun mouseMoved(e: MouseEvent) {
                onSurfaceInteraction(false)
            }
        }
        addMouseListener(focusRequester)
        addMouseMotionListener(focusRequester)
        glPanel.addMouseListener(focusRequester)
        glPanel.addMouseMotionListener(focusRequester)
        glPanel.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                scheduleRender(force = true)
            }

            override fun componentShown(e: ComponentEvent) {
                scheduleRender(force = true)
            }
        })
    }

    override fun addNotify() {
        super.addNotify()
        SwingUtilities.invokeLater {
            requestInteractionFocus()
            onPlayerStateChanged()
        }
    }

    private fun initializeMpv(drawable: GLAutoDrawable) {
        val lib = WindowsMpvLibrary.INSTANCE
        val ptr = lib.mpv_create() ?: throw RuntimeException("Failed to create libmpv instance")
        lib.mpv_set_option_string(ptr, "vo", "libmpv")
        lib.mpv_set_option_string(ptr, "target-colorspace-hint", "yes")
        lib.mpv_set_option_string(ptr, "hdr-compute-peak", "yes")
        lib.mpv_set_option_string(ptr, "tone-mapping", "auto")
        lib.mpv_set_option_string(ptr, "input-media-keys", "yes")
        lib.mpv_set_option_string(ptr, "subs-match-os-language", "yes")
        lib.mpv_set_option_string(ptr, "subs-fallback", "yes")
        lib.mpv_set_option_string(ptr, "sub-ass-override", "force")
        lib.mpv_set_option_string(ptr, "hwdec", "auto-copy-safe")
        lib.mpv_set_option_string(ptr, "keep-open", "yes")
        lib.mpv_set_option_string(ptr, "cache", "yes")
        lib.mpv_set_option_string(ptr, "cache-pause", "yes")
        lib.mpv_set_option_string(ptr, "cache-pause-wait", WindowsPlayerCachePauseWaitSeconds)
        lib.mpv_set_option_string(ptr, "demuxer-max-bytes", WindowsPlayerMaxCacheBytes)
        lib.mpv_set_option_string(ptr, "demuxer-max-back-bytes", WindowsPlayerMaxBackCacheBytes)
        lib.mpv_set_option_string(ptr, "demuxer-readahead-secs", WindowsPlayerReadAheadSeconds)
        val ret = lib.mpv_initialize(ptr)
        if (ret < 0) {
            throw RuntimeException("Failed to initialize libmpv: error code $ret")
        }
        lib.mpv_request_log_messages(ptr, "warn")
        glProcAddressCallback = object : MpvOpenGlGetProcAddressCallback {
            override fun invoke(ctx: Pointer?, name: String?): Pointer? =
                name?.let(::resolveOpenGlProcAddress)
        }
        glInitParams = MpvOpenGlInitParams().apply {
            get_proc_address = glProcAddressCallback
            get_proc_address_ctx = null
            write()
        }
        val renderParams = createRenderParams(
            MPV_RENDER_PARAM_API_TYPE to cStringMemory(MPV_RENDER_API_TYPE_OPENGL),
            MPV_RENDER_PARAM_OPENGL_INIT_PARAMS to glInitParams?.pointer,
        )
        val renderContextRef = PointerByReference()
        val renderRet = lib.mpv_render_context_create(renderContextRef, ptr, renderParams[0].pointer)
        if (renderRet < 0) {
            throw RuntimeException("Failed to initialize libmpv render context: error code $renderRet")
        }
        val createdRenderContext = renderContextRef.value
            ?: throw RuntimeException("Failed to allocate libmpv render context")
        renderContext = createdRenderContext
        renderUpdateCallback = object : MpvRenderUpdateCallback {
            override fun invoke(ctx: Pointer?) {
                perfCollector?.recordRenderUpdateCallback()
                scheduleRender(force = false)
            }
        }
        lib.mpv_render_context_set_update_callback(createdRenderContext, renderUpdateCallback, null)
        lib.mpv_observe_property(ptr, 0, "pause", MPV_FORMAT_FLAG)
        lib.mpv_observe_property(ptr, 0, "paused-for-cache", MPV_FORMAT_FLAG)
        lib.mpv_observe_property(ptr, 0, "core-idle", MPV_FORMAT_FLAG)
        lib.mpv_observe_property(ptr, 0, "eof-reached", MPV_FORMAT_FLAG)
        lib.mpv_observe_property(ptr, 0, "seeking", MPV_FORMAT_FLAG)
        lib.mpv_observe_property(ptr, 0, "track-list/count", MPV_FORMAT_INT64)
        lib.mpv_observe_property(ptr, 0, "volume", MPV_FORMAT_DOUBLE)
        lib.mpv_observe_property(ptr, 0, "time-pos", MPV_FORMAT_DOUBLE)
        lib.mpv_observe_property(ptr, 0, "duration", MPV_FORMAT_DOUBLE)
        lib.mpv_observe_property(ptr, 0, "speed", MPV_FORMAT_DOUBLE)
        wakeupCallback = object : MpvWakeupCallback {
            override fun invoke(ctx: Pointer?) {
                readEvents()
            }
        }
        lib.mpv_set_wakeup_callback(ptr, wakeupCallback, null)
        state.playerPtr = ptr
        desktopPlayerTrace("embedded panel initialized with opengl render api glVendor=${drawable.gl.glGetString(GL.GL_VENDOR) ?: ""} glRenderer=${drawable.gl.glGetString(GL.GL_RENDERER) ?: ""}")
        SwingUtilities.invokeLater(onPlayerStateChanged)
        scheduleRender(force = true)
    }

    private fun readEvents() {
        val ptr = state.playerPtr ?: return
        val lib = WindowsMpvLibrary.INSTANCE
        var hasUpdates = false
        var eventCount = 0
        while (true) {
            val eventPtr = lib.mpv_wait_event(ptr, 0.0) ?: break
            val event = MpvEvent(eventPtr)
            event.read()
            when (event.event_id) {
                MPV_EVENT_NONE -> break
                MPV_EVENT_SHUTDOWN -> return
                MPV_EVENT_LOG_MESSAGE -> {
                    val message = event.data?.let { data ->
                        MpvLogMessage(data).apply { read() }
                    }
                    desktopPlayerTrace(
                        "mpv log level=${mpvLogText(message?.level)} " +
                            "prefix=${mpvLogText(message?.prefix)} " +
                            "text=${redactMpvLogText(mpvLogText(message?.text)).take(400)}"
                    )
                }
                MPV_EVENT_START_FILE,
                MPV_EVENT_END_FILE,
                -> desktopPlayerTrace("mpv event id=${event.event_id} error=${event.error}")
                MPV_EVENT_FILE_LOADED -> {
                    desktopPlayerTrace("mpv event id=${event.event_id} error=${event.error} applying subtitle style")
                    applyWindowsMpvSubtitleStyle(ptr, state.subtitleStyle)
                }
                else -> {
                    hasUpdates = true
                    eventCount += 1
                }
            }
        }
        if (hasUpdates) {
            perfCollector?.recordWakeup(eventCount)
            SwingUtilities.invokeLater(onPlayerStateChanged)
        }
    }

    private fun configureVideoPipeline() {
        val ptr = state.playerPtr ?: return
        val filterSpec = if (state.streamIsHdrLike || state.streamHasDolbyVision) {
            WindowsHdrCompatibilityFilter
        } else {
            ""
        }
        WindowsMpvLibrary.INSTANCE.mpv_set_property_string(ptr, "vf", filterSpec)
    }

    override fun loadFile(url: String, audioUrl: String?, headers: Map<String, String>) {
        val ptr = state.playerPtr ?: return
        val lib = WindowsMpvLibrary.INSTANCE
        val effectiveUrl = DesktopPlaybackUrlResolver.resolveUrlIfNeeded(url, headers)
        val effectiveAudioUrl = audioUrl?.let { DesktopPlaybackUrlResolver.resolveUrlIfNeeded(it, emptyMap()) }
        val effectiveHeaders = if (effectiveUrl != url) emptyMap() else headers
        val headersStr = effectiveHeaders.entries
            .joinToString(",") { "${it.key}: ${it.value.replace("\\", "\\\\").replace(",", "\\,")}" }
        desktopPlayerTrace(
            "embedded panel loadFile host=${playbackUrlForLog(url)} " +
                "effectiveHost=${playbackUrlForLog(effectiveUrl)} " +
                "audioHost=${playbackUrlForLog(audioUrl)} " +
                "effectiveAudioHost=${playbackUrlForLog(effectiveAudioUrl)} " +
                "headerKeys=${effectiveHeaders.keys.joinToString()} headerBlobLength=${headersStr.length}"
        )
        configureVideoPipeline()
        applyWindowsMpvSubtitleStyle(ptr, state.subtitleStyle)
        lib.mpv_set_property_string(ptr, "http-header-fields", headersStr)
        val loadResult = lib.mpv_command(ptr, arrayOf("loadfile", effectiveUrl, "replace", null))
        if (loadResult < 0) {
            desktopPlayerTrace("embedded panel loadFile failed commandResult=$loadResult")
        }
        if (!effectiveAudioUrl.isNullOrBlank()) {
            Timer().schedule(500) {
                SwingUtilities.invokeLater {
                    lib.mpv_command(ptr, arrayOf("audio-add", effectiveAudioUrl, "select", null))
                }
            }
        }
        reapplyWindowsMpvSubtitleStyleLater(state, ptr, state.subtitleStyle)
        requestFocusInWindow()
        glPanel.requestFocusInWindow()
        scheduleRender(force = true)
    }

    override fun loadSubtitleUrl(url: String) {
        val ptr = state.playerPtr ?: return
        WindowsMpvLibrary.INSTANCE.mpv_command(ptr, arrayOf("sub-add", url, "select", null))
        applyWindowsMpvSubtitleStyle(ptr, state.subtitleStyle)
        reapplyWindowsMpvSubtitleStyleLater(state, ptr, state.subtitleStyle)
        scheduleRender(force = true)
    }

    override fun applyResizeMode(resizeMode: PlayerResizeMode) {
        val ptr = state.playerPtr ?: return
        val lib = WindowsMpvLibrary.INSTANCE
        when (resizeMode) {
            PlayerResizeMode.Fit -> {
                lib.mpv_set_option_string(ptr, "panscan", "0.0")
                lib.mpv_set_option_string(ptr, "video-unscaled", "no")
            }
            PlayerResizeMode.Fill -> {
                lib.mpv_set_option_string(ptr, "panscan", "1.0")
                lib.mpv_set_option_string(ptr, "video-unscaled", "no")
            }
            PlayerResizeMode.Zoom -> {
                lib.mpv_set_option_string(ptr, "panscan", "0.0")
                lib.mpv_set_option_string(ptr, "video-unscaled", "downscale-big")
            }
        }
        scheduleRender(force = true)
    }

    override fun retryPlayback() {
        val ptr = state.playerPtr ?: return
        val lib = WindowsMpvLibrary.INSTANCE
        val sourceUrl = state.currentSourceUrl.takeIf { it.isNotBlank() } ?: return
        val pos = getDouble("time-pos")
        loadFile(sourceUrl, state.currentSourceAudioUrl, state.currentHeaders)
        Timer().schedule(500) {
            SwingUtilities.invokeLater {
                lib.mpv_command(ptr, arrayOf("seek", String.format("%.3f", pos), "absolute", null))
            }
        }
    }

    override fun clearExternalSubtitles() {
        val ptr = state.playerPtr ?: return
        val lib = WindowsMpvLibrary.INSTANCE
        val count = getString("track-list/count")?.toIntOrNull() ?: 0
        for (i in count - 1 downTo 0) {
            val type = getString("track-list/$i/type") ?: ""
            val external = getString("track-list/$i/external") == "yes"
            if (type == "sub" && external) {
                val id = getString("track-list/$i/id") ?: ""
                lib.mpv_command(ptr, arrayOf("sub-remove", id, null))
            }
        }
        lib.mpv_set_property_string(ptr, "sid", "no")
        scheduleRender(force = true)
    }

    override fun clearExternalSubtitlesAndSelect(trackIndex: Int) {
        clearExternalSubtitles()
        if (trackIndex >= 0 && trackIndex < state.subtitleTracks.size) {
            val track = state.subtitleTracks[trackIndex]
            val ptr = state.playerPtr ?: return
            WindowsMpvLibrary.INSTANCE.mpv_set_property_string(ptr, "sid", track.id)
        }
    }

    override fun toggleFullScreen() {
        val window = SwingUtilities.getWindowAncestor(this) ?: return
        if (fullscreenState.isActive(window)) {
            fullscreenState.exit(window)
            return
        }
        fullscreenState.enter(window)
    }

    override fun requestInteractionFocus() {
        requestFocusInWindow()
        glPanel.requestFocusInWindow()
    }

    override fun dispose() {
        if (playerDisposed) {
            return
        }
        playerDisposed = true
        desktopPlayerTrace("embedded panel dispose start displayable=${glPanel.isDisplayable}")
        fullscreenState.exit(SwingUtilities.getWindowAncestor(this))
        state.playerPtr?.let { ptr ->
            WindowsMpvLibrary.INSTANCE.mpv_set_wakeup_callback(ptr, null, null)
        }
        renderContext?.let { renderPtr ->
            WindowsMpvLibrary.INSTANCE.mpv_render_context_set_update_callback(renderPtr, null, null)
        }
        renderUpdateCallback = null
        wakeupCallback = null
        glProcAddressCallback = null
        if (glPanel.isDisplayable) {
            desktopPlayerTrace("embedded panel destroying gl panel")
            glPanel.destroy()
        } else {
            desktopPlayerTrace("embedded panel releasing resources without gl panel destroy")
            releasePlayerResources()
        }
    }

    private fun closePlayer() {
        dispose()
        onClose()
    }

    private fun releasePlayerResources() {
        val renderPtr = renderContext
        val playerPtr = state.playerPtr
        desktopPlayerTrace("embedded panel release resources render=${renderPtr != null} player=${playerPtr != null}")
        renderContext = null
        glInitParams = null
        state.playerPtr = null
        if (renderPtr != null) {
            WindowsMpvLibrary.INSTANCE.mpv_render_context_free(renderPtr)
        }
        if (playerPtr != null) {
            WindowsMpvLibrary.INSTANCE.mpv_terminate_destroy(playerPtr)
        }
    }

    private fun scheduleRender(force: Boolean) {
        if (playerDisposed) {
            return
        }
        synchronized(renderStateLock) {
            forceRenderRequested = forceRenderRequested || force
            if (renderScheduled) {
                return
            }
            renderScheduled = true
        }
        SwingUtilities.invokeLater {
            val forceNow = synchronized(renderStateLock) {
                renderScheduled = false
                val requested = forceRenderRequested
                forceRenderRequested = false
                requested
            }
            if (glPanel.isDisplayable) {
                if (forceNow) {
                    synchronized(renderStateLock) {
                        forceRenderRequested = true
                    }
                }
                glPanel.display()
            }
        }
    }

    private fun renderOnGlThread(drawable: GLAutoDrawable) {
        if (playerDisposed) {
            return
        }
        val renderPtr = renderContext ?: return
        val width = drawable.surfaceWidth.coerceAtLeast(1)
        val height = drawable.surfaceHeight.coerceAtLeast(1)
        val force = synchronized(renderStateLock) {
            val requested = forceRenderRequested
            forceRenderRequested = false
            requested
        }
        if (width <= 0 || height <= 0) {
            return
        }
        val lib = WindowsMpvLibrary.INSTANCE
        val shouldRender = force || (lib.mpv_render_context_update(renderPtr) and MPV_RENDER_UPDATE_FRAME) != 0L
        if (!shouldRender) {
            return
        }
        val framebufferBinding = IntArray(1)
        drawable.gl.glGetIntegerv(GL.GL_FRAMEBUFFER_BINDING, framebufferBinding, 0)
        val fbo = MpvOpenGlFbo().apply {
            this.fbo = framebufferBinding[0]
            this.w = width
            this.h = height
            this.internal_format = 0
            write()
        }
        val renderParams = createRenderParams(
            MPV_RENDER_PARAM_OPENGL_FBO to fbo.pointer,
            MPV_RENDER_PARAM_FLIP_Y to intMemory(1),
        )
        val renderRet = lib.mpv_render_context_render(renderPtr, renderParams[0].pointer)
        if (renderRet < 0) {
            desktopPlayerTrace("mpv opengl render failed code=$renderRet width=$width height=$height")
        }
        drawable.gl.glFlush()
    }

    private fun togglePlayPause() {
        val ptr = state.playerPtr ?: return
        val lib = WindowsMpvLibrary.INSTANCE
        val paused = lib.mpv_get_property_string(ptr, "pause")?.let { p ->
            val s = p.getString(0)
            lib.mpv_free(p)
            s == "yes"
        } ?: false
        lib.mpv_set_property_string(ptr, "pause", if (paused) "no" else "yes")
    }

    private fun seekByOffset(offsetMs: Long) {
        val ptr = state.playerPtr ?: return
        val seconds = offsetMs / 1000.0
        WindowsMpvLibrary.INSTANCE.mpv_command(ptr, arrayOf("seek", String.format("%.3f", seconds), "relative", null))
    }

    private fun adjustVolume(delta: Float) {
        setVolume((state.volumeLevel.fraction + delta).coerceIn(0f, 1f))
    }

    private fun setVolume(level: Float) {
        val ptr = state.playerPtr ?: return
        val clampedLevel = level.coerceIn(0f, 1f)
        val volumePercent = (clampedLevel * 100f).roundToInt()
        WindowsMpvLibrary.INSTANCE.mpv_set_property_string(ptr, "volume", volumePercent.toString())
        state.volumeLevel = PlayerAudioLevel(
            fraction = clampedLevel,
            isMuted = volumePercent == 0,
        )
    }

    private fun getDouble(name: String): Double {
        val ptr = state.playerPtr ?: return 0.0
        val lib = WindowsMpvLibrary.INSTANCE
        val p = lib.mpv_get_property_string(ptr, name)
        if (p != null) {
            val str = p.getString(0)
            lib.mpv_free(p)
            return str.toDoubleOrNull() ?: 0.0
        }
        return 0.0
    }

    private fun getString(name: String): String? {
        val ptr = state.playerPtr ?: return null
        val lib = WindowsMpvLibrary.INSTANCE
        val p = lib.mpv_get_property_string(ptr, name)
        if (p != null) {
            val str = p.getString(0)
            lib.mpv_free(p)
            return str
        }
        return null
    }
}

// Swing GUI Window implementation
internal class WindowsPlayerPanel(
    private val playerTheme: PlayerTheme,
    private val state: WindowsPlayerWindowState,
    private val onClose: () -> Unit,
    private val onPlayerStateChanged: () -> Unit,
    private val perfCollector: DesktopBackendPerfCollector? = null,
    private val onSurfaceInteraction: (Boolean) -> Unit = {},
    private val onAddonSubtitlesFetch: () -> Unit,
    private val onSourcesRequested: () -> Unit,
    private val onSourceSelected: (String) -> Unit,
    private val onSourceReload: () -> Unit,
    private val onEpisodesRequested: () -> Unit,
    private val onEpisodeSelected: (String) -> Unit,
    private val onEpisodeStreamSelected: (String) -> Unit,
    private val onNextEpisodeRequested: () -> Unit,
    private val onSubmitIntro: (String, Double, Double) -> Unit,
    private val showNativeControls: Boolean = true,
) : JPanel(BorderLayout()), WindowsPlaybackPanel {
    private val canvas = object : Canvas() {
        override fun addNotify() {
            super.addNotify()
            SwingUtilities.invokeLater {
                if (!mpvInitialized) {
                    mpvInitialized = true
                    initializeMpv()
                }
                onPlayerStateChanged()
            }
        }
    }

    // Controls Row 1
    private val skipIntroButton: DarkButton
    private val nextEpisodeButton: DarkButton

    // Controls Row 2
    private val seekSlider = JSlider(0, 1000, 0)

    // Controls Row 3
    private val playPauseButton: DarkButton
    private val timeLabel = JLabel("00:00 / 00:00")
    private val speedButton: DarkButton
    private val volumeButton: DarkButton
    private val audioButton: DarkButton
    private val subsButton: DarkButton
    private val sourcesButton: DarkButton
    private val episodesButton: DarkButton
    private val fullscreenButton: DarkButton
    private val exitButton: DarkButton

    private val controlPanel = JPanel()
    private val fullscreenState = FullscreenWindowState()
    private var lastMouseMovedTime = System.currentTimeMillis()
    private var isSeeking = false
    private var mpvInitialized = false
    private var wakeupCallback: MpvWakeupCallback? = null

    init {
        state.panelRef = this

        val defaultBg = playerTheme.buttonBgColor
        val accent = playerTheme.accentColor

        skipIntroButton = DarkButton("Skip Intro", defaultBg, accent)
        nextEpisodeButton = DarkButton("Next Episode", defaultBg)
        playPauseButton = DarkButton("▶", defaultBg)
        speedButton = DarkButton("1.0x", defaultBg)
        volumeButton = DarkButton("🔊 100%", defaultBg)
        audioButton = DarkButton("Audio", defaultBg)
        subsButton = DarkButton("Subs", defaultBg)
        sourcesButton = DarkButton("Sources", defaultBg)
        episodesButton = DarkButton("Episodes", defaultBg)
        fullscreenButton = DarkButton("⛶", defaultBg)
        exitButton = DarkButton("✕", defaultBg)

        setupPanel()
        if (showNativeControls) {
            setupControls()
        }
        setupListeners()

        if (showNativeControls) {
            val timer = javax.swing.Timer(250) {
                if (System.currentTimeMillis() - lastMouseMovedTime > 3000 && state.playerPtr != null) {
                    val lib = WindowsMpvLibrary.INSTANCE
                    val paused = lib.mpv_get_property_string(state.playerPtr!!, "pause")?.let { p ->
                        val s = p.getString(0)
                        lib.mpv_free(p)
                        s == "yes"
                    } ?: false
                    if (!paused) {
                        hideControls()
                    }
                }
            }
            timer.start()
        }
    }

    private fun setupPanel() {
        background = playerTheme.panelBgColor
        isFocusable = true
        focusTraversalKeysEnabled = false
        canvas.background = java.awt.Color.BLACK
        canvas.isFocusable = true
        canvas.focusTraversalKeysEnabled = false
        add(canvas, BorderLayout.CENTER)
    }

    private fun setupControls() {
        controlPanel.layout = BoxLayout(controlPanel, BoxLayout.Y_AXIS)
        controlPanel.background = playerTheme.controlBgColor
        controlPanel.border = BorderFactory.createEmptyBorder(8, 12, 8, 12)

        // Row 1: Overlays
        val row1 = JPanel(FlowLayout(FlowLayout.TRAILING, 10, 0))
        row1.isOpaque = false
        skipIntroButton.isVisible = false
        nextEpisodeButton.isVisible = false
        row1.add(skipIntroButton)
        row1.add(nextEpisodeButton)

        // Row 2: Progress Slider
        val row2 = JPanel(BorderLayout())
        row2.isOpaque = false
        seekSlider.isOpaque = false
        seekSlider.isFocusable = false
        seekSlider.background = playerTheme.controlBgColor.darker()
        seekSlider.foreground = playerTheme.accentColor
        seekSlider.setUI(ModernSliderUI(seekSlider, playerTheme.accentColor))
        row2.add(seekSlider, BorderLayout.CENTER)

        // Row 3: Action Buttons
        val row3 = JPanel(BorderLayout(10, 0))
        row3.isOpaque = false

        val leftPanel = JPanel(FlowLayout(FlowLayout.LEADING, 10, 0))
        leftPanel.isOpaque = false
        val seekBackBtn = DarkButton("⏪ 10s", playerTheme.buttonBgColor)
        val seekForwardBtn = DarkButton("10s ⏩", playerTheme.buttonBgColor)

        timeLabel.foreground = playerTheme.textMutedColor
        timeLabel.font = Font("Segoe UI", Font.PLAIN, 13)

        leftPanel.add(playPauseButton)
        leftPanel.add(seekBackBtn)
        leftPanel.add(seekForwardBtn)
        leftPanel.add(timeLabel)

        val rightPanel = JPanel(FlowLayout(FlowLayout.TRAILING, 8, 0))
        rightPanel.isOpaque = false

        rightPanel.add(speedButton)
        rightPanel.add(volumeButton)
        rightPanel.add(audioButton)
        rightPanel.add(subsButton)
        rightPanel.add(sourcesButton)
        rightPanel.add(episodesButton)
        rightPanel.add(fullscreenButton)
        rightPanel.add(exitButton)

        row3.add(leftPanel, BorderLayout.WEST)
        row3.add(rightPanel, BorderLayout.EAST)

        controlPanel.add(row1)
        controlPanel.add(Box.createVerticalStrut(4))
        controlPanel.add(row2)
        controlPanel.add(Box.createVerticalStrut(4))
        controlPanel.add(row3)

        add(controlPanel, BorderLayout.SOUTH)
        revalidate()

        // Seek action handlers
        seekBackBtn.addActionListener { seekByOffset(-10000) }
        seekForwardBtn.addActionListener { seekByOffset(10000) }
        playPauseButton.addActionListener { togglePlayPause() }
        speedButton.addActionListener { toggleSpeed() }
        fullscreenButton.addActionListener { toggleFullScreen() }
        exitButton.addActionListener { closePlayer() }
        skipIntroButton.addActionListener {
            val endTime = state.skipIntroEndTimeMs
            if (endTime != null) {
                seekToPosition(endTime)
            }
        }
        nextEpisodeButton.addActionListener { onNextEpisodeRequested() }
        volumeButton.addActionListener { toggleMute() }
    }

    private fun setupListeners() {
        val interactionListener = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                lastMouseMovedTime = System.currentTimeMillis()
                onSurfaceInteraction(false)
                showControls()
            }
            override fun mouseClicked(e: MouseEvent) {
                lastMouseMovedTime = System.currentTimeMillis()
                onSurfaceInteraction(true)
                showControls()
                canvas.requestFocusInWindow()
                if (e.clickCount == 2) {
                    toggleFullScreen()
                }
            }
        }

        canvas.addMouseListener(interactionListener)
        canvas.addMouseMotionListener(interactionListener)
        addMouseListener(interactionListener)
        addMouseMotionListener(interactionListener)

        val keyListener = object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                lastMouseMovedTime = System.currentTimeMillis()
                showControls()
                when (e.keyCode) {
                    KeyEvent.VK_SPACE -> togglePlayPause()
                    KeyEvent.VK_LEFT -> seekByOffset(-10000)
                    KeyEvent.VK_RIGHT -> seekByOffset(10000)
                    KeyEvent.VK_UP -> adjustVolume(5.0)
                    KeyEvent.VK_DOWN -> adjustVolume(-5.0)
                    KeyEvent.VK_F -> toggleFullScreen()
                    KeyEvent.VK_ESCAPE -> closePlayer()
                }
            }
        }

        canvas.addKeyListener(keyListener)
        addKeyListener(keyListener)

        // Mouse Wheel volume control
        val wheelListener = MouseWheelListener { e ->
            lastMouseMovedTime = System.currentTimeMillis()
            showControls()
            adjustVolume(-e.wheelRotation * 2.0)
        }
        canvas.addMouseWheelListener(wheelListener)
        addMouseWheelListener(wheelListener)

        // Slider scrubbing support
        seekSlider.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                isSeeking = true
            }
            override fun mouseReleased(e: MouseEvent) {
                isSeeking = false
                val percent = seekSlider.value / 1000.0
                val duration = getDouble("duration")
                seekToPosition((percent * duration * 1000).toLong())
            }
        })

        sourcesButton.addActionListener { e ->
            onSourcesRequested()
            val menu = JPopupMenu()
            menu.styleDark()
            if (state.sourcesLoading) {
                val item = JMenuItem("Loading sources...")
                item.isEnabled = false
                item.styleDark()
                menu.add(item)
            } else if (state.sourceStreams.isEmpty()) {
                val item = JMenuItem("No sources found")
                item.isEnabled = false
                item.styleDark()
                menu.add(item)
            } else {
                state.sourceStreams.forEach { stream ->
                    val label = "[${stream.addonName}] ${stream.streamLabel} ${stream.streamSubtitle ?: ""}"
                    val item = JCheckBoxMenuItem(label, stream.directPlaybackUrl == state.currentSourceStreamUrl)
                    item.addActionListener {
                        stream.directPlaybackUrl?.let { url -> onSourceSelected(url) }
                    }
                    item.styleDark()
                    menu.add(item)
                }
            }
            menu.show(sourcesButton, 0, -menu.preferredSize.height)
        }

        episodesButton.addActionListener { e ->
            onEpisodesRequested()
            val menu = JPopupMenu()
            menu.styleDark()
            val list = JList(state.episodes.map { "S${it.season ?: 0}E${it.episode ?: 0} - ${it.title}" }.toTypedArray())
            list.background = playerTheme.buttonBgColor
            list.foreground = java.awt.Color.WHITE
            list.selectionBackground = playerTheme.accentColor
            list.selectionForeground = java.awt.Color.WHITE
            list.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(evt: MouseEvent) {
                    if (evt.clickCount == 2) {
                        val idx = list.selectedIndex
                        if (idx in state.episodes.indices) {
                            onEpisodeSelected(state.episodes[idx].id)
                            menu.isVisible = false
                        }
                    }
                }
            })
            val scroll = JScrollPane(list)
            scroll.background = playerTheme.buttonBgColor
            scroll.border = BorderFactory.createEmptyBorder()
            scroll.preferredSize = Dimension(300, 300)
            menu.add(scroll)
            menu.show(episodesButton, 0, -menu.preferredSize.height)
        }

        audioButton.addActionListener { e ->
            val menu = JPopupMenu()
            menu.styleDark()
            if (state.audioTracks.isEmpty()) {
                val item = JMenuItem("No audio tracks")
                item.isEnabled = false
                item.styleDark()
                menu.add(item)
            } else {
                state.audioTracks.forEach { track ->
                    val label = "${track.label.ifBlank { "Track " + (track.index + 1) }} [${track.language ?: "unknown"}]"
                    val item = JCheckBoxMenuItem(label, track.isSelected)
                    item.addActionListener {
                        selectAudio(track.index)
                    }
                    item.styleDark()
                    menu.add(item)
                }
            }
            menu.show(audioButton, 0, -menu.preferredSize.height)
        }

        subsButton.addActionListener { e ->
            val menu = JPopupMenu()
            menu.styleDark()

            // Disable item
            val noSubItem = JCheckBoxMenuItem("None", state.subtitleTracks.none { it.isSelected } && state.addonSubtitles.none { it.isSelected })
            noSubItem.addActionListener {
                selectSubtitle(-1)
            }
            noSubItem.styleDark()
            menu.add(noSubItem)
            menu.addDarkSeparator()

            // Built-in subtitles
            if (state.subtitleTracks.isNotEmpty()) {
                val builtInMenu = JMenu("Built-in Subtitles")
                builtInMenu.styleDark()
                state.subtitleTracks.forEach { track ->
                    val label = "${track.label.ifBlank { "Track " + (track.index + 1) }} [${track.language ?: "unknown"}]"
                    val item = JCheckBoxMenuItem(label, track.isSelected)
                    item.addActionListener {
                        selectSubtitle(track.index)
                    }
                    item.styleDark()
                    builtInMenu.add(item)
                }
                menu.add(builtInMenu)
            }

            // Addon subtitles
            onAddonSubtitlesFetch()
            if (state.addonSubtitlesLoading) {
                val loadingItem = JMenuItem("Loading addons...")
                loadingItem.isEnabled = false
                loadingItem.styleDark()
                menu.add(loadingItem)
            } else if (state.addonSubtitles.isNotEmpty()) {
                val addonMenu = JMenu("Addon Subtitles")
                addonMenu.styleDark()
                state.addonSubtitles.forEach { sub ->
                    val item = JCheckBoxMenuItem(sub.display, sub.isSelected)
                    item.addActionListener {
                        // Load addon sub url
                        state.panelRef?.loadSubtitleUrl(sub.url)
                    }
                    item.styleDark()
                    addonMenu.add(item)
                }
                menu.add(addonMenu)
            }

            // Style Customizer
            menu.addDarkSeparator()
            val styleMenu = JMenu("Subtitle Style")
            styleMenu.styleDark()

            val colorMenu = JMenu("Color")
            colorMenu.styleDark()
            listOf("White" to ComposeColor.White, "Yellow" to ComposeColor(0xFFFFD700), "Cyan" to ComposeColor(0xFF00E5FF), "Red" to ComposeColor(0xFFFF5C5C), "Green" to ComposeColor(0xFF00FF88)).forEach { (name, color) ->
                val item = JCheckBoxMenuItem(name, state.subtitleStyle.textColor == color)
                item.addActionListener {
                    applySubStyle(state.subtitleStyle.copy(textColor = color))
                }
                item.styleDark()
                colorMenu.add(item)
            }
            styleMenu.add(colorMenu)

            val outlineItem = JCheckBoxMenuItem("Outline", state.subtitleStyle.outlineEnabled)
            outlineItem.addActionListener {
                applySubStyle(state.subtitleStyle.copy(outlineEnabled = !state.subtitleStyle.outlineEnabled))
            }
            outlineItem.styleDark()
            styleMenu.add(outlineItem)

            val sizeMenu = JMenu("Font Size")
            sizeMenu.styleDark()
            listOf(14, 18, 22, 26, 30).forEach { size ->
                val item = JCheckBoxMenuItem("${size}sp", state.subtitleStyle.fontSizeSp == size)
                item.addActionListener {
                    applySubStyle(state.subtitleStyle.copy(fontSizeSp = size))
                }
                item.styleDark()
                sizeMenu.add(item)
            }
            styleMenu.add(sizeMenu)

            val offsetMenu = JMenu("Bottom Offset")
            offsetMenu.styleDark()
            listOf(10, 20, 30, 40).forEach { offset ->
                val item = JCheckBoxMenuItem("${offset}%", state.subtitleStyle.bottomOffset == offset)
                item.addActionListener {
                    applySubStyle(state.subtitleStyle.copy(bottomOffset = offset))
                }
                item.styleDark()
                offsetMenu.add(item)
            }
            styleMenu.add(offsetMenu)

            menu.add(styleMenu)
            menu.show(subsButton, 0, -menu.preferredSize.height)
        }
    }

    private fun initializeMpv() {
        val lib = WindowsMpvLibrary.INSTANCE
        val ptr = lib.mpv_create() ?: throw RuntimeException("Failed to create libmpv instance")
        state.playerPtr = ptr

        val hwnd = Native.getComponentPointer(canvas)
        val hwndLong = Pointer.nativeValue(hwnd)

        lib.mpv_set_option_string(ptr, "wid", hwndLong.toString())
        lib.mpv_set_option_string(ptr, "vo", "gpu")
        lib.mpv_set_option_string(ptr, "gpu-api", "d3d11")
        lib.mpv_set_option_string(ptr, "input-media-keys", "yes")
        lib.mpv_set_option_string(ptr, "subs-match-os-language", "yes")
        lib.mpv_set_option_string(ptr, "subs-fallback", "yes")
        lib.mpv_set_option_string(ptr, "sub-ass-override", "force")
        lib.mpv_set_option_string(ptr, "hwdec", "auto-copy-safe")
        lib.mpv_set_option_string(ptr, "target-colorspace-hint", "yes")
        lib.mpv_set_option_string(ptr, "hdr-compute-peak", "yes")
        lib.mpv_set_option_string(ptr, "tone-mapping", "auto")
        lib.mpv_set_option_string(ptr, "keep-open", "yes")
        lib.mpv_set_option_string(ptr, "cache", "yes")
        lib.mpv_set_option_string(ptr, "cache-pause", "yes")
        lib.mpv_set_option_string(ptr, "cache-pause-wait", WindowsPlayerCachePauseWaitSeconds)
        lib.mpv_set_option_string(ptr, "demuxer-max-bytes", WindowsPlayerMaxCacheBytes)
        lib.mpv_set_option_string(ptr, "demuxer-max-back-bytes", WindowsPlayerMaxBackCacheBytes)
        lib.mpv_set_option_string(ptr, "demuxer-readahead-secs", WindowsPlayerReadAheadSeconds)

        val ret = lib.mpv_initialize(ptr)
        if (ret < 0) {
            throw RuntimeException("Failed to initialize libmpv: error code $ret")
        }
        lib.mpv_request_log_messages(ptr, "warn")
        lib.mpv_observe_property(ptr, 0, "pause", MPV_FORMAT_FLAG)
        lib.mpv_observe_property(ptr, 0, "paused-for-cache", MPV_FORMAT_FLAG)
        lib.mpv_observe_property(ptr, 0, "core-idle", MPV_FORMAT_FLAG)
        lib.mpv_observe_property(ptr, 0, "eof-reached", MPV_FORMAT_FLAG)
        lib.mpv_observe_property(ptr, 0, "seeking", MPV_FORMAT_FLAG)
        lib.mpv_observe_property(ptr, 0, "track-list/count", MPV_FORMAT_INT64)
        lib.mpv_observe_property(ptr, 0, "volume", MPV_FORMAT_DOUBLE)
        lib.mpv_observe_property(ptr, 0, "time-pos", MPV_FORMAT_DOUBLE)
        lib.mpv_observe_property(ptr, 0, "duration", MPV_FORMAT_DOUBLE)
        lib.mpv_observe_property(ptr, 0, "speed", MPV_FORMAT_DOUBLE)
        wakeupCallback = object : MpvWakeupCallback {
            override fun invoke(ctx: Pointer?) {
                readEvents()
            }
        }
        lib.mpv_set_wakeup_callback(ptr, wakeupCallback, null)
    }

    override fun loadFile(url: String, audioUrl: String?, headers: Map<String, String>) {
        val ptr = state.playerPtr ?: return
        val lib = WindowsMpvLibrary.INSTANCE

        // Headers formatting
        val headersStr = headers.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() && !it.key.equals("Range", ignoreCase = true) }
            .joinToString(",") { "${it.key}: ${it.value.replace("\\", "\\\\").replace(",", "\\,")}" }

        lib.mpv_set_property_string(ptr, "http-header-fields", headersStr)
        desktopPlayerTrace(
            "native panel loadFile host=${playbackUrlForLog(url)} " +
                "audioHost=${playbackUrlForLog(audioUrl)} headerKeys=${headers.keys.joinToString()}"
        )
        applyWindowsMpvSubtitleStyle(ptr, state.subtitleStyle)
        val loadResult = lib.mpv_command(ptr, arrayOf("loadfile", url, "replace", null))
        if (loadResult < 0) {
            desktopPlayerTrace("native panel loadFile failed commandResult=$loadResult")
        }

        if (!audioUrl.isNullOrBlank()) {
            Timer().schedule(500) {
                SwingUtilities.invokeLater {
                    lib.mpv_command(ptr, arrayOf("audio-add", audioUrl, "select", null))
                }
            }
        }
        reapplyWindowsMpvSubtitleStyleLater(state, ptr, state.subtitleStyle)
    }

    fun updatePlaybackState(
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
        isLoading: Boolean,
        speed: Float
    ) {
        if (!isSeeking) {
            if (durationMs > 0) {
                seekSlider.value = ((positionMs.toDouble() / durationMs.toDouble()) * 1000).toInt()
            } else {
                seekSlider.value = 0
            }
            timeLabel.text = "${formatTime(positionMs)} / ${formatTime(durationMs)}"
        }
        playPauseButton.text = if (isPlaying) "⏸" else "▶"
        speedButton.text = "${speed}x"
        val vol = getDouble("volume").toInt()
        volumeButton.text = "🔊 $vol%"
    }

    override fun applyResizeMode(resizeMode: PlayerResizeMode) {
        val ptr = state.playerPtr ?: return
        val lib = WindowsMpvLibrary.INSTANCE
        when (resizeMode) {
            PlayerResizeMode.Fit -> {
                lib.mpv_set_option_string(ptr, "panscan", "0.0")
                lib.mpv_set_option_string(ptr, "video-unscaled", "no")
            }
            PlayerResizeMode.Fill -> {
                lib.mpv_set_option_string(ptr, "panscan", "1.0")
                lib.mpv_set_option_string(ptr, "video-unscaled", "no")
            }
            PlayerResizeMode.Zoom -> {
                lib.mpv_set_option_string(ptr, "panscan", "0.0")
                lib.mpv_set_option_string(ptr, "video-unscaled", "downscale-big")
            }
        }
    }

    fun updateSkipIntroButtonVisibility(visible: Boolean) {
        skipIntroButton.isVisible = visible
        if (showNativeControls) {
            controlPanel.revalidate()
        }
    }

    fun updateNextEpisodeButtonVisibility(visible: Boolean) {
        nextEpisodeButton.isVisible = visible
        if (showNativeControls) {
            controlPanel.revalidate()
        }
    }

    override fun retryPlayback() {
        val ptr = state.playerPtr ?: return
        val lib = WindowsMpvLibrary.INSTANCE
        val path = lib.mpv_get_property_string(ptr, "path")
        if (path != null) {
            val pathStr = path.getString(0)
            lib.mpv_free(path)
            val pos = getDouble("time-pos")
            loadFile(pathStr, state.currentSourceAudioUrl, state.currentHeaders)
            Timer().schedule(500) {
                SwingUtilities.invokeLater {
                    lib.mpv_command(ptr, arrayOf("seek", String.format("%.3f", pos), "absolute", null))
                }
            }
        }
    }

    override fun clearExternalSubtitles() {
        val ptr = state.playerPtr ?: return
        val lib = WindowsMpvLibrary.INSTANCE
        val count = getString("track-list/count")?.toIntOrNull() ?: 0
        for (i in count - 1 downTo 0) {
            val type = getString("track-list/$i/type") ?: ""
            val external = getString("track-list/$i/external") == "yes"
            if (type == "sub" && external) {
                val id = getString("track-list/$i/id") ?: ""
                lib.mpv_command(ptr, arrayOf("sub-remove", id, null))
            }
        }
        lib.mpv_set_property_string(ptr, "sid", "no")
    }

    override fun clearExternalSubtitlesAndSelect(trackIndex: Int) {
        clearExternalSubtitles()
        if (trackIndex >= 0 && trackIndex < state.subtitleTracks.size) {
            val track = state.subtitleTracks[trackIndex]
            val ptr = state.playerPtr ?: return
            WindowsMpvLibrary.INSTANCE.mpv_set_property_string(ptr, "sid", track.id)
        }
    }

    override fun loadSubtitleUrl(url: String) {
        val ptr = state.playerPtr ?: return
        WindowsMpvLibrary.INSTANCE.mpv_command(ptr, arrayOf("sub-add", url, "select", null))
        applyWindowsMpvSubtitleStyle(ptr, state.subtitleStyle)
        reapplyWindowsMpvSubtitleStyleLater(state, ptr, state.subtitleStyle)
    }

    private fun selectAudio(idx: Int) {
        if (idx in state.audioTracks.indices) {
            val track = state.audioTracks[idx]
            val ptr = state.playerPtr ?: return
            WindowsMpvLibrary.INSTANCE.mpv_set_property_string(ptr, "aid", track.id)
        }
    }

    private fun selectSubtitle(idx: Int) {
        val ptr = state.playerPtr ?: return
        if (idx < 0) {
            WindowsMpvLibrary.INSTANCE.mpv_set_property_string(ptr, "sid", "no")
        } else if (idx in state.subtitleTracks.indices) {
            val track = state.subtitleTracks[idx]
            WindowsMpvLibrary.INSTANCE.mpv_set_property_string(ptr, "sid", track.id)
        }
    }

    private fun applySubStyle(style: SubtitleStyleState) {
        state.subtitleStyle = style
        val ptr = state.playerPtr ?: return
        applyWindowsMpvSubtitleStyle(ptr, style)
    }

    private fun togglePlayPause() {
        val ptr = state.playerPtr ?: return
        val lib = WindowsMpvLibrary.INSTANCE
        val paused = lib.mpv_get_property_string(ptr, "pause")?.let { p ->
            val s = p.getString(0)
            lib.mpv_free(p)
            s == "yes"
        } ?: false
        lib.mpv_set_property_string(ptr, "pause", if (paused) "no" else "yes")
    }

    private fun toggleSpeed() {
        val ptr = state.playerPtr ?: return
        val lib = WindowsMpvLibrary.INSTANCE
        val speeds = listOf(1.0, 1.25, 1.5, 1.75, 2.0)
        val currentSpeed = getDouble("speed")
        val nextSpeed = speeds.firstOrNull { it > currentSpeed } ?: 1.0
        lib.mpv_set_property_string(ptr, "speed", nextSpeed.toString())
    }

    private fun adjustVolume(delta: Double) {
        val ptr = state.playerPtr ?: return
        val lib = WindowsMpvLibrary.INSTANCE
        val currentVol = getDouble("volume")
        val newVol = (currentVol + delta).coerceIn(0.0, 100.0)
        lib.mpv_set_property_string(ptr, "volume", newVol.toString())
    }

    private var previousVolume = 100.0
    private fun toggleMute() {
        val ptr = state.playerPtr ?: return
        val lib = WindowsMpvLibrary.INSTANCE
        val currentVol = getDouble("volume")
        if (currentVol > 0) {
            previousVolume = currentVol
            lib.mpv_set_property_string(ptr, "volume", "0")
        } else {
            lib.mpv_set_property_string(ptr, "volume", previousVolume.toString())
        }
    }

    private fun seekByOffset(offsetMs: Long) {
        val ptr = state.playerPtr ?: return
        val seconds = offsetMs / 1000.0
        WindowsMpvLibrary.INSTANCE.mpv_command(ptr, arrayOf("seek", String.format("%.3f", seconds), "relative", null))
    }

    private fun seekToPosition(positionMs: Long) {
        val ptr = state.playerPtr ?: return
        val seconds = positionMs / 1000.0
        WindowsMpvLibrary.INSTANCE.mpv_command(ptr, arrayOf("seek", String.format("%.3f", seconds), "absolute", null))
    }

    override fun toggleFullScreen() {
        val w = SwingUtilities.getWindowAncestor(this) as? Frame ?: return
        if (fullscreenState.isActive(w)) {
            fullscreenState.exit(w)
        } else {
            fullscreenState.enter(w)
        }
    }

    override fun requestInteractionFocus() {
        requestFocusInWindow()
        canvas.requestFocusInWindow()
    }

    private fun showControls() {
        if (!showNativeControls) {
            return
        }
        if (!controlPanel.isVisible) {
            controlPanel.isVisible = true
            val w = SwingUtilities.getWindowAncestor(this)
            w?.cursor = Cursor.getDefaultCursor()
            revalidate()
        }
    }

    private fun hideControls() {
        if (!showNativeControls) {
            return
        }
        if (controlPanel.isVisible) {
            controlPanel.isVisible = false
            val cursorImage = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
            val blankCursor = Toolkit.getDefaultToolkit().createCustomCursor(cursorImage, Point(0, 0), "blank cursor")
            val w = SwingUtilities.getWindowAncestor(this)
            w?.cursor = blankCursor
            revalidate()
        }
    }

    override fun dispose() {
        val ptr = state.playerPtr
        state.playerPtr = null
        fullscreenState.exit(SwingUtilities.getWindowAncestor(this))
        if (ptr != null) {
            WindowsMpvLibrary.INSTANCE.mpv_set_wakeup_callback(ptr, null, null)
            WindowsMpvLibrary.INSTANCE.mpv_terminate_destroy(ptr)
        }
        wakeupCallback = null
        val w = SwingUtilities.getWindowAncestor(this)
        if (w != null) {
            w.cursor = Cursor.getDefaultCursor()
        }
    }

    private fun readEvents() {
        val ptr = state.playerPtr ?: return
        val lib = WindowsMpvLibrary.INSTANCE
        var hasUpdates = false
        var eventCount = 0
        while (true) {
            val eventPtr = lib.mpv_wait_event(ptr, 0.0) ?: break
            val event = MpvEvent(eventPtr)
            event.read()
            when (event.event_id) {
                MPV_EVENT_NONE -> break
                MPV_EVENT_SHUTDOWN -> return
                MPV_EVENT_LOG_MESSAGE -> {
                    val message = event.data?.let { data ->
                        MpvLogMessage(data).apply { read() }
                    }
                    desktopPlayerTrace(
                        "mpv log level=${mpvLogText(message?.level)} " +
                            "prefix=${mpvLogText(message?.prefix)} " +
                            "text=${redactMpvLogText(mpvLogText(message?.text)).take(400)}"
                    )
                }
                MPV_EVENT_START_FILE,
                MPV_EVENT_END_FILE,
                -> desktopPlayerTrace("mpv event id=${event.event_id} error=${event.error}")
                MPV_EVENT_FILE_LOADED -> {
                    desktopPlayerTrace("mpv event id=${event.event_id} error=${event.error} applying subtitle style")
                    applyWindowsMpvSubtitleStyle(ptr, state.subtitleStyle)
                }
                else -> {
                    hasUpdates = true
                    eventCount += 1
                }
            }
        }
        if (hasUpdates) {
            perfCollector?.recordWakeup(eventCount)
            SwingUtilities.invokeLater(onPlayerStateChanged)
        }
    }

    private fun closePlayer() {
        dispose()
        onClose()
    }

    private fun getDouble(name: String): Double {
        val ptr = state.playerPtr ?: return 0.0
        val lib = WindowsMpvLibrary.INSTANCE
        val p = lib.mpv_get_property_string(ptr, name)
        if (p != null) {
            val str = p.getString(0)
            lib.mpv_free(p)
            return str.toDoubleOrNull() ?: 0.0
        }
        return 0.0
    }

    private fun getString(name: String): String? {
        val ptr = state.playerPtr ?: return null
        val lib = WindowsMpvLibrary.INSTANCE
        val p = lib.mpv_get_property_string(ptr, name)
        if (p != null) {
            val str = p.getString(0)
            lib.mpv_free(p)
            return str
        }
        return null
    }

    private fun formatTime(ms: Long): String {
        val totalSecs = ms / 1000
        val hours = totalSecs / 3600
        val mins = (totalSecs % 3600) / 60
        val secs = totalSecs % 60
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, mins, secs)
        } else {
            String.format("%02d:%02d", mins, secs)
        }
    }

    private fun JPopupMenu.styleDark() {
        background = playerTheme.buttonBgColor
        border = BorderFactory.createLineBorder(playerTheme.accentColor, 1)
    }

    private fun JMenuItem.styleDark() {
        background = playerTheme.buttonBgColor
        foreground = java.awt.Color.WHITE
        font = Font("Segoe UI", Font.PLAIN, 13)
        isOpaque = true
        border = BorderFactory.createEmptyBorder(6, 12, 6, 12)
    }

    private fun JMenu.styleDark() {
        background = playerTheme.buttonBgColor
        foreground = java.awt.Color.WHITE
        font = Font("Segoe UI", Font.PLAIN, 13)
        isOpaque = true
        border = BorderFactory.createEmptyBorder(6, 12, 6, 12)
        popupMenu.styleDark()
    }

    private fun JPopupMenu.addDarkSeparator() {
        val sep = JPopupMenu.Separator()
        sep.background = playerTheme.controlBgColor
        sep.foreground = playerTheme.controlBgColor
        add(sep)
    }
}

internal class DarkButton(
    text: String,
    private val defaultBg: java.awt.Color,
    var accentColor: java.awt.Color? = null
) : JButton(text) {
    private var isHovered = false
    private var isPressed = false

    init {
        isFocusable = false
        foreground = java.awt.Color.WHITE
        font = Font("Segoe UI", Font.PLAIN, 13)
        isContentAreaFilled = false
        isBorderPainted = false
        border = BorderFactory.createEmptyBorder(6, 12, 6, 12)

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                isHovered = true
                repaint()
            }
            override fun mouseExited(e: MouseEvent) {
                isHovered = false
                isPressed = false
                repaint()
            }
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    isPressed = true
                    repaint()
                }
            }
            override fun mouseReleased(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    isPressed = false
                    repaint()
                }
            }
        })
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val bg = when {
            !isEnabled -> defaultBg.darker()
            isPressed -> accentColor?.darker() ?: defaultBg.darker()
            isHovered -> accentColor?.brighter() ?: defaultBg.brighter()
            else -> accentColor ?: defaultBg
        }
        g2.color = bg
        g2.fillRoundRect(0, 0, width, height, 8, 8)

        // Subtle outline border
        g2.color = java.awt.Color(255, 255, 255, 20)
        g2.drawRoundRect(0, 0, width - 1, height - 1, 8, 8)

        g2.dispose()
        super.paintComponent(g)
    }
}

private class ModernSliderUI(
    slider: JSlider,
    private val accentColor: java.awt.Color
) : javax.swing.plaf.basic.BasicSliderUI(slider) {
    override fun paintTrack(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val trackHeight = 6
        val y = trackRect.y + (trackRect.height - trackHeight) / 2

        // Draw track background
        g2.color = slider.background
        g2.fillRoundRect(trackRect.x, y, trackRect.width, trackHeight, 3, 3)

        // Draw track fill (progress)
        val valueX = thumbRect.x + thumbRect.width / 2
        g2.color = if (slider.isEnabled) accentColor else java.awt.Color(100, 100, 100)
        g2.fillRoundRect(trackRect.x, y, valueX - trackRect.x, trackHeight, 3, 3)

        g2.dispose()
    }

    override fun paintThumb(g: Graphics) {
        if (!slider.isEnabled) return
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val size = 12
        val x = thumbRect.x + (thumbRect.width - size) / 2
        val y = thumbRect.y + (thumbRect.height - size) / 2

        // Draw white thumb circle
        g2.color = java.awt.Color.WHITE
        g2.fillOval(x, y, size, size)

        // Outer subtle border
        g2.color = java.awt.Color(0, 0, 0, 100)
        g2.drawOval(x, y, size, size)

        g2.dispose()
    }

    override fun paintFocus(g: Graphics) {}

    override fun getThumbSize(): Dimension {
        return Dimension(16, 16)
    }

    override fun createTrackListener(slider: JSlider): TrackListener {
        return object : TrackListener() {
            override fun mousePressed(e: MouseEvent) {
                if (slider.isEnabled) {
                    val value = valueForXPosition(e.x)
                    slider.setValue(value)
                    super.mousePressed(e)
                }
            }
        }
    }
}

private fun applyWindowsMpvSubtitleStyle(ptr: Pointer, style: SubtitleStyleState) {
    val lib = WindowsMpvLibrary.INSTANCE
    val properties = listOf(
        "sub-ass-override" to style.toMpvOverrideMode(),
        "sub-color" to style.textColor.toMpvArgbColor(),
        "sub-back-color" to style.backgroundColor.toMpvArgbColor(),
        "sub-outline-color" to style.outlineColor.toMpvArgbColor(),
        "sub-border-style" to style.toMpvBorderStyle(),
        "sub-outline-size" to style.toMpvOutlineSize(),
        "sub-font-size" to style.fontSizeSp.toString(),
        "sub-bold" to if (style.bold) "yes" else "no",
        "sub-pos" to style.toMpvPosition(),
    )
    desktopPlayerTrace(
        "applying subtitle style text=${style.textColor.toMpvArgbColor()} " +
            "background=${style.backgroundColor.toMpvArgbColor()} " +
            "outline=${style.outlineColor.toMpvArgbColor()} " +
            "outlineSize=${style.toMpvOutlineSize()} fontSize=${style.fontSizeSp} " +
            "bold=${style.bold} position=${style.toMpvPosition()} override=${style.toMpvOverrideMode()}"
    )
    properties.forEach { (name, value) ->
        val result = lib.mpv_set_property_string(ptr, name, value)
        if (result < 0) {
            desktopPlayerTrace("subtitle style property rejected name=$name value=$value result=$result")
        }
    }
}

private fun reapplyWindowsMpvSubtitleStyleLater(
    state: WindowsPlayerWindowState,
    ptr: Pointer,
    style: SubtitleStyleState,
) {
    Timer().schedule(300) {
        SwingUtilities.invokeLater {
            if (!state.isClosed && state.playerPtr == ptr) {
                applyWindowsMpvSubtitleStyle(ptr, style)
            }
        }
    }
}
