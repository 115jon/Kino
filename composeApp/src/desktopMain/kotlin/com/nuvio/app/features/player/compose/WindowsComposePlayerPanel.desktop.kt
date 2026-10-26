package com.nuvio.app.features.player.compose

import androidx.compose.runtime.mutableStateOf
import com.nuvio.app.features.player.DesktopBackendPerfCollector
import com.nuvio.app.features.player.FullscreenWindowState
import com.nuvio.app.features.player.MpvEventPump
import com.nuvio.app.features.player.MpvWakeupCallback
import com.nuvio.app.features.player.PlayerResizeMode
import com.nuvio.app.features.player.SwingStateNotifier
import com.nuvio.app.features.player.WindowsMpvLibrary
import com.nuvio.app.features.player.WindowsPlaybackPanel
import com.nuvio.app.features.player.WindowsPlayerWindowState
import com.nuvio.app.features.player.applyWindowsMpvSubtitleStyle
import com.nuvio.app.features.player.boundedWindowsMpvTrackCount
import com.nuvio.app.features.player.readMpvPropertyString
import com.nuvio.app.features.player.reapplyWindowsMpvSubtitleStyleLater
import com.nuvio.app.features.player.scheduleWindowsSwingAction
import com.nuvio.app.features.player.shouldRestoreWindowsFullscreen
import com.nuvio.app.features.player.withMpv
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Frame
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

internal class WindowsComposePlayerPanel(
    private val state: WindowsPlayerWindowState,
    private val onInitializationError: (String) -> Unit,
    onPlayerStateChanged: () -> Unit,
    private val onWindowFocusChanged: (Boolean, Long?) -> Unit,
    private val perfCollector: DesktopBackendPerfCollector?,
) : WindowsPlaybackPanel {
    val stats = ComposeVideoStats()
    val renderer = MpvGlVideoRenderer(stats)
    val videoVisible = mutableStateOf(false)
    var focusRequest: () -> Unit = {}

    @Volatile
    private var disposed = false
    private var window: Window? = null
    private var initializedPlayerPtr: Pointer? = null
    private var wakeupCallback: MpvWakeupCallback? = null
    private val fullscreenState = FullscreenWindowState()
    private val stateChangedNotifier = SwingStateNotifier(onPlayerStateChanged)
    private val eventPump = MpvEventPump(::readEvents)
    private val reveal = ComposeVideoRevealTracker()
    private val loader = WindowsComposeMpvLoader(
        state = state,
        isOwner = { !disposed && !state.isClosed && state.panelRef === this },
        notifyStateChanged = stateChangedNotifier::request,
        onUnrecoverable = { SwingUtilities.invokeLater { dispose() } },
    )
    private val focusListener = object : WindowAdapter() {
        override fun windowGainedFocus(e: WindowEvent) {
            onWindowFocusChanged(true, windowHandle())
        }

        override fun windowLostFocus(e: WindowEvent) {
            onWindowFocusChanged(false, windowHandle())
        }
    }
    private val stateListener = object : WindowAdapter() {
        override fun windowStateChanged(e: WindowEvent) {
            val iconified = (e.newState and Frame.ICONIFIED) != 0
            if (!shouldRestoreWindowsFullscreen(fullscreenState.isActive(e.window), iconified)) return
            SwingUtilities.invokeLater { if (!disposed) fullscreenState.restore(e.window) }
        }
    }

    init {
        state.panelRef = this
        state.isClosed = false
        renderer.onBeforeRender = { reveal.armForRender() }
        renderer.onFrameRendered = ::onFrameRendered
    }

    fun start(window: Window) {
        check(SwingUtilities.isEventDispatchThread())
        if (disposed || this.window != null) return
        this.window = window
        window.addWindowFocusListener(focusListener)
        window.addWindowStateListener(stateListener)
        onWindowFocusChanged(window.isFocused, windowHandle())
        val startNs = System.nanoTime()
        thread(name = "Kino-mpv-init", isDaemon = true) {
            val ptr = runCatching { createMpv() }.getOrElse { error ->
                SwingUtilities.invokeLater { fail(error.message ?: "Compose video initialization failed") }
                return@thread
            }
            SwingUtilities.invokeLater { attachRenderer(ptr, startNs) }
        }
    }

    private fun createMpv(): Pointer {
        val lib = WindowsMpvLibrary.INSTANCE
        val ptr = lib.mpv_create() ?: throw IllegalStateException("Failed to create libmpv instance")
        try {
            applyComposeMpvOptions(lib, ptr)
            val result = lib.mpv_initialize(ptr)
            if (result < 0) throw IllegalStateException("Failed to initialize libmpv: error code $result")
            observeComposeMpvProperties(lib, ptr)
        } catch (error: Throwable) {
            lib.mpv_terminate_destroy(ptr)
            throw error
        }
        initializedPlayerPtr = ptr
        return ptr
    }

    private fun attachRenderer(ptr: Pointer, startNs: Long) {
        if (disposed) {
            destroyMpvAsync(ptr)
            return
        }
        val error = renderer.attach(window, ptr)
        if (error != null) {
            initializedPlayerPtr = null
            destroyMpvAsync(ptr)
            fail("Compose video surface unavailable: $error")
            return
        }
        val lib = WindowsMpvLibrary.INSTANCE
        wakeupCallback = object : MpvWakeupCallback {
            override fun invoke(ctx: Pointer?) {
                eventPump.request()
            }
        }
        lib.mpv_set_wakeup_callback(ptr, wakeupCallback, null)
        state.playerPtr = ptr
        eventPump.request()
        stateChangedNotifier.request()
        composeVideoTrace("compose panel ready ms=${(System.nanoTime() - startNs) / 1_000_000}")
    }

    private fun fail(message: String) {
        composeVideoTrace("compose panel initialization failed message=$message")
        state.isClosed = true
        onInitializationError(message)
    }

    private fun onFrameRendered() {
        if (videoVisible.value) return
        if (reveal.shouldReveal(state)) {
            videoVisible.value = true
            composeVideoTrace("compose video visible=true generation=${state.sourceGeneration}")
        }
    }

    private fun hideVideo() {
        reveal.reset()
        if (videoVisible.value) {
            videoVisible.value = false
            composeVideoTrace("compose video visible=false generation=${state.sourceGeneration}")
        }
    }

    private fun readEvents() {
        state.mpvCallLock.withLock {
            val ptr = state.playerPtr ?: return
            if (state.isClosed) return
            val result = drainComposeMpvEvents(WindowsMpvLibrary.INSTANCE, ptr, state, reveal)
            if (result.count > 0) {
                perfCollector?.recordWakeup(result.count)
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
        if (disposed || state.isClosed || state.panelRef !== this) return
        hideVideo()
        loader.load(url, audioUrl, headers, onCommitted, forceResolve)
    }

    override fun loadSubtitleUrl(url: String) {
        state.withMpv { ptr ->
            WindowsMpvLibrary.INSTANCE.mpv_command(ptr, arrayOf("sub-add", url, "select", null))
            applyWindowsMpvSubtitleStyle(ptr, state.subtitleStyle)
            reapplyWindowsMpvSubtitleStyleLater(state, ptr)
        }
    }

    override fun applyResizeMode(resizeMode: PlayerResizeMode) {
        val (panscan, unscaled) = when (resizeMode) {
            PlayerResizeMode.Fit -> "0.0" to "no"
            PlayerResizeMode.Fill -> "1.0" to "no"
            PlayerResizeMode.Zoom -> "0.0" to "downscale-big"
        }
        state.withMpv { ptr ->
            WindowsMpvLibrary.INSTANCE.mpv_set_property_string(ptr, "panscan", panscan)
            WindowsMpvLibrary.INSTANCE.mpv_set_property_string(ptr, "video-unscaled", unscaled)
        }
        renderer.requestRender(force = true)
    }

    override fun retryPlayback() {
        val ptr = state.playerPtr ?: return
        val sourceUrl = state.currentSourceUrl.takeIf { it.isNotBlank() } ?: return
        val position = readMpvPropertyString(WindowsMpvLibrary.INSTANCE, ptr, "time-pos")?.toDoubleOrNull() ?: 0.0
        loadFile(
            sourceUrl,
            state.currentSourceAudioUrl,
            state.currentHeaders,
            onCommitted = { generation ->
                scheduleWindowsSwingAction(500) {
                    state.withMpv { current ->
                        if (current == ptr && state.sourceGeneration == generation) {
                            WindowsMpvLibrary.INSTANCE.mpv_command(
                                current,
                                arrayOf("seek", String.format("%.3f", position), "absolute", null),
                            )
                        }
                    }
                }
            },
            forceResolve = true,
        )
    }

    override fun clearExternalSubtitles() {
        state.withMpv { ptr ->
            val lib = WindowsMpvLibrary.INSTANCE
            val count = boundedWindowsMpvTrackCount(readMpvPropertyString(lib, ptr, "track-list/count"))
            for (index in count - 1 downTo 0) {
                val type = readMpvPropertyString(lib, ptr, "track-list/$index/type")
                val external = readMpvPropertyString(lib, ptr, "track-list/$index/external") == "yes"
                val id = readMpvPropertyString(lib, ptr, "track-list/$index/id")
                if (type == "sub" && external && id != null) lib.mpv_command(ptr, arrayOf("sub-remove", id, null))
            }
            lib.mpv_set_property_string(ptr, "sid", "no")
        }
    }

    override fun clearExternalSubtitlesAndSelect(trackIndex: Int) {
        clearExternalSubtitles()
        val track = state.subtitleTracks.getOrNull(trackIndex) ?: return
        state.withMpv { ptr -> WindowsMpvLibrary.INSTANCE.mpv_set_property_string(ptr, "sid", track.id) }
    }

    override fun toggleFullScreen() {
        val toggle = {
            window?.let { target ->
                if (fullscreenState.isActive(target)) fullscreenState.exit(target) else fullscreenState.enter(target)
            }
            Unit
        }
        if (SwingUtilities.isEventDispatchThread()) toggle() else SwingUtilities.invokeLater(toggle)
    }

    override fun requestInteractionFocus() {
        focusRequest()
    }

    fun exitFullScreen(): Boolean {
        val target = window ?: return false
        if (!fullscreenState.isActive(target)) return false
        fullscreenState.exit(target)
        return true
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        composeVideoTrace("compose panel dispose start")
        if (state.panelRef === this) {
            state.loadRequestGate.invalidate()
            state.panelRef = null
            state.isClosed = true
        }
        loader.close()
        eventPump.closeAndAwait()
        window?.let { target ->
            target.removeWindowFocusListener(focusListener)
            target.removeWindowStateListener(stateListener)
            fullscreenState.exit(target)
        }
        onWindowFocusChanged(false, null)
        val ptr = initializedPlayerPtr
        initializedPlayerPtr = null
        state.mpvCallLock.withLock {
            if (ptr != null) WindowsMpvLibrary.INSTANCE.mpv_set_wakeup_callback(ptr, null, null)
            if (state.playerPtr == ptr) state.playerPtr = null
        }
        if (SwingUtilities.isEventDispatchThread()) renderer.release() else SwingUtilities.invokeAndWait(renderer::release)
        wakeupCallback = null
        ptr?.let(::destroyMpvAsync)
        window = null
    }

    private fun destroyMpvAsync(ptr: Pointer) {
        thread(name = "Kino-mpv-destroy", isDaemon = true) {
            val startNs = System.nanoTime()
            WindowsMpvLibrary.INSTANCE.mpv_terminate_destroy(ptr)
            composeVideoTrace("compose panel mpv destroyed ms=${(System.nanoTime() - startNs) / 1_000_000}")
        }
    }

    private fun windowHandle(): Long? {
        val target = window ?: return null
        return runCatching { Pointer.nativeValue(Native.getComponentPointer(target)) }.getOrNull()?.takeIf { it != 0L }
    }
}
