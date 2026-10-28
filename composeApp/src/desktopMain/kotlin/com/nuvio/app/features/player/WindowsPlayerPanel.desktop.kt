package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.RenderSettings
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Typography
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.LocalAppTheme
import com.nuvio.app.core.ui.LocalNuvioThemeTokens
import com.nuvio.app.core.ui.LocalNuvioTypeScale
import com.nuvio.app.core.ui.NuvioThemeTokens
import com.nuvio.app.core.ui.NuvioTypeScale
import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.compose.runtime.CompositionLocalProvider
import java.awt.*
import java.awt.event.*
import javax.swing.*

@OptIn(ExperimentalComposeUiApi::class)
internal class WindowsPlayerPanel(
    private val panelBackground: java.awt.Color,
    private val overlayColorScheme: ColorScheme,
    private val overlayTypography: Typography,
    private val overlayNuvioTokens: NuvioThemeTokens,
    private val overlayTypeScale: NuvioTypeScale,
    private val overlayAppTheme: AppTheme,
    private val overlayRippleConfiguration: RippleConfiguration?,
    private val state: WindowsPlayerWindowState,
    private val onInitializationError: (String) -> Unit,
    private val onPlayerStateChanged: () -> Unit,
    private val onSurfaceInteraction: (Boolean) -> Unit = {},
    private val onSurfaceExit: () -> Unit = {},
    private val onWindowFocusChanged: (Boolean, Long?) -> Unit = { _, _ -> },
    private val overlayContent: @Composable () -> Unit = {},
) : JPanel(BorderLayout()), WindowsPlaybackPanel {
    private val canvas = object : Canvas() {
        override fun update(g: Graphics) = Unit

        override fun addNotify() {
            super.addNotify()
            libraryLoadScope.launch {
                runCatching { WindowsMpvLibrary.INSTANCE }
                SwingUtilities.invokeLater { initializeMpvWhenDisplayable() }
            }
        }

        private fun initializeMpvWhenDisplayable() {
            if (playerDisposed || !isDisplayable) return
            if (!mpvInitialized) {
                try {
                    initializeMpv()
                    mpvInitialized = true
                } catch (error: Throwable) {
                    engine.close()
                    state.panelRef = null
                    libraryLoadScope.cancel()
                    onInitializationError(error.message ?: "Native video initialization failed")
                }
            }
            stateChangedNotifier.request()
        }

        override fun removeNotify() {
            shutdownMpv()
            super.removeNotify()
        }
    }

    private val fullscreenState = FullscreenWindowState { state.fullscreen.value = it }
    private var mpvInitialized = false
    private var videoSurfaceVisible = false
    @Volatile
    private var playerDisposed = false
    private val engine: MpvEngine get() = state.engine
    private val libraryLoadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateChangedNotifier = SwingStateNotifier(onPlayerStateChanged)
    private var playerWindow: Window? = null
    private var overlayWindow: JWindow? = null
    private var pointerInside = false
    private val controlsTimer = javax.swing.Timer(250) {
        updatePointerPresence()
    }
    private val videoLayer = JPanel()
    private val overlayPanel = ComposePanel(renderSettings = RenderSettings.SwingGraphics())
    private var overlayWindowUsesTransparency = true
    private val scheduleOverlayWindowBoundsUpdate = createCoalescedSwingUpdate {
        updateOverlayWindowBounds()
    }
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
            SwingUtilities.invokeLater { primeOverlayWindow() }
        }

        override fun windowLostFocus(e: WindowEvent) {
            SwingUtilities.invokeLater {
                if (!isPlayerWindowActive()) {
                    onWindowFocusChanged(false, windowHandle())
                    notifySurfaceExit()
                }
            }
        }
    }
    private val windowStateListener = object : WindowAdapter() {
        override fun windowStateChanged(e: WindowEvent) {
            val window = e.window
            val isIconified = (e.newState and Frame.ICONIFIED) != 0
            if (!shouldRestoreWindowsFullscreen(fullscreenState.isActive(window), isIconified)) return
            SwingUtilities.invokeLater {
                if (!playerDisposed) {
                    fullscreenState.restore(window)
                    scheduleOverlayWindowBoundsUpdate()
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
        engine.onStateChanged = stateChangedNotifier::request
        engine.onFatalError = { SwingUtilities.invokeLater { dispose() } }

        setupPanel()
        setupListeners()

        controlsTimer.start()
    }

    override fun addNotify() {
        desktopPlayerTrace("native panel addNotify begin visible=$isVisible displayable=$isDisplayable")
        super.addNotify()
        desktopPlayerTrace("native panel addNotify peerReady visible=$isVisible displayable=$isDisplayable")
        pointerInside = containsPlayerPointer()
        playerWindow = SwingUtilities.getWindowAncestor(this)?.also { window ->
            window.addWindowFocusListener(windowFocusListener)
            window.addWindowStateListener(windowStateListener)
            window.addComponentListener(windowBoundsListener)
            createOverlayWindow(window)
            SwingUtilities.invokeLater {
                desktopPlayerTrace(
                    "native panel visibility visible=$isVisible displayable=$isDisplayable " +
                        "ownerShowing=${window.isShowing} overlayShowing=${overlayWindow?.isShowing}",
                )
                onWindowFocusChanged(window.isFocused, windowHandle())
            }
        }
    }

    override fun removeNotify() {
        desktopPlayerTrace(
            "native panel removeNotify start displayable=$isDisplayable " +
                "overlayVisible=${overlayWindow?.isVisible} overlayShowing=${overlayWindow?.isShowing}",
        )
        controlsTimer.stop()
        pointerInside = false
        onWindowFocusChanged(false, null)
        playerWindow?.removeWindowFocusListener(windowFocusListener)
        playerWindow?.removeWindowStateListener(windowStateListener)
        playerWindow?.removeComponentListener(windowBoundsListener)
        playerWindow = null
        overlayWindow?.isVisible = false
        overlayWindow?.dispose()
        overlayWindow = null
        super.removeNotify()
        desktopPlayerTrace("native panel removeNotify complete displayable=$isDisplayable")
    }

    private fun setupPanel() {
        background = panelBackground
        isFocusable = true
        focusTraversalKeysEnabled = false
        canvas.isVisible = false
        canvas.background = java.awt.Color.BLACK
        canvas.ignoreRepaint = true
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
        setOverlayWindowTransparency(false)
        scheduleOverlayWindowBoundsUpdate()
        overlayWindow?.isVisible = owner.isShowing
        SwingUtilities.invokeLater {
            desktopPlayerTrace("native overlay window renderApi=${overlayPanel.renderApi}")
            primeOverlayWindow()
        }
    }

    private fun primeOverlayWindow() {
        val window = overlayWindow ?: return
        val renderApi = overlayPanel.renderApi.toString()
        val shouldPrime = shouldPrimeWindowsPlayerOverlay(
            windowShowing = window.isShowing,
            overlayDisplayable = overlayPanel.isDisplayable,
            renderApi = renderApi,
        )
        desktopPlayerTrace(
            "native overlay window prime shouldPrime=$shouldPrime " +
                "windowShowing=${window.isShowing} panelDisplayable=${overlayPanel.isDisplayable} " +
                "renderApi=$renderApi",
        )
        if (!shouldPrime) return
        overlayPanel.renderImmediately()
        setOverlayWindowTransparency(transparent = true)
        overlayPanel.renderImmediately()
        desktopPlayerTrace("native overlay window prime complete transparent=$overlayWindowUsesTransparency")
    }

    private fun setOverlayWindowTransparency(transparent: Boolean) {
        val window = overlayWindow ?: return
        val contentPane = window.contentPane as? JComponent ?: return
        if (overlayWindowUsesTransparency == transparent) return
        val fallbackBackground = java.awt.Color(12, 12, 12)
        val transparentBackground = java.awt.Color(0, 0, 0, 0)
        val background = if (transparent) transparentBackground else fallbackBackground
        overlayPanel.isOpaque = !transparent
        overlayPanel.background = background
        contentPane.isOpaque = !transparent
        contentPane.background = background
        window.background = background
        overlayWindowUsesTransparency = transparent
        desktopPlayerTrace(
            "native overlay window transparency=$transparent " +
                "windowVisible=${window.isVisible} windowShowing=${window.isShowing} " +
                "contentOpaque=${contentPane.isOpaque} panelOpaque=${overlayPanel.isOpaque}",
        )
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

    private fun setupListeners() {
        val interactionListener = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                pointerInside = true
                onSurfaceInteraction(false)
            }
            override fun mouseClicked(e: MouseEvent) {
                if (shouldForwardWindowsPlayerClick(WindowsPlayerPointerTarget.Video)) {
                    onSurfaceInteraction(true)
                }
                canvas.requestFocusInWindow()
            }

            override fun mouseExited(e: MouseEvent) {
                updatePointerPresence()
            }
        }
        val overlayInteractionListener = object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                pointerInside = true
                onSurfaceInteraction(false)
            }
        }

        canvas.addMouseListener(interactionListener)
        canvas.addMouseMotionListener(interactionListener)
        overlayPanel.addMouseListener(createWindowsOverlayPointerListener {
            updatePointerPresence()
        })
        overlayPanel.addMouseMotionListener(overlayInteractionListener)
        addMouseListener(interactionListener)
        addMouseMotionListener(interactionListener)

        overlayPanel.isFocusable = true

        val wheelListener = MouseWheelListener { e ->
            adjustVolume(-e.wheelRotation * 2.0)
        }
        canvas.addMouseWheelListener(wheelListener)
        addMouseWheelListener(wheelListener)

    }

    private fun initializeMpv() {
        val hwnd = Native.getComponentPointer(canvas)
        val hwndLong = Pointer.nativeValue(hwnd)
        desktopPlayerTrace(
            "native panel canvas displayable=${canvas.isDisplayable} showing=${canvas.isShowing} " +
                "size=${canvas.width}x${canvas.height} hwnd=$hwndLong"
        )
        if (hwndLong == 0L) {
            throw RuntimeException("Native video canvas has no HWND")
        }
        engine.start(WidOutput(hwndLong))
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
        if (playerDisposed || state.isClosed || state.panelRef !== this) return
        hideVideoSurfaceForLoad()
        engine.load(url, audioUrl, headers, onCommitted, forceResolve, startPositionMs)
    }

    fun updateVideoOutputReady(videoOutputReady: Boolean) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater {
                updateVideoOutputReady(videoOutputReady)
            }
            return
        }
        setVideoSurfaceVisible(videoOutputReady)
    }

    private fun setVideoSurfaceVisible(visible: Boolean) {
        if (visible) {
            setOverlayWindowTransparency(transparent = true)
        }
        if (videoSurfaceVisible == visible && canvas.isVisible == visible) return
        videoSurfaceVisible = visible
        canvas.isVisible = visible
        desktopPlayerTrace(
            "native video canvas visible=$visible displayable=${canvas.isDisplayable} " +
                "showing=${canvas.isShowing}",
        )
    }

    fun hideVideoSurfaceForLoad() {
        if (SwingUtilities.isEventDispatchThread()) {
            setVideoSurfaceVisible(false)
        } else {
            SwingUtilities.invokeLater {
                if (!playerDisposed) {
                    setVideoSurfaceVisible(false)
                }
            }
        }
    }

    override fun applyResizeMode(resizeMode: PlayerResizeMode) {
        engine.setResizeMode(resizeMode)
    }

    override fun retryPlayback() {
        if (!engine.isReady) return
        val playback = state.playback
        val sourceUrl = playback.currentSourceUrl.takeIf { it.isNotBlank() } ?: return
        val pos = engine.currentPositionSeconds()
        loadFile(
            sourceUrl,
            playback.currentSourceAudioUrl,
            playback.currentHeaders,
            onCommitted = { loadGeneration -> engine.restorePositionAfterLoad(loadGeneration, pos) },
            forceResolve = true,
        )
    }

    override fun clearExternalSubtitles() {
        engine.clearExternalSubtitles()
    }

    override fun clearExternalSubtitlesAndSelect(trackIndex: Int) {
        engine.clearExternalSubtitlesAndSelect(trackIndex)
    }

    override fun loadSubtitleUrl(url: String) {
        engine.addSubtitle(url, reapplyStyleLater = true)
    }

    private fun adjustVolume(delta: Double) {
        engine.adjustVolumePercent(delta)
    }

    override fun toggleFullScreen() {
        val toggle = fun() {
            val window = SwingUtilities.getWindowAncestor(this) as? Frame
            if (window != null) {
                if (fullscreenState.isActive(window)) {
                    fullscreenState.exit(window)
                } else {
                    fullscreenState.enter(window)
                }
                scheduleOverlayWindowBoundsUpdate()
                SwingUtilities.invokeLater {
                    scheduleOverlayWindowBoundsUpdate()
                }
            }
        }
        if (SwingUtilities.isEventDispatchThread()) toggle() else SwingUtilities.invokeLater(toggle)
    }

    override fun exitFullScreen() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(::exitFullScreen)
            return
        }
        val window = SwingUtilities.getWindowAncestor(this) ?: return
        if (!fullscreenState.isActive(window)) return
        fullscreenState.exit(window)
        scheduleOverlayWindowBoundsUpdate()
    }

    override fun acceptsShortcutsFrom(window: Window?): Boolean =
        window != null && (window === playerWindow || window === overlayWindow)

    override fun requestInteractionFocus() {
        requestFocusInWindow()
        canvas.requestFocusInWindow()
    }

    override fun dispose() {
        if (playerDisposed) return
        playerDisposed = true
        desktopPlayerTrace(
            "native panel dispose start displayable=$isDisplayable " +
                "overlayVisible=${overlayWindow?.isVisible} overlayShowing=${overlayWindow?.isShowing}",
        )
        controlsTimer.stop()
        val ownsState = state.panelRef === this
        if (ownsState) {
            state.panelRef = null
            engine.close()
        }
        libraryLoadScope.cancel()
        overlayWindow?.isVisible = false
        overlayWindow?.dispose()
        overlayWindow = null
        desktopPlayerTrace("native panel overlay disposed")
        shutdownMpv()
        onWindowFocusChanged(false, null)
        fullscreenState.exit(SwingUtilities.getWindowAncestor(this))
        val w = SwingUtilities.getWindowAncestor(this)
        if (w != null) {
            w.cursor = Cursor.getDefaultCursor()
        }
        desktopPlayerTrace("native panel dispose complete")
    }

    private fun shutdownMpv() {
        engine.shutdown()
    }
}
