package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.RenderSettings
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Typography
import co.touchlab.kermit.Logger
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.LocalAppTheme
import com.nuvio.app.core.ui.LocalNuvioThemeTokens
import com.nuvio.app.core.ui.LocalNuvioTypeScale
import com.nuvio.app.core.ui.NuvioThemeTokens
import com.nuvio.app.core.ui.NuvioTypeScale
import com.nuvio.app.core.ui.appTheme
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioTypeScale
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import androidx.compose.runtime.CompositionLocalProvider
import java.awt.*
import java.awt.event.*
import java.awt.image.BufferedImage
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.swing.*
import javax.swing.plaf.basic.BasicSliderUI
import kotlin.concurrent.withLock
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

private fun playbackMetadataForLog(value: String?): String =
    value.orEmpty()
        .replace(Regex("[\\r\\n]+"), " ")
        .trim()
        .take(240)

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
    private var renderFrames = 0L
    private var renderDurationNsTotal = 0L
    private var renderQueueDelayNsTotal = 0L
    private var renderDurationNsMax = 0L
    private var renderQueueDelayNsMax = 0L

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
    fun recordRenderFrame(durationNs: Long, queueDelayNs: Long) {
        renderFrames += 1L
        renderDurationNsTotal += durationNs
        renderQueueDelayNsTotal += queueDelayNs
        renderDurationNsMax = maxOf(renderDurationNsMax, durationNs)
        renderQueueDelayNsMax = maxOf(renderQueueDelayNsMax, queueDelayNs)
        maybeLog()
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
        val averageRenderMs = if (renderFrames > 0L) {
            (renderDurationNsTotal.toDouble() / renderFrames.toDouble()) / 1_000_000.0
        } else {
            0.0
        }
        val averageRenderQueueMs = if (renderFrames > 0L) {
            (renderQueueDelayNsTotal.toDouble() / renderFrames.toDouble()) / 1_000_000.0
        } else {
            0.0
        }
        desktopPlayerPerfLog.i {
            "backend wakeupBursts=$wakeupBursts wakeupEvents=$wakeupEvents updateCallbacks=$updateCallbacks fallbackSignals=$fallbackSignals sourceLoadSignals=$sourceLoadSignals polls=$polls avgPollMs=${"%.2f".format(averagePollMs)} renderFrames=$renderFrames avgRenderMs=${"%.2f".format(averageRenderMs)} maxRenderMs=${"%.2f".format(renderDurationNsMax / 1_000_000.0)} avgRenderQueueMs=${"%.2f".format(averageRenderQueueMs)} maxRenderQueueMs=${"%.2f".format(renderQueueDelayNsMax / 1_000_000.0)} playing=$playing loading=$loading idle=$idle"
        }
        lastLogNs = nowNs
        wakeupBursts = 0L
        wakeupEvents = 0L
        updateCallbacks = 0L
        fallbackSignals = 0L
        sourceLoadSignals = 0L
        polls = 0L
        pollDurationNsTotal = 0L
        renderFrames = 0L
        renderDurationNsTotal = 0L
        renderQueueDelayNsTotal = 0L
        renderDurationNsMax = 0L
        renderQueueDelayNsMax = 0L
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
    fun mpv_error_string(error: Int): Pointer?
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
private const val WindowsPlayerStartupTimeoutMs = 30_000L
private const val WindowsPlayerInitialSeekTimeoutMs = 8_000L
private const val WindowsPlayerFallbackPollIntervalMs = 500L
private const val WindowsPlayerMaxCacheBytes = "536870912"
private const val WindowsPlayerMaxBackCacheBytes = "268435456"
private const val WindowsPlayerReadAheadSeconds = "30"
private const val WindowsPlayerCachePauseWaitSeconds = "5"

private enum class WindowsVideoSurface {
    Native,
    Embedded,
}

private fun windowsVideoSurface(): WindowsVideoSurface =
    when (System.getProperty("kino.windows.video-surface")?.trim()?.lowercase()) {
        "embedded", "gl", "opengl" -> WindowsVideoSurface.Embedded
        else -> WindowsVideoSurface.Native
    }

private fun setWindowsMpvOption(
    lib: WindowsMpvLibrary,
    ptr: Pointer,
    name: String,
    value: String,
): Int {
    val result = lib.mpv_set_option_string(ptr, name, value)
    desktopPlayerTrace("mpv option name=$name value=$value result=$result")
    return result
}

private fun requireWindowsMpvOption(
    lib: WindowsMpvLibrary,
    ptr: Pointer,
    name: String,
    value: String,
) {
    val result = setWindowsMpvOption(lib, ptr, name, value)
    if (result < 0) {
        throw RuntimeException("Unsupported libmpv option $name=$value (error $result)")
    }
}

private class MpvEventPump(
    private val drain: () -> Unit,
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Kino-mpv-events").apply { isDaemon = true }
    }
    private val requested = AtomicBoolean(false)
    private val scheduled = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    fun request() {
        if (closed.get()) return
        requested.set(true)
        if (scheduled.compareAndSet(false, true)) {
            submit()
        }
    }

    fun closeAndAwait() {
        if (!closed.compareAndSet(false, true)) return
        requested.set(false)
        executor.shutdown()
        try {
            executor.awaitTermination(5L, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        executor.shutdownNow()
    }

    private fun submit() {
        try {
            executor.execute(::drainLoop)
        } catch (_: RejectedExecutionException) {
            scheduled.set(false)
        }
    }

    private fun drainLoop() {
        try {
            while (!closed.get() && requested.getAndSet(false)) {
                drain()
            }
        } finally {
            scheduled.set(false)
            if (!closed.get() && requested.get() && scheduled.compareAndSet(false, true)) {
                submit()
            }
        }
    }
}

private class SwingStateNotifier(
    private val callback: () -> Unit,
) {
    private val scheduled = AtomicBoolean(false)

    fun request() {
        if (!scheduled.compareAndSet(false, true)) return
        SwingUtilities.invokeLater {
            scheduled.set(false)
            callback()
        }
    }
}

private fun scheduleLatestWindowsVolumeUpdate(
    state: WindowsPlayerWindowState,
    playerPtr: Pointer,
    pendingVolumePercent: AtomicReference<Int?>,
    volumeUpdateScheduled: AtomicBoolean,
) {
    if (!volumeUpdateScheduled.compareAndSet(false, true)) return

    SwingUtilities.invokeLater {
        try {
            val volumePercent = pendingVolumePercent.getAndSet(null)
            if (volumePercent != null && state.playerPtr == playerPtr && !state.isClosed) {
                state.mpvCallLock.withLock {
                    if (state.playerPtr == playerPtr && !state.isClosed) {
                        WindowsMpvLibrary.INSTANCE.mpv_set_property_string(
                            playerPtr,
                            "volume",
                            volumePercent.toString(),
                        )
                    }
                }
            }
        } finally {
            volumeUpdateScheduled.set(false)
            if (pendingVolumePercent.get() != null && state.playerPtr == playerPtr && !state.isClosed) {
                scheduleLatestWindowsVolumeUpdate(
                    state = state,
                    playerPtr = playerPtr,
                    pendingVolumePercent = pendingVolumePercent,
                    volumeUpdateScheduled = volumeUpdateScheduled,
                )
            }
        }
    }
}

private fun scheduleWindowsSwingAction(delayMs: Int, action: () -> Unit) {
    SwingUtilities.invokeLater {
        javax.swing.Timer(delayMs) { action() }.apply {
            isRepeats = false
            start()
        }
    }
}
private const val WindowsHdrCompatibilityFilter = "libplacebo=apply_filmgrain=1:peak_detect=1:tonemapping=auto"
private const val WindowsDolbyVisionCompatibilityFilter = "libplacebo=apply_dolbyvision=1:apply_filmgrain=1:peak_detect=1:tonemapping=auto"
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

    fun recordRenderFrame(durationNs: Long, queueDelayNs: Long) =
        stats.recordRenderFrame(durationNs, queueDelayNs)
}

private data class WindowsPlaybackPollResult(
    val generation: Long,
    val loadRequestGeneration: Long?,
    val snapshot: PlayerPlaybackSnapshot,
    val audioTracks: List<AudioTrack>,
    val subtitleTracks: List<SubtitleTrack>,
    val volumeLevel: PlayerAudioLevel,
    val polledTracks: Boolean,
    val errorMessage: String?,
    val terminalPlaybackError: String?,
    val startupStalled: Boolean,
    val logMessage: String?,
)

private data class MpvVideoDiagnostics(
    val codec: String?,
    val decoder: String?,
    val hardwareDecoder: String?,
    val format: String?,
    val pixelFormat: String?,
    val hardwarePixelFormat: String?,
    val primaries: String?,
    val transfer: String?,
    val matrix: String?,
    val levels: String?,
    val signalPeak: String?,
    val dolbyVision: String?,
) {
    fun signature(): String = listOf(
        codec,
        decoder,
        hardwareDecoder,
        format,
        pixelFormat,
        hardwarePixelFormat,
        primaries,
        transfer,
        matrix,
        levels,
        signalPeak,
        dolbyVision,
    ).joinToString("|")

    fun logValue(): String =
        "codec=${codec.orEmpty()} decoder=${decoder.orEmpty()} hwdec=${hardwareDecoder.orEmpty()} format=${format.orEmpty()} " +
            "pixelformat=${pixelFormat.orEmpty()} hwPixelformat=${hardwarePixelFormat.orEmpty()} " +
            "primaries=${primaries.orEmpty()} transfer=${transfer.orEmpty()} matrix=${matrix.orEmpty()} " +
            "levels=${levels.orEmpty()} sigPeak=${signalPeak.orEmpty()} dolbyVision=${dolbyVision.orEmpty()}"

    fun toMetadata(state: WindowsPlayerWindowState): DesktopVideoMetadata = DesktopVideoMetadata(
        codec = codec ?: format,
        pixelFormat = pixelFormat,
        hardwarePixelFormat = hardwarePixelFormat,
        primaries = primaries,
        transfer = transfer,
        matrix = matrix,
        signalPeak = signalPeak?.toDoubleOrNull(),
        dolbyVision = dolbyVision,
        externalHdrLike = state.streamIsHdrLike,
        externalDolbyVision = state.streamHasDolbyVision,
    )
}

private fun readMpvPropertyString(
    lib: WindowsMpvLibrary,
    ptr: Pointer,
    name: String,
): String? {
    val property = lib.mpv_get_property_string(ptr, name) ?: return null
    val value = property.getString(0)
    lib.mpv_free(property)
    return value.takeIf { it.isNotBlank() }
}

private fun readMpvVideoDiagnostics(
    lib: WindowsMpvLibrary,
    ptr: Pointer,
): MpvVideoDiagnostics = MpvVideoDiagnostics(
    codec = readMpvPropertyString(lib, ptr, "video-codec"),
    decoder = readMpvPropertyString(lib, ptr, "video-decoder"),
    hardwareDecoder = readMpvPropertyString(lib, ptr, "hwdec-current"),
    format = readMpvPropertyString(lib, ptr, "video-format"),
    pixelFormat = readMpvPropertyString(lib, ptr, "video-params/pixelformat"),
    hardwarePixelFormat = readMpvPropertyString(lib, ptr, "video-params/hw-pixelformat"),
    primaries = readMpvPropertyString(lib, ptr, "video-params/primaries"),
    transfer = readMpvPropertyString(lib, ptr, "video-params/gamma"),
    matrix = readMpvPropertyString(lib, ptr, "video-params/colormatrix"),
    levels = readMpvPropertyString(lib, ptr, "video-params/colorlevels"),
    signalPeak = readMpvPropertyString(lib, ptr, "video-params/sig-peak"),
    dolbyVision = readMpvPropertyString(lib, ptr, "video-params/dolby-vision"),
)

private fun configureWindowsVideoPipeline(
    state: WindowsPlayerWindowState,
    ptr: Pointer,
    trigger: String,
): String? {
    val lib = WindowsMpvLibrary.INSTANCE
    val diagnostics = readMpvVideoDiagnostics(lib, ptr)
    val signature = diagnostics.signature()
    if (signature == state.lastVideoDiagnosticsSignature) return null

    val decision = selectDesktopVideoPipeline(diagnostics.toMetadata(state))
    val filterSpec = when (decision.mode) {
        DesktopVideoPipelineMode.Standard -> ""
        DesktopVideoPipelineMode.HdrCompatibility -> WindowsHdrCompatibilityFilter
        DesktopVideoPipelineMode.DolbyVisionCompatibility -> WindowsDolbyVisionCompatibilityFilter
    }
    val result = lib.mpv_set_property_string(ptr, "vf", filterSpec)
    state.lastVideoDiagnosticsSignature = signature
    state.videoPipelineMode = decision.mode
    val logMessage =
        "video pipeline trigger=$trigger mode=${decision.mode} reason=${decision.reason} " +
            "filter=${filterSpec.ifBlank { "none" }} result=$result ${diagnostics.logValue()}"
    desktopPlayerTrace(logMessage)
    return logMessage
}

private fun resetWindowsVideoPipeline(state: WindowsPlayerWindowState, ptr: Pointer) {
    state.lastVideoDiagnosticsSignature = null
    state.videoPipelineMode = DesktopVideoPipelineMode.Standard
    val result = WindowsMpvLibrary.INSTANCE.mpv_set_property_string(ptr, "vf", "")
    desktopPlayerTrace("video pipeline reset result=$result")
}

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

@Structure.FieldOrder("playlist_entry_id")
internal class MpvEventStartFile(pointer: Pointer? = null) : Structure(pointer) {
    @JvmField
    var playlist_entry_id: Long = 0L
}

@Structure.FieldOrder("reason", "error", "playlist_entry_id")
internal class MpvEventEndFile(pointer: Pointer? = null) : Structure(pointer) {
    @JvmField
    var reason: Int = 0

    @JvmField
    var error: Int = 0

    @JvmField
    var playlist_entry_id: Long = 0L
}

@Structure.FieldOrder("prefix", "level", "text", "log_level")
internal class MpvLogMessage(pointer: Pointer? = null) : Structure(pointer) {
    @JvmField
    var prefix: Pointer? = null

    @JvmField
    var level: Pointer? = null

    @JvmField
    var text: Pointer? = null

    @JvmField
    var log_level: Pointer? = null
}

private fun traceMpvLogMessage(data: Pointer?) {
    try {
        val message = data?.let { MpvLogMessage(it).apply { read() } }
        desktopPlayerTrace(
            "mpv log level=${mpvLogText(message?.level)} " +
                "prefix=${mpvLogText(message?.prefix)} " +
                "text=${redactMpvLogText(mpvLogText(message?.text)).take(400)}",
        )
    } catch (error: Throwable) {
        desktopPlayerTrace("mpv log decode failed type=${error::class.simpleName} message=${error.message}")
    }
}

private fun mpvLogText(pointer: Pointer?): String = pointer?.getString(0).orEmpty()

private fun readMpvErrorString(lib: WindowsMpvLibrary, error: Int): String? =
    lib.mpv_error_string(error)?.getString(0)?.takeIf { it.isNotBlank() && it != "success" }

private fun readMpvStartFile(data: Pointer?): MpvEventStartFile? =
    data?.let { pointer -> runCatching { MpvEventStartFile(pointer).apply { read() } }.getOrNull() }

private fun readMpvEndFile(data: Pointer?): MpvEventEndFile? =
    data?.let { pointer -> runCatching { MpvEventEndFile(pointer).apply { read() } }.getOrNull() }

private fun handleWindowsMpvStartFileEvent(
    state: WindowsPlayerWindowState,
    data: Pointer?,
): String {
    val startFile = readMpvStartFile(data)
    if (startFile == null) {
        return "mpv event id=$MPV_EVENT_START_FILE data=unavailable"
    }
    val previousEntryId = state.lastObservedPlaylistEntryId
    if (state.pendingLoadRequestGeneration != null) {
        return "mpv event id=$MPV_EVENT_START_FILE playlistEntryId=${startFile.playlist_entry_id} stale=true pendingLoad=true"
    }
    if (!isWindowsPlaybackStartFileCurrent(
            playlistEntryId = startFile.playlist_entry_id,
            expectedPlaylistEntryId = state.pendingPlaylistEntryId,
            previousPlaylistEntryId = previousEntryId,
        )
    ) {
        return "mpv event id=$MPV_EVENT_START_FILE playlistEntryId=${startFile.playlist_entry_id} stale=true"
    }
    state.lastObservedPlaylistEntryId = startFile.playlist_entry_id
    state.pendingPlaylistEntryId = null
    state.activePlaylistEntryId = startFile.playlist_entry_id
    state.activePlaylistEntryGeneration = state.sourceGeneration
    return "mpv event id=$MPV_EVENT_START_FILE playlistEntryId=${startFile.playlist_entry_id} generation=${state.sourceGeneration}"
}

private fun discardPendingWindowsMpvEvents(lib: WindowsMpvLibrary, ptr: Pointer): Int {
    var discarded = 0
    while (true) {
        val eventPtr = lib.mpv_wait_event(ptr, 0.0) ?: break
        val event = MpvEvent(eventPtr).apply { read() }
        if (event.event_id == MPV_EVENT_NONE) break
        discarded += 1
    }
    return discarded
}

private fun handleWindowsMpvEndFileEvent(
    state: WindowsPlayerWindowState,
    lib: WindowsMpvLibrary,
    data: Pointer?,
): String {
    val endFile = readMpvEndFile(data)
    if (endFile == null) {
        return "mpv event id=$MPV_EVENT_END_FILE data=unavailable"
    }
    val errorMessage = if (endFile.reason == WindowsMpvEndFileReasonError) {
        readMpvErrorString(lib, endFile.error)
    } else {
        null
    }
    val playbackError = selectWindowsPlaybackEndFileError(
        event = WindowsPlaybackEndFile(
            reason = endFile.reason,
            errorMessage = errorMessage,
            playlistEntryId = endFile.playlist_entry_id,
        ),
        activePlaylistEntryId = state.activePlaylistEntryId,
        activePlaylistEntryGeneration = state.activePlaylistEntryGeneration,
        currentSourceGeneration = state.sourceGeneration,
        hasLoadedMedia = state.hasLoadedMedia,
    )
    if (playbackError != null) {
        state.terminalPlaybackError = playbackError
        state.terminalPlaybackErrorGeneration = state.sourceGeneration
    }
    return "mpv event id=$MPV_EVENT_END_FILE reason=${endFile.reason} error=${endFile.error} " +
        "playlistEntryId=${endFile.playlist_entry_id} activePlaylistEntryId=${state.activePlaylistEntryId} " +
        "generation=${state.sourceGeneration} terminal=${playbackError != null}"
}

private val mpvUrlPattern = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)

private fun redactMpvLogText(value: String): String =
    mpvUrlPattern.replace(value) { match ->
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

private fun Component.containsCurrentPointer(): Boolean {
    if (!isShowing) return false
    val pointer = MouseInfo.getPointerInfo()?.location ?: return false
    val localPointer = Point(pointer)
    SwingUtilities.convertPointFromScreen(localPointer, this)
    return contains(localPointer)
}

internal fun createWindowsOverlayPointerListener(onPointerExit: () -> Unit): MouseAdapter =
    object : MouseAdapter() {
        override fun mouseExited(e: MouseEvent) {
            onPointerExit()
        }
    }
internal fun isWindowsPlayerPointerInside(playerInside: Boolean, overlayInside: Boolean): Boolean =
    playerInside || overlayInside

internal fun shouldHideWindowsPlayerControls(pointerInside: Boolean): Boolean = !pointerInside

internal enum class WindowsStartupStallKeyAction {
    Retry,
    Back,
    Consume,
}

internal fun windowsStartupStallKeyAction(keyCode: Int): WindowsStartupStallKeyAction = when (keyCode) {
    KeyEvent.VK_R -> WindowsStartupStallKeyAction.Retry
    KeyEvent.VK_ESCAPE -> WindowsStartupStallKeyAction.Back
    else -> WindowsStartupStallKeyAction.Consume
}

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
            background = ComposeColor.Black,
            factory = {
                desktopPlayerTrace(
                    "windows presentation surface=${if (useNativeVideoSurface) "native-d3d11" else "embedded-opengl"} " +
                        "composeInteropBlending=${System.getProperty("compose.interop.blending")} " +
                        "nativeControls=false overlayWindow=true",
                )
                if (useNativeVideoSurface) {
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
                            "vo=${getString("vo-configured") ?: ""}",
                            "hwdec=${getString("hwdec-current") ?: ""}",
                            "video=${getString("video-params/w") ?: ""}x${getString("video-params/h") ?: ""}",
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

                    val audioTracksList: List<AudioTrack>
                    val subtitleTracksList: List<SubtitleTrack>
                    val trackMetadataDiagnostics = mutableListOf<String>()
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
                            val codec = getString("track-list/$i/codec") ?: ""
                            val external = getString("track-list/$i/external") == "yes"
                            val selected = getString("track-list/$i/selected") == "yes"
                            trackMetadataDiagnostics +=
                                "${playbackMetadataForLog(type)}[$i] id=${playbackMetadataForLog(id)} " +
                                    "title=${playbackMetadataForLog(title).ifBlank { "<empty>" }} " +
                                    "lang=${playbackMetadataForLog(lang).ifBlank { "<empty>" }} " +
                                    "codec=${playbackMetadataForLog(codec).ifBlank { "<empty>" }} external=$external"

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

                    Triple(
                        WindowsPlaybackPollResult(
                            generation = pollGeneration,
                            loadRequestGeneration = pollLoadRequestGeneration,
                            snapshot = snapshot,
                            audioTracks = audioTracksList,
                            subtitleTracks = subtitleTracksList,
                            volumeLevel = toPlayerAudioLevel(volumePercent),
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
                                trackMetadataDiagnostics.takeIf { it.isNotEmpty() }?.joinToString(" || ") {
                                    "mpv track metadata $it"
                                },
                                videoPipelineLog,
                            ).joinToString(" | ").takeIf { it.isNotBlank() },
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

// Window state storage class to share context across threads safely
internal class WindowsPlayerWindowState {
    val mpvCallLock = ReentrantLock()
    val loadRequestGate = WindowsPlaybackLoadRequestGate()
    @Volatile
    var playerPtr: Pointer? = null
    @Volatile
    var isClosed = false
    @Volatile
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
    @Volatile
    var sourceGeneration = 0L
    @Volatile
    var pendingLoadRequestGeneration: Long? = null
    var startupStallSinceMs = 0L
    @Volatile
    var startupStalled = false
    @Volatile
    var hasLoadedMedia = false
    @Volatile
    var activePlaylistEntryId: Long? = null
    @Volatile
    var activePlaylistEntryGeneration: Long? = null
    @Volatile
    var pendingPlaylistEntryId: Long? = null
    @Volatile
    var lastObservedPlaylistEntryId: Long? = null
    @Volatile
    var terminalPlaybackError: String? = null
    @Volatile
    var terminalPlaybackErrorGeneration: Long? = null
    @Volatile
    var initialSeekRequestedAtMs = 0L
    @Volatile
    var initialSeekTargetMs = 0L
    @Volatile
    var initialSeekRecoveryIssued = false
    var currentSourceAudioUrl: String? = null
    var currentHeaders = mapOf<String, String>()
    var volumeLevel = PlayerAudioLevel(fraction = 1f, isMuted = false)
    var streamProfileSummary: String? = null
    var streamIsHdrLike = false
    var streamHasDolbyVision = false
    var streamHasHdrFallback = false
    var lastVideoDiagnosticsSignature: String? = null
    var videoPipelineMode = DesktopVideoPipelineMode.Standard
    var lastReportedPlaybackError: String? = null
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

private fun WindowsPlayerWindowState.withMpv(block: (Pointer) -> Unit) {
    mpvCallLock.withLock {
        val ptr = playerPtr ?: return@withLock
        if (isClosed) return@withLock
        block(ptr)
    }
}

internal interface WindowsPlaybackPanel {
    fun loadFile(
        url: String,
        audioUrl: String?,
        headers: Map<String, String>,
        onCommitted: ((Long) -> Unit)? = null,
        forceResolve: Boolean = false,
    )
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
    private val onInitializationError: (String) -> Unit,
    private val onPlayerStateChanged: () -> Unit,
    private val perfCollector: DesktopBackendPerfCollector? = null,
    private val onSurfaceInteraction: (Boolean) -> Unit = {},
    private val onSurfaceExit: () -> Unit = {},
    private val onWindowFocusChanged: (Boolean, Long?) -> Unit = { _, _ -> },
) : JPanel(BorderLayout()), WindowsPlaybackPanel {
    private val glPanel = GLJPanel(createGlCapabilities())
    private var mpvInitialized = false
    @Volatile
    private var playerDisposed = false
    private val urlResolutionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var urlResolutionJob: Job? = null
    private var initializedPlayerPtr: Pointer? = null
    private val fullscreenState = FullscreenWindowState()
    private var renderContext: Pointer? = null
    private var renderUpdateCallback: MpvRenderUpdateCallback? = null
    private var wakeupCallback: MpvWakeupCallback? = null
    private val stateChangedNotifier = SwingStateNotifier(onPlayerStateChanged)
    private val eventPump = MpvEventPump(::readEvents)
    private var glProcAddressCallback: MpvOpenGlGetProcAddressCallback? = null
    private var glInitParams: MpvOpenGlInitParams? = null
    private val renderStateLock = Any()
    private var renderScheduled = false
    private var forceRenderRequested = false
    private var renderEnqueuedAtNs = 0L
    private val framebufferBinding = IntArray(1)
    private val renderFbo = MpvOpenGlFbo()
    private val renderFlipY = intMemory(1)
    private val renderParams = createRenderParams(
        MPV_RENDER_PARAM_OPENGL_FBO to renderFbo.pointer,
        MPV_RENDER_PARAM_FLIP_Y to renderFlipY,
    )
    private var playerWindow: Window? = null
    private var pointerInside = false
    private val pointerCheckTimer = javax.swing.Timer(200) {
        updatePointerPresence()
    }
    private val windowFocusListener = object : WindowAdapter() {
        override fun windowGainedFocus(e: WindowEvent) {
            onWindowFocusChanged(true, windowHandle())
        }

        override fun windowLostFocus(e: WindowEvent) {
            onWindowFocusChanged(false, windowHandle())
            notifySurfaceExit()
        }
    }

    private fun windowHandle(): Long? {
        val window = playerWindow ?: SwingUtilities.getWindowAncestor(this) ?: return null
        return Pointer.nativeValue(Native.getComponentPointer(window)).takeIf { it != 0L }
    }

    private fun updatePointerPresence(): Boolean {
        val isInside = containsCurrentPointer()
        if (shouldNotifyPlayerSurfaceExit(pointerInside, isInside)) {
            onSurfaceExit()
        }
        pointerInside = isInside
        return isInside
    }

    private fun notifySurfaceExit() {
        if (pointerInside) {
            onSurfaceExit()
        }
        pointerInside = false
    }

    init {
        state.panelRef = this
        state.isClosed = false
        background = java.awt.Color.BLACK
        isFocusable = true
        focusTraversalKeysEnabled = false
        glPanel.background = java.awt.Color.BLACK
        glPanel.isFocusable = true
        glPanel.focusTraversalKeysEnabled = false
        glPanel.addGLEventListener(object : GLEventListener {
            override fun init(drawable: GLAutoDrawable) {
                if (!mpvInitialized) {
                    try {
                        initializeMpv(drawable)
                        mpvInitialized = true
                    } catch (error: Throwable) {
                        state.isClosed = true
                        eventPump.closeAndAwait()
                        releasePlayerResources()
                        onInitializationError(error.message ?: "Embedded video initialization failed")
                    }
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
                if (state.startupStalled) {
                    when (windowsStartupStallKeyAction(e.keyCode)) {
                        WindowsStartupStallKeyAction.Retry -> retryPlayback()
                        WindowsStartupStallKeyAction.Back -> closePlayer()
                        WindowsStartupStallKeyAction.Consume -> Unit
                    }
                    return
                }
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
            }

            override fun mouseMoved(e: MouseEvent) {
                pointerInside = true
                onSurfaceInteraction(false)
            }

            override fun mouseExited(e: MouseEvent) {
                updatePointerPresence()
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
        pointerInside = containsCurrentPointer()
        playerWindow = SwingUtilities.getWindowAncestor(this)?.also { window ->
            window.addWindowFocusListener(windowFocusListener)
            SwingUtilities.invokeLater {
                onWindowFocusChanged(window.isFocused, windowHandle())
            }
        }
        pointerCheckTimer.start()
        SwingUtilities.invokeLater {
            requestInteractionFocus()
            stateChangedNotifier.request()
        }
    }

    override fun removeNotify() {
        pointerCheckTimer.stop()
        pointerInside = false
        onWindowFocusChanged(false, null)
        playerWindow?.removeWindowFocusListener(windowFocusListener)
        playerWindow = null
        super.removeNotify()
    }

    private fun initializeMpv(drawable: GLAutoDrawable) {
        val lib = WindowsMpvLibrary.INSTANCE
        val ptr = lib.mpv_create() ?: throw RuntimeException("Failed to create libmpv instance")
        initializedPlayerPtr = ptr
        lib.mpv_set_option_string(ptr, "vo", "libmpv")
        lib.mpv_set_option_string(ptr, "target-colorspace-hint", "yes")
        lib.mpv_set_option_string(ptr, "hdr-compute-peak", "yes")
        lib.mpv_set_option_string(ptr, "tone-mapping", "auto")
        lib.mpv_set_option_string(ptr, "input-media-keys", "no")
        lib.mpv_set_option_string(ptr, "media-controls", "no")
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
         lib.mpv_observe_property(ptr, 0, "duration", MPV_FORMAT_DOUBLE)
        lib.mpv_observe_property(ptr, 0, "speed", MPV_FORMAT_DOUBLE)
        wakeupCallback = object : MpvWakeupCallback {
            override fun invoke(ctx: Pointer?) {
                eventPump.request()
            }
        }
        lib.mpv_set_wakeup_callback(ptr, wakeupCallback, null)
        state.playerPtr = ptr
        initializedPlayerPtr = ptr
        eventPump.request()
        desktopPlayerTrace(
            "embedded panel initialized with opengl render api " +
                "glVendor=${drawable.gl.glGetString(GL.GL_VENDOR) ?: ""} " +
                "glRenderer=${drawable.gl.glGetString(GL.GL_RENDERER) ?: ""} " +
                "glVersion=${drawable.gl.glGetString(GL.GL_VERSION) ?: ""}"
        )
        stateChangedNotifier.request()
        scheduleRender(force = true)
    }

    private fun readEvents() {
        state.mpvCallLock.withLock {
        val ptr = state.playerPtr ?: return
        if (state.isClosed) return
        val lib = WindowsMpvLibrary.INSTANCE
        var hasUpdates = false
        var eventCount = 0
        while (!state.isClosed) {
            val eventPtr = lib.mpv_wait_event(ptr, 0.0) ?: break
            val event = MpvEvent(eventPtr)
            event.read()
            when (event.event_id) {
                MPV_EVENT_NONE -> break
                MPV_EVENT_SHUTDOWN -> return
                MPV_EVENT_LOG_MESSAGE -> {
                    traceMpvLogMessage(event.data)
                }
                MPV_EVENT_START_FILE -> {
                    desktopPlayerTrace(handleWindowsMpvStartFileEvent(state, event.data))
                    hasUpdates = true
                    eventCount += 1
                }
                MPV_EVENT_END_FILE -> {
                    desktopPlayerTrace(handleWindowsMpvEndFileEvent(state, lib, event.data))
                    hasUpdates = true
                    eventCount += 1
                }
                MPV_EVENT_FILE_LOADED -> {
                    desktopPlayerTrace("mpv event id=${event.event_id} error=${event.error} applying subtitle style")
                    applyWindowsMpvSubtitleStyle(ptr, state.subtitleStyle)
                    configureWindowsVideoPipeline(state, ptr, "file-loaded")
                    hasUpdates = true
                    eventCount += 1
                }
                else -> {
                    hasUpdates = true
                    eventCount += 1
                }
            }
        }
        if (hasUpdates) {
            perfCollector?.recordWakeup(eventCount)
            stateChangedNotifier.request()
        }
        }
    }

    override fun loadFile(
        url: String,
        audioUrl: String?,
        headers: Map<String, String>,
        onCommitted: ((Long) -> Unit)?,
        forceResolve: Boolean,
    ) {
        val ptr = state.playerPtr ?: return
        if (playerDisposed || state.isClosed || state.panelRef !== this) return
        val requestGeneration = state.loadRequestGate.allocate()
        state.mpvCallLock.withLock {
            state.sourceGeneration += 1L
            state.pendingLoadRequestGeneration = requestGeneration
            state.hasLoadedMedia = false
            state.startupStallSinceMs = 0L
            state.startupStalled = false
            state.lastReportedPlaybackError = null
            state.terminalPlaybackError = null
            state.terminalPlaybackErrorGeneration = null
            state.pendingPlaylistEntryId = null
        }
        urlResolutionJob?.cancel()
        urlResolutionJob = urlResolutionScope.launch {
            val resolved = runInterruptible {
                resolveWindowsPlaybackUrls(url, audioUrl, headers, forceResolve)
            }
            if (!isActive) return@launch
            SwingUtilities.invokeLater {
                if (!canCommitLoad(requestGeneration, ptr)) return@invokeLater
                var committedGeneration: Long? = null
                state.mpvCallLock.withLock {
                    if (!canCommitLoad(requestGeneration, ptr)) return@withLock
                    val lib = WindowsMpvLibrary.INSTANCE
                    val loadGeneration = state.sourceGeneration
                    committedGeneration = loadGeneration
                    state.pendingLoadRequestGeneration = null
                    state.lastReportedPlaybackError = null
                    state.startupStallSinceMs = 0L
                    state.startupStalled = false
                    state.hasLoadedMedia = false
                    state.activePlaylistEntryId = null
                    state.activePlaylistEntryGeneration = null
                    state.pendingPlaylistEntryId = null
                    state.terminalPlaybackError = null
                    state.terminalPlaybackErrorGeneration = null
                    state.initialSeekRequestedAtMs = 0L
                    state.initialSeekTargetMs = 0L
                    state.initialSeekRecoveryIssued = false
                    val headersStr = resolved.headers.entries
                        .joinToString(",") { "${it.key}: ${it.value.replace("\\", "\\\\").replace(",", "\\,")}" }
                    desktopPlayerTrace(
                        "embedded panel loadFile host=${playbackUrlForLog(url)} " +
                            "effectiveHost=${playbackUrlForLog(resolved.url)} " +
                            "audioHost=${playbackUrlForLog(audioUrl)} " +
                            "effectiveAudioHost=${playbackUrlForLog(resolved.audioUrl)} " +
                            "headerKeys=${resolved.headers.keys.joinToString()} headerBlobLength=${headersStr.length}"
                    )
                    resetWindowsVideoPipeline(state, ptr)
                    val discardedEvents = discardPendingWindowsMpvEvents(lib, ptr)
                    if (discardedEvents > 0) {
                        desktopPlayerTrace("embedded panel discarded pending mpv events count=$discardedEvents")
                    }
                    applyWindowsMpvSubtitleStyle(ptr, state.subtitleStyle)
                    lib.mpv_set_property_string(ptr, "http-header-fields", headersStr)
                    val loadResult = lib.mpv_command(ptr, arrayOf("loadfile", resolved.url, "replace", null))
                    state.pendingPlaylistEntryId = loadResult.takeIf { it >= 0 }
                        ?.let { readMpvPropertyString(lib, ptr, "playlist/0/id")?.toLongOrNull() }
                    selectWindowsPlaybackCommandError(loadResult)?.let { playbackError ->
                        state.terminalPlaybackError = playbackError
                        state.terminalPlaybackErrorGeneration = loadGeneration
                        stateChangedNotifier.request()
                        desktopPlayerTrace("embedded panel loadFile failed commandResult=$loadResult")
                    }
                    if (!resolved.audioUrl.isNullOrBlank()) {
                        scheduleWindowsSwingAction(500) {
                            if (!state.isClosed && state.playerPtr == ptr && state.sourceGeneration == loadGeneration) {
                                lib.mpv_command(ptr, arrayOf("audio-add", resolved.audioUrl, "select", null))
                            }
                        }
                    }
                    reapplyWindowsMpvSubtitleStyleLater(state, ptr)
                    requestFocusInWindow()
                    glPanel.requestFocusInWindow()
                    scheduleRender(force = true)
                }
                committedGeneration?.let { onCommitted?.invoke(it) }
            }
        }
    }

    private fun canCommitLoad(requestGeneration: Long, ptr: Pointer): Boolean =
        !playerDisposed &&
            !state.isClosed &&
            state.panelRef === this &&
            state.playerPtr == ptr &&
            state.loadRequestGate.isCurrent(requestGeneration)

    override fun loadSubtitleUrl(url: String) {
        val ptr = state.playerPtr ?: return
        WindowsMpvLibrary.INSTANCE.mpv_command(ptr, arrayOf("sub-add", url, "select", null))
        applyWindowsMpvSubtitleStyle(ptr, state.subtitleStyle)
        reapplyWindowsMpvSubtitleStyleLater(state, ptr)
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
        loadFile(
            sourceUrl,
            state.currentSourceAudioUrl,
            state.currentHeaders,
            onCommitted = { loadGeneration ->
            scheduleWindowsSwingAction(500) {
                if (!state.isClosed && state.playerPtr == ptr && state.sourceGeneration == loadGeneration) {
                    lib.mpv_command(ptr, arrayOf("seek", String.format("%.3f", pos), "absolute", null))
                }
            }
            },
            forceResolve = true,
        )
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
        pointerCheckTimer.stop()
        val ownsState = state.panelRef === this
        if (ownsState) {
            state.loadRequestGate.invalidate()
            state.panelRef = null
            state.isClosed = true
        }
        urlResolutionScope.cancel()
        urlResolutionJob = null
        eventPump.closeAndAwait()
        onWindowFocusChanged(false, null)
        desktopPlayerTrace("embedded panel dispose start displayable=${glPanel.isDisplayable}")
        fullscreenState.exit(SwingUtilities.getWindowAncestor(this))
        state.mpvCallLock.withLock {
            initializedPlayerPtr?.let { ptr ->
                WindowsMpvLibrary.INSTANCE.mpv_set_wakeup_callback(ptr, null, null)
            }
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
        val playerPtr = initializedPlayerPtr
        desktopPlayerTrace("embedded panel release resources render=${renderPtr != null} player=${playerPtr != null}")
        renderContext = null
        glInitParams = null
        state.mpvCallLock.withLock {
            if (renderPtr != null) {
                WindowsMpvLibrary.INSTANCE.mpv_render_context_free(renderPtr)
            }
            if (state.playerPtr == playerPtr) {
                state.playerPtr = null
            }
            if (playerPtr != null) {
                WindowsMpvLibrary.INSTANCE.mpv_terminate_destroy(playerPtr)
            }
        }
        initializedPlayerPtr = null
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
            renderEnqueuedAtNs = System.nanoTime()
        }
        SwingUtilities.invokeLater {
            val queueDelayNs = System.nanoTime() - synchronized(renderStateLock) {
                renderEnqueuedAtNs
            }
            val renderStartNs = System.nanoTime()
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
            perfCollector?.recordRenderFrame(
                durationNs = System.nanoTime() - renderStartNs,
                queueDelayNs = queueDelayNs,
            )
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
        drawable.gl.glGetIntegerv(GL.GL_FRAMEBUFFER_BINDING, framebufferBinding, 0)
        renderFbo.fbo = framebufferBinding[0]
        renderFbo.w = width
        renderFbo.h = height
        renderFbo.internal_format = 0
        renderFbo.write()
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

@OptIn(ExperimentalComposeUiApi::class)
internal class WindowsPlayerPanel(
    private val playerTheme: PlayerTheme,
    private val overlayColorScheme: ColorScheme,
    private val overlayTypography: Typography,
    private val overlayNuvioTokens: NuvioThemeTokens,
    private val overlayTypeScale: NuvioTypeScale,
    private val overlayAppTheme: AppTheme,
    private val overlayRippleConfiguration: RippleConfiguration?,
    private val state: WindowsPlayerWindowState,
    private val onClose: () -> Unit,
    private val onInitializationError: (String) -> Unit,
    private val onPlayerStateChanged: () -> Unit,
    private val perfCollector: DesktopBackendPerfCollector? = null,
    private val onSurfaceInteraction: (Boolean) -> Unit = {},
    private val onSurfaceExit: () -> Unit = {},
    private val onWindowFocusChanged: (Boolean, Long?) -> Unit = { _, _ -> },
    private val onAddonSubtitlesFetch: () -> Unit,
    private val onSourcesRequested: () -> Unit,
    private val onSourceSelected: (String) -> Unit,
    private val onSourceReload: () -> Unit,
    private val onEpisodesRequested: () -> Unit,
    private val onEpisodeSelected: (String) -> Unit,
    private val onEpisodeStreamSelected: (String) -> Unit,
    private val onNextEpisodeRequested: () -> Unit,
    private val onSubmitIntro: (String, Double, Double) -> Unit,
    private val overlayContent: @Composable () -> Unit = {},
    private val showNativeControls: Boolean = true,
) : JPanel(BorderLayout()), WindowsPlaybackPanel {
    private val canvas = object : Canvas() {
        override fun addNotify() {
            super.addNotify()
            SwingUtilities.invokeLater {
                if (!mpvInitialized) {
                    try {
                        initializeMpv()
                        mpvInitialized = true
                    } catch (error: Throwable) {
                        state.isClosed = true
                        state.loadRequestGate.invalidate()
                        state.panelRef = null
                        urlResolutionScope.cancel()
                        urlResolutionJob = null
                        eventPump.closeAndAwait()
                        onInitializationError(error.message ?: "Native video initialization failed")
                    }
                }
                stateChangedNotifier.request()
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
    private var isSeeking = false
    private var mpvInitialized = false
    @Volatile
    private var playerDisposed = false
    private val urlResolutionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var urlResolutionJob: Job? = null
    private var initializedPlayerPtr: Pointer? = null
    private var wakeupCallback: MpvWakeupCallback? = null
    private val stateChangedNotifier = SwingStateNotifier(onPlayerStateChanged)
    private val eventPump = MpvEventPump(::readEvents)
    private var isPlaying = false
    private var lastPlaybackUiState: WindowsPlaybackUiState? = null
    private var playerWindow: Window? = null
    private var overlayWindow: JWindow? = null
    private var pointerInside = false
    private val controlsTimer = javax.swing.Timer(250) {
        val isInside = updatePointerPresence()
        if (shouldHideWindowsPlayerControls(isInside)) {
            hideControls()
        }
    }
    private val videoLayer = JPanel()
    private val overlayPanel = ComposePanel(renderSettings = RenderSettings.SwingGraphics())
    private val windowBoundsListener = object : ComponentAdapter() {
        override fun componentMoved(e: ComponentEvent?) {
            scheduleOverlayWindowBoundsUpdate()
        }

        override fun componentResized(e: ComponentEvent?) {
            scheduleOverlayWindowBoundsUpdate()
        }

        override fun componentShown(e: ComponentEvent?) {
            scheduleOverlayWindowBoundsUpdate()
        }
    }
    private val windowFocusListener = object : WindowAdapter() {
        override fun windowGainedFocus(e: WindowEvent) {
            onWindowFocusChanged(true, windowHandle())
            scheduleOverlayWindowBoundsUpdate()
            overlayWindow?.isVisible = true
        }

        override fun windowLostFocus(e: WindowEvent) {
            SwingUtilities.invokeLater {
                if (!isPlayerWindowActive()) {
                    onWindowFocusChanged(false, windowHandle())
                    notifySurfaceExit()
                    hideControls()
                }
            }
        }
    }

    private fun isPlayerWindowActive(): Boolean {
        var activeWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
        while (activeWindow != null) {
            if (activeWindow == playerWindow || activeWindow == overlayWindow) return true
            activeWindow = activeWindow.owner
        }
        return false
    }

    private fun windowHandle(): Long? {
        val window = playerWindow ?: SwingUtilities.getWindowAncestor(this) ?: return null
        return Pointer.nativeValue(Native.getComponentPointer(window)).takeIf { it != 0L }
    }
    private fun containsPlayerPointer(): Boolean = isWindowsPlayerPointerInside(
        playerInside = containsCurrentPointer(),
        overlayInside = overlayWindow?.containsCurrentPointer() == true,
    )

    private fun updatePointerPresence(): Boolean {
        val isInside = containsPlayerPointer()
        if (shouldNotifyPlayerSurfaceExit(pointerInside, isInside)) {
            onSurfaceExit()
        }
        pointerInside = isInside
        return isInside
    }

    private fun notifySurfaceExit() {
        if (pointerInside) {
            onSurfaceExit()
        }
        pointerInside = false
    }

    init {
        state.panelRef = this
        state.isClosed = false

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

        controlsTimer.start()
    }

    override fun addNotify() {
        super.addNotify()
        pointerInside = containsPlayerPointer()
        playerWindow = SwingUtilities.getWindowAncestor(this)?.also { window ->
            window.addWindowFocusListener(windowFocusListener)
            window.addComponentListener(windowBoundsListener)
            createOverlayWindow(window)
            SwingUtilities.invokeLater {
                onWindowFocusChanged(window.isFocused, windowHandle())
            }
        }
    }

    override fun removeNotify() {
        controlsTimer.stop()
        pointerInside = false
        onWindowFocusChanged(false, null)
        playerWindow?.removeWindowFocusListener(windowFocusListener)
        playerWindow?.removeComponentListener(windowBoundsListener)
        playerWindow = null
        overlayWindow?.isVisible = false
        overlayWindow?.dispose()
        overlayWindow = null
        super.removeNotify()
    }

    private fun setupPanel() {
        background = playerTheme.panelBgColor
        isFocusable = true
        focusTraversalKeysEnabled = false
        canvas.background = java.awt.Color.BLACK
        canvas.isFocusable = true
        canvas.focusTraversalKeysEnabled = false
        videoLayer.layout = OverlayLayout(videoLayer)
        videoLayer.background = java.awt.Color.BLACK
        videoLayer.add(canvas)
        overlayPanel.isOpaque = false
        overlayPanel.background = java.awt.Color(0, 0, 0, 0)
        overlayPanel.setContent {
            CompositionLocalProvider(
                LocalNuvioThemeTokens provides overlayNuvioTokens,
                LocalNuvioTypeScale provides overlayTypeScale,
                LocalAppTheme provides overlayAppTheme,
                LocalRippleConfiguration provides overlayRippleConfiguration,
            ) {
                MaterialTheme(
                    colorScheme = overlayColorScheme,
                    typography = overlayTypography,
                ) {
                    overlayContent()
                }
            }
        }
        add(videoLayer, BorderLayout.CENTER)
    }

    private fun createOverlayWindow(owner: Window) {
        if (overlayWindow == null) {
            overlayWindow = JWindow(owner).apply {
                setFocusableWindowState(false)
                isAutoRequestFocus = false
                setBackground(java.awt.Color(0, 0, 0, 0))
                (contentPane as? JComponent)?.isOpaque = false
                contentPane.background = java.awt.Color(0, 0, 0, 0)
                add(overlayPanel, BorderLayout.CENTER)
            }
        }
        scheduleOverlayWindowBoundsUpdate()
        overlayWindow?.isVisible = owner.isShowing
        SwingUtilities.invokeLater {
            desktopPlayerTrace("native overlay window renderApi=${overlayPanel.renderApi}")
        }
    }

    private fun scheduleOverlayWindowBoundsUpdate() {
        SwingUtilities.invokeLater {
            updateOverlayWindowBounds()
        }
    }

    private fun updateOverlayWindowBounds() {
        val window = overlayWindow ?: return
        val owner = playerWindow ?: return
        val contentPane = (owner as? RootPaneContainer)?.contentPane ?: return
        if (!contentPane.isShowing || contentPane.width <= 0 || contentPane.height <= 0) return
        val location = Point(0, 0)
        SwingUtilities.convertPointToScreen(location, contentPane)
        window.setBounds(location.x, location.y, contentPane.width, contentPane.height)
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
                pointerInside = true
                onSurfaceInteraction(false)
                showControls()
            }
            override fun mouseClicked(e: MouseEvent) {
                onSurfaceInteraction(true)
                showControls()
                canvas.requestFocusInWindow()
            }

            override fun mouseExited(e: MouseEvent) {
                if (!updatePointerPresence()) {
                    hideControls()
                }
            }
        }

        canvas.addMouseListener(interactionListener)
        canvas.addMouseMotionListener(interactionListener)
        overlayPanel.addMouseListener(createWindowsOverlayPointerListener {
            if (!updatePointerPresence()) {
                hideControls()
            }
        })
        overlayPanel.addMouseMotionListener(interactionListener)
        addMouseListener(interactionListener)
        addMouseMotionListener(interactionListener)

        val keyListener = object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (state.startupStalled) {
                    when (windowsStartupStallKeyAction(e.keyCode)) {
                        WindowsStartupStallKeyAction.Retry -> retryPlayback()
                        WindowsStartupStallKeyAction.Back -> closePlayer()
                        WindowsStartupStallKeyAction.Consume -> Unit
                    }
                    return
                }
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
        overlayPanel.isFocusable = true
        overlayPanel.addKeyListener(keyListener)
        addKeyListener(keyListener)

        // Mouse Wheel volume control
        val wheelListener = MouseWheelListener { e ->
            showControls()
            adjustVolume(-e.wheelRotation * 2.0)
        }
        canvas.addMouseWheelListener(wheelListener)
        if (showNativeControls) {
            overlayPanel.addMouseWheelListener(wheelListener)
        }
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
        try {
            val hwnd = Native.getComponentPointer(canvas)
            val hwndLong = Pointer.nativeValue(hwnd)
            desktopPlayerTrace(
                "native panel canvas displayable=${canvas.isDisplayable} showing=${canvas.isShowing} " +
                    "size=${canvas.width}x${canvas.height} hwnd=$hwndLong"
            )
            if (hwndLong == 0L) {
                throw RuntimeException("Native video canvas has no HWND")
            }

            requireWindowsMpvOption(lib, ptr, "wid", hwndLong.toString())
            setWindowsMpvOption(lib, ptr, "vo", "gpu-next,gpu")
            requireWindowsMpvOption(lib, ptr, "gpu-api", "d3d11")
            requireWindowsMpvOption(lib, ptr, "gpu-context", "d3d11")
            requireWindowsMpvOption(lib, ptr, "d3d11-output-mode", "window")
            requireWindowsMpvOption(lib, ptr, "d3d11-flip", "no")
            setWindowsMpvOption(lib, ptr, "input-media-keys", "no")
            setWindowsMpvOption(lib, ptr, "media-controls", "no")
            setWindowsMpvOption(lib, ptr, "subs-match-os-language", "yes")
            setWindowsMpvOption(lib, ptr, "subs-fallback", "yes")
            setWindowsMpvOption(lib, ptr, "sub-ass-override", "force")
            setWindowsMpvOption(lib, ptr, "hwdec", "auto-copy-safe")
            setWindowsMpvOption(lib, ptr, "target-colorspace-hint", "yes")
            setWindowsMpvOption(lib, ptr, "hdr-compute-peak", "yes")
            setWindowsMpvOption(lib, ptr, "tone-mapping", "auto")
            setWindowsMpvOption(lib, ptr, "keep-open", "yes")
            setWindowsMpvOption(lib, ptr, "cache", "yes")
            setWindowsMpvOption(lib, ptr, "cache-pause", "yes")
            setWindowsMpvOption(lib, ptr, "cache-pause-wait", WindowsPlayerCachePauseWaitSeconds)
            setWindowsMpvOption(lib, ptr, "demuxer-max-bytes", WindowsPlayerMaxCacheBytes)
            setWindowsMpvOption(lib, ptr, "demuxer-max-back-bytes", WindowsPlayerMaxBackCacheBytes)
            setWindowsMpvOption(lib, ptr, "demuxer-readahead-secs", WindowsPlayerReadAheadSeconds)

            val ret = lib.mpv_initialize(ptr)
            if (ret < 0) {
                throw RuntimeException("Failed to initialize libmpv: error code $ret")
            }
            lib.mpv_request_log_messages(ptr, "info")
            lib.mpv_observe_property(ptr, 0, "pause", MPV_FORMAT_FLAG)
            lib.mpv_observe_property(ptr, 0, "paused-for-cache", MPV_FORMAT_FLAG)
            lib.mpv_observe_property(ptr, 0, "core-idle", MPV_FORMAT_FLAG)
            lib.mpv_observe_property(ptr, 0, "eof-reached", MPV_FORMAT_FLAG)
            lib.mpv_observe_property(ptr, 0, "seeking", MPV_FORMAT_FLAG)
            lib.mpv_observe_property(ptr, 0, "track-list/count", MPV_FORMAT_INT64)
            lib.mpv_observe_property(ptr, 0, "volume", MPV_FORMAT_DOUBLE)
            lib.mpv_observe_property(ptr, 0, "duration", MPV_FORMAT_DOUBLE)
            lib.mpv_observe_property(ptr, 0, "speed", MPV_FORMAT_DOUBLE)
            wakeupCallback = object : MpvWakeupCallback {
                override fun invoke(ctx: Pointer?) {
                    eventPump.request()
                }
            }
            lib.mpv_set_wakeup_callback(ptr, wakeupCallback, null)
            state.playerPtr = ptr
            initializedPlayerPtr = ptr
            eventPump.request()
        } catch (error: Throwable) {
            lib.mpv_terminate_destroy(ptr)
            throw error
        }
    }

    override fun loadFile(
        url: String,
        audioUrl: String?,
        headers: Map<String, String>,
        onCommitted: ((Long) -> Unit)?,
        forceResolve: Boolean,
    ) {
        val ptr = state.playerPtr ?: return
        if (playerDisposed || state.isClosed || state.panelRef !== this) return
        val requestGeneration = state.loadRequestGate.allocate()
        state.mpvCallLock.withLock {
            state.sourceGeneration += 1L
            state.pendingLoadRequestGeneration = requestGeneration
            state.hasLoadedMedia = false
            state.startupStallSinceMs = 0L
            state.startupStalled = false
            state.lastReportedPlaybackError = null
            state.terminalPlaybackError = null
            state.terminalPlaybackErrorGeneration = null
            state.pendingPlaylistEntryId = null
        }
        urlResolutionJob?.cancel()
        urlResolutionJob = urlResolutionScope.launch {
            val resolved = runInterruptible {
                resolveWindowsPlaybackUrls(url, audioUrl, headers, forceResolve)
            }
            if (!isActive) return@launch
            SwingUtilities.invokeLater {
                if (!canCommitLoad(requestGeneration, ptr)) return@invokeLater
                var committedGeneration: Long? = null
                state.mpvCallLock.withLock {
                    if (!canCommitLoad(requestGeneration, ptr)) return@withLock
                    val lib = WindowsMpvLibrary.INSTANCE
                    val loadGeneration = state.sourceGeneration
                    committedGeneration = loadGeneration
                    state.pendingLoadRequestGeneration = null
                    state.lastReportedPlaybackError = null
                    state.startupStallSinceMs = 0L
                    state.startupStalled = false
                    state.hasLoadedMedia = false
                    state.activePlaylistEntryId = null
                    state.activePlaylistEntryGeneration = null
                    state.pendingPlaylistEntryId = null
                    state.terminalPlaybackError = null
                    state.terminalPlaybackErrorGeneration = null
                    state.initialSeekRequestedAtMs = 0L
                    state.initialSeekTargetMs = 0L
                    state.initialSeekRecoveryIssued = false
                    val headersStr = resolved.headers.entries
                        .joinToString(",") { "${it.key}: ${it.value.replace("\\", "\\\\").replace(",", "\\,")}" }
                    lib.mpv_set_property_string(ptr, "http-header-fields", headersStr)
                    resetWindowsVideoPipeline(state, ptr)
                    desktopPlayerTrace(
                        "native panel loadFile host=${playbackUrlForLog(url)} " +
                            "effectiveHost=${playbackUrlForLog(resolved.url)} " +
                            "audioHost=${playbackUrlForLog(audioUrl)} " +
                            "effectiveAudioHost=${playbackUrlForLog(resolved.audioUrl)} " +
                            "headerKeys=${resolved.headers.keys.joinToString()} headerBlobLength=${headersStr.length}"
                    )
                    applyWindowsMpvSubtitleStyle(ptr, state.subtitleStyle)
                    val discardedEvents = discardPendingWindowsMpvEvents(lib, ptr)
                    if (discardedEvents > 0) {
                        desktopPlayerTrace("native panel discarded pending mpv events count=$discardedEvents")
                    }
                    val loadResult = lib.mpv_command(ptr, arrayOf("loadfile", resolved.url, "replace", null))
                    state.pendingPlaylistEntryId = loadResult.takeIf { it >= 0 }
                        ?.let { readMpvPropertyString(lib, ptr, "playlist/0/id")?.toLongOrNull() }
                    selectWindowsPlaybackCommandError(loadResult)?.let { playbackError ->
                        state.terminalPlaybackError = playbackError
                        state.terminalPlaybackErrorGeneration = loadGeneration
                        stateChangedNotifier.request()
                        desktopPlayerTrace("native panel loadFile failed commandResult=$loadResult")
                    }

                    if (!resolved.audioUrl.isNullOrBlank()) {
                        scheduleWindowsSwingAction(500) {
                            if (!state.isClosed && state.playerPtr == ptr && state.sourceGeneration == loadGeneration) {
                                lib.mpv_command(ptr, arrayOf("audio-add", resolved.audioUrl, "select", null))
                            }
                        }
                    }
                    reapplyWindowsMpvSubtitleStyleLater(state, ptr)
                }
                committedGeneration?.let { onCommitted?.invoke(it) }
            }
        }
    }

    private fun canCommitLoad(requestGeneration: Long, ptr: Pointer): Boolean =
        !playerDisposed &&
            !state.isClosed &&
            state.panelRef === this &&
            state.playerPtr == ptr &&
            state.loadRequestGate.isCurrent(requestGeneration)

    fun updatePlaybackState(
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
        isLoading: Boolean,
        speed: Float,
        volumePercent: Float,
    ) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater {
                updatePlaybackState(positionMs, durationMs, isPlaying, isLoading, speed, volumePercent)
            }
            return
        }
        val nextState = WindowsPlaybackUiState(
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = isPlaying,
            isLoading = isLoading,
            speed = speed,
            volumePercent = volumePercent,
        )
        if (lastPlaybackUiState == nextState) return
        lastPlaybackUiState = nextState
        this.isPlaying = isPlaying
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
        volumeButton.text = "🔊 ${volumePercent.roundToInt()}%"
    }

    private data class WindowsPlaybackUiState(
        val positionMs: Long,
        val durationMs: Long,
        val isPlaying: Boolean,
        val isLoading: Boolean,
        val speed: Float,
        val volumePercent: Float,
    )

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
        val sourceUrl = state.currentSourceUrl.takeIf { it.isNotBlank() } ?: return
        val pos = getDouble("time-pos")
        loadFile(
            sourceUrl,
            state.currentSourceAudioUrl,
            state.currentHeaders,
            onCommitted = { loadGeneration ->
                scheduleWindowsSwingAction(500) {
                    if (!state.isClosed && state.playerPtr == ptr && state.sourceGeneration == loadGeneration) {
                        lib.mpv_command(ptr, arrayOf("seek", String.format("%.3f", pos), "absolute", null))
                    }
                }
            },
            forceResolve = true,
        )
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
        reapplyWindowsMpvSubtitleStyleLater(state, ptr)
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
        scheduleOverlayWindowBoundsUpdate()
        SwingUtilities.invokeLater {
            scheduleOverlayWindowBoundsUpdate()
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
        if (playerDisposed) return
        playerDisposed = true
        controlsTimer.stop()
        val ownsState = state.panelRef === this
        if (ownsState) {
            state.loadRequestGate.invalidate()
            state.panelRef = null
            state.isClosed = true
        }
        urlResolutionScope.cancel()
        urlResolutionJob = null
        overlayWindow?.isVisible = false
        overlayWindow?.dispose()
        overlayWindow = null
        eventPump.closeAndAwait()
        onWindowFocusChanged(false, null)
        fullscreenState.exit(SwingUtilities.getWindowAncestor(this))
        state.mpvCallLock.withLock {
            val ptr = initializedPlayerPtr
            if (state.playerPtr == ptr) {
                state.playerPtr = null
            }
            if (ptr != null) {
                WindowsMpvLibrary.INSTANCE.mpv_set_wakeup_callback(ptr, null, null)
                WindowsMpvLibrary.INSTANCE.mpv_terminate_destroy(ptr)
            }
        }
        initializedPlayerPtr = null
        wakeupCallback = null
        val w = SwingUtilities.getWindowAncestor(this)
        if (w != null) {
            w.cursor = Cursor.getDefaultCursor()
        }
    }

    private fun readEvents() {
        state.mpvCallLock.withLock {
        val ptr = state.playerPtr ?: return
        if (state.isClosed) return
        val lib = WindowsMpvLibrary.INSTANCE
        var hasUpdates = false
        var eventCount = 0
        while (!state.isClosed) {
            val eventPtr = lib.mpv_wait_event(ptr, 0.0) ?: break
            val event = MpvEvent(eventPtr)
            event.read()
            when (event.event_id) {
                MPV_EVENT_NONE -> break
                MPV_EVENT_SHUTDOWN -> return
                MPV_EVENT_LOG_MESSAGE -> {
                    traceMpvLogMessage(event.data)
                }
                MPV_EVENT_START_FILE -> {
                    desktopPlayerTrace(handleWindowsMpvStartFileEvent(state, event.data))
                    hasUpdates = true
                    eventCount += 1
                }
                MPV_EVENT_END_FILE -> {
                    desktopPlayerTrace(handleWindowsMpvEndFileEvent(state, lib, event.data))
                    hasUpdates = true
                    eventCount += 1
                }
                MPV_EVENT_FILE_LOADED -> {
                    desktopPlayerTrace("mpv event id=${event.event_id} error=${event.error} applying subtitle style")
                    applyWindowsMpvSubtitleStyle(ptr, state.subtitleStyle)
                    configureWindowsVideoPipeline(state, ptr, "file-loaded")
                    hasUpdates = true
                    eventCount += 1
                }
                else -> {
                    hasUpdates = true
                    eventCount += 1
                }
            }
        }
        if (hasUpdates) {
            perfCollector?.recordWakeup(eventCount)
            stateChangedNotifier.request()
        }
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
) {
    val generation = state.sourceGeneration
    scheduleWindowsSwingAction(300) {
        state.withMpv { currentPtr ->
            if (currentPtr == ptr && state.sourceGeneration == generation) {
                applyWindowsMpvSubtitleStyle(currentPtr, state.subtitleStyle)
            }
        }
    }
}
