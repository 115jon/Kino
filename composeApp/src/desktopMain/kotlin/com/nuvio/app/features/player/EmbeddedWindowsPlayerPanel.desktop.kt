package com.nuvio.app.features.player

import com.jogamp.opengl.GL
import com.jogamp.opengl.GLAutoDrawable
import com.jogamp.opengl.GLCapabilities
import com.jogamp.opengl.GLEventListener
import com.jogamp.opengl.GLProfile
import com.jogamp.opengl.awt.GLJPanel
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.*
import java.awt.event.*
import javax.swing.*

private fun createGlCapabilities(): GLCapabilities {
    WindowsJoglNativeLoader.ensureConfigured()
    GLProfile.initSingleton()
    return GLCapabilities(GLProfile.getDefault())
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
    private val engine: MpvEngine get() = state.engine
    private val fullscreenState = FullscreenWindowState()
    private val stateChangedNotifier = SwingStateNotifier(onPlayerStateChanged)
    private val output = RenderApiOutput {
        perfCollector?.recordRenderUpdateCallback()
        scheduleRender(force = false)
    }
    private val renderStateLock = Any()
    private var renderScheduled = false
    private var forceRenderRequested = false
    private var renderEnqueuedAtNs = 0L
    private val framebufferBinding = IntArray(1)
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
    private val windowStateListener = object : WindowAdapter() {
        override fun windowStateChanged(e: WindowEvent) {
            val window = e.window
            val isIconified = (e.newState and Frame.ICONIFIED) != 0
            if (!shouldRestoreWindowsFullscreen(fullscreenState.isActive(window), isIconified)) return
            SwingUtilities.invokeLater {
                if (!playerDisposed) fullscreenState.restore(window)
            }
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
        engine.onStateChanged = stateChangedNotifier::request
        engine.onFatalError = { SwingUtilities.invokeLater { dispose() } }
        engine.onLoadCommitted = {
            SwingUtilities.invokeLater {
                if (!playerDisposed) {
                    requestFocusInWindow()
                    glPanel.requestFocusInWindow()
                    scheduleRender(force = true)
                }
            }
        }
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
                if (state.playback.startupStalled) {
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
            window.addWindowStateListener(windowStateListener)
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
        playerWindow?.removeWindowStateListener(windowStateListener)
        playerWindow = null
        super.removeNotify()
    }

    private fun initializeMpv(drawable: GLAutoDrawable) {
        engine.start(output)
        desktopPlayerTrace(
            "embedded panel initialized with opengl render api " +
                "glVendor=${drawable.gl.glGetString(GL.GL_VENDOR) ?: ""} " +
                "glRenderer=${drawable.gl.glGetString(GL.GL_RENDERER) ?: ""} " +
                "glVersion=${drawable.gl.glGetString(GL.GL_VERSION) ?: ""}"
        )
        stateChangedNotifier.request()
        scheduleRender(force = true)
    }

    override fun loadFile(
        url: String,
        audioUrl: String?,
        headers: Map<String, String>,
        onCommitted: ((Long) -> Unit)?,
        forceResolve: Boolean,
    ) {
        if (!engine.isReady) return
        if (playerDisposed || state.isClosed || state.panelRef !== this) return
        engine.load(url, audioUrl, headers, onCommitted, forceResolve)
    }

    override fun loadSubtitleUrl(url: String) {
        if (!engine.isReady) return
        engine.addSubtitle(url, reapplyStyleLater = true)
        scheduleRender(force = true)
    }

    override fun applyResizeMode(resizeMode: PlayerResizeMode) {
        if (!engine.isReady) return
        engine.setResizeMode(resizeMode)
        scheduleRender(force = true)
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
        if (!engine.isReady) return
        engine.clearExternalSubtitles()
        scheduleRender(force = true)
    }

    override fun clearExternalSubtitlesAndSelect(trackIndex: Int) {
        if (!engine.isReady) return
        engine.clearExternalSubtitlesAndSelect(trackIndex)
        scheduleRender(force = true)
    }

    override fun toggleFullScreen() {
        val toggle = fun() {
            val window = SwingUtilities.getWindowAncestor(this)
            if (window != null) {
                if (fullscreenState.isActive(window)) {
                    fullscreenState.exit(window)
                } else {
                    fullscreenState.enter(window)
                }
            }
        }
        if (SwingUtilities.isEventDispatchThread()) toggle() else SwingUtilities.invokeLater(toggle)
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
            state.panelRef = null
            engine.close()
        }
        engine.closeEvents()
        onWindowFocusChanged(false, null)
        desktopPlayerTrace("embedded panel dispose start displayable=${glPanel.isDisplayable}")
        fullscreenState.exit(SwingUtilities.getWindowAncestor(this))
        output.clearUpdateCallback()
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
        desktopPlayerTrace(
            "embedded panel release resources render=${output.renderContext != null} player=${engine.isReady}"
        )
        engine.shutdown()
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
        if (output.renderContext == null) return
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
        val shouldRender = force || output.hasNewFrame()
        if (!shouldRender) {
            return
        }
        drawable.gl.glGetIntegerv(GL.GL_FRAMEBUFFER_BINDING, framebufferBinding, 0)
        val renderRet = output.render(framebufferBinding[0], width, height) ?: return
        if (renderRet < 0) {
            desktopPlayerTrace("mpv opengl render failed code=$renderRet width=$width height=$height")
        }
        drawable.gl.glFlush()
    }

    private fun togglePlayPause() {
        engine.togglePause()
    }

    private fun seekByOffset(offsetMs: Long) {
        engine.seekBy(offsetMs)
    }

    private fun adjustVolume(delta: Float) {
        engine.setVolumeLevelNow((state.playback.volumeLevel.fraction + delta).coerceIn(0f, 1f))
    }
}
