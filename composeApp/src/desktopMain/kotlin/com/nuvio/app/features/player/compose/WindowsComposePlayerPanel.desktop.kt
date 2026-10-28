package com.nuvio.app.features.player.compose

import com.nuvio.app.features.player.DesktopBackendPerfCollector
import com.nuvio.app.features.player.FullscreenWindowState
import com.nuvio.app.features.player.MpvEngine
import com.nuvio.app.features.player.PlayerResizeMode
import com.nuvio.app.features.player.SwingStateNotifier
import com.nuvio.app.features.player.WindowsPlaybackPanel
import com.nuvio.app.features.player.WindowsPlayerWindowState
import com.nuvio.app.features.player.shouldRestoreWindowsFullscreen
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Frame
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities

internal class WindowsComposePlayerPanel(
    private val state: WindowsPlayerWindowState,
    private val onInitializationError: (String) -> Unit,
    onPlayerStateChanged: () -> Unit,
    private val onWindowFocusChanged: (Boolean, Long?) -> Unit,
    perfCollector: DesktopBackendPerfCollector?,
) : WindowsPlaybackPanel {
    val stats = ComposeVideoStats()
    val engine: MpvEngine get() = state.engine
    var focusRequest: () -> Unit = {}

    @Volatile
    private var disposed = false
    private var window: Window? = null
    private var startNs = 0L
    private val fullscreenState = FullscreenWindowState { state.fullscreen.value = it }
    private val stateChangedNotifier = SwingStateNotifier(onPlayerStateChanged)
    val presenter = ComposeVideoPresenter(
        engine = state.engine,
        stats = stats,
        onStarted = ::onEngineStarted,
        onFailed = ::fail,
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
        engine.perfCollector = perfCollector
        engine.onStateChanged = stateChangedNotifier::request
        engine.onFatalError = { SwingUtilities.invokeLater { dispose() } }
        engine.onPlaybackRestart = presenter::onPlaybackRestart
        engine.onLoadCommitted = { presenter.requestRender(force = true) }
    }

    fun start(window: Window) {
        check(SwingUtilities.isEventDispatchThread())
        if (disposed || this.window != null) return
        this.window = window
        window.addWindowFocusListener(focusListener)
        window.addWindowStateListener(stateListener)
        onWindowFocusChanged(window.isFocused, windowHandle())
        startNs = System.nanoTime()
        presenter.start(window)?.let { error -> fail("Compose video surface unavailable: $error") }
    }

    private fun onEngineStarted() {
        stateChangedNotifier.request()
        composeVideoTrace("compose panel ready ms=${(System.nanoTime() - startNs) / 1_000_000}")
    }

    private fun fail(message: String) {
        if (disposed) return
        composeVideoTrace("compose panel initialization failed message=$message")
        state.isClosed = true
        onInitializationError(message)
    }

    override fun loadFile(
        url: String,
        audioUrl: String?,
        headers: Map<String, String>,
        onCommitted: ((Long) -> Unit)?,
        forceResolve: Boolean,
        startPositionMs: Long?,
    ) {
        if (!engine.isReady) return
        if (disposed || state.isClosed || state.panelRef !== this) return
        presenter.hide()
        engine.load(url, audioUrl, headers, onCommitted, forceResolve, startPositionMs)
    }

    override fun loadSubtitleUrl(url: String) {
        if (!engine.isReady) return
        engine.addSubtitle(url, reapplyStyleLater = true)
    }

    override fun applyResizeMode(resizeMode: PlayerResizeMode) {
        if (!engine.isReady) return
        engine.setResizeMode(resizeMode)
        presenter.requestRender(force = true)
    }

    override fun retryPlayback() {
        if (!engine.isReady) return
        val playback = state.playback
        val sourceUrl = playback.currentSourceUrl.takeIf { it.isNotBlank() } ?: return
        val position = engine.currentPositionSeconds()
        loadFile(
            sourceUrl,
            playback.currentSourceAudioUrl,
            playback.currentHeaders,
            onCommitted = { generation -> engine.restorePositionAfterLoad(generation, position) },
            forceResolve = true,
        )
    }

    override fun clearExternalSubtitles() {
        if (!engine.isReady) return
        engine.clearExternalSubtitles()
    }

    override fun clearExternalSubtitlesAndSelect(trackIndex: Int) {
        if (!engine.isReady) return
        engine.clearExternalSubtitlesAndSelect(trackIndex)
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

    override fun exitFullScreen() {
        val exit = {
            window?.let { target -> if (fullscreenState.isActive(target)) fullscreenState.exit(target) }
            Unit
        }
        if (SwingUtilities.isEventDispatchThread()) exit() else SwingUtilities.invokeLater(exit)
    }

    override fun acceptsShortcutsFrom(window: Window?): Boolean = window != null && window === this.window

    override fun dispose() {
        if (disposed) return
        disposed = true
        composeVideoTrace("compose panel dispose start")
        if (state.panelRef === this) {
            state.panelRef = null
            engine.close()
        }
        engine.closeEvents()
        window?.let { target ->
            target.removeWindowFocusListener(focusListener)
            target.removeWindowStateListener(stateListener)
            fullscreenState.exit(target)
        }
        onWindowFocusChanged(false, null)
        presenter.release()
        window = null
    }

    private fun windowHandle(): Long? {
        val target = window ?: return null
        return runCatching { Pointer.nativeValue(Native.getComponentPointer(target)) }.getOrNull()?.takeIf { it != 0L }
    }
}
