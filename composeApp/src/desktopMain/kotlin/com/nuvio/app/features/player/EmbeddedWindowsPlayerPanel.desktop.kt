package com.nuvio.app.features.player

import com.jogamp.opengl.GL
import com.jogamp.opengl.GLAutoDrawable
import com.jogamp.opengl.GLCapabilities
import com.jogamp.opengl.GLEventListener
import com.jogamp.opengl.GLProfile
import com.jogamp.opengl.awt.GLJPanel
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.awt.*
import java.awt.event.*
import javax.swing.*
import kotlin.concurrent.withLock
import kotlin.math.roundToInt

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
        lib.mpv_observe_property(ptr, 0, "aid", MPV_FORMAT_INT64)
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
                    recordWindowsAudioUnderrun(state, event.data)
                    traceMpvLogMessage(event.data)
                }
                MPV_EVENT_PROPERTY_CHANGE -> {
                    markWindowsAudioDiagnosticsRequested(state, event.data)
                    hasUpdates = true
                    eventCount += 1
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
            state.lastTrackMetadataSignature = null
            state.audioDiagnosticsRequested = false
            state.pendingLoadRequestGeneration = requestGeneration
            state.hasLoadedMedia = false
            state.startupStallSinceMs = 0L
            state.startupStalled = false
            state.lastReportedPlaybackError = null
            state.terminalPlaybackError = null
            state.terminalPlaybackErrorGeneration = null
            state.terminalPlaybackEndGeneration = null
            state.pendingPlaylistEntryId = null
            state.pendingPlaylistEntryGeneration = null
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
                    if (shouldAbortWindowsPlaybackLoad(resolved)) {
                        state.pendingLoadRequestGeneration = null
                        state.terminalPlaybackError = "Playback URL resolution failed"
                        state.terminalPlaybackErrorGeneration = loadGeneration
                        desktopPlayerTrace("embedded panel playback URL resolution failed generation=$loadGeneration")
                        return@withLock
                    }
                    state.pendingLoadRequestGeneration = null
                    state.lastReportedPlaybackError = null
                    state.startupStallSinceMs = 0L
                    state.startupStalled = false
                    state.hasLoadedMedia = false
                    state.activePlaylistEntryId = null
                    state.activePlaylistEntryGeneration = null
                    state.pendingPlaylistEntryId = null
                    state.pendingPlaylistEntryGeneration = null
                    state.terminalPlaybackError = null
                    state.terminalPlaybackErrorGeneration = null
                    state.terminalPlaybackEndGeneration = null
                    state.initialSeekRequestedAtMs = 0L
                    state.initialSeekTargetMs = 0L
                    state.initialSeekRecoveryIssued = false
                    val videoHeadersStr = encodeWindowsPlaybackHeaders(resolved.videoHeaders)
                    desktopPlayerTrace(
                        "embedded panel loadFile host=${playbackUrlForLog(url)} " +
                            "effectiveHost=${playbackUrlForLog(resolved.url)} " +
                            "audioHost=${playbackUrlForLog(audioUrl)} " +
                            "effectiveAudioHost=${playbackUrlForLog(resolved.audioUrl)} " +
                            "videoHeaderKeys=${resolved.videoHeaders.keys.joinToString()} " +
                            "audioHeaderKeys=${resolved.audioHeaders.keys.joinToString()} " +
                            "videoHeaderBlobLength=${videoHeadersStr.length}"
                    )
                    resetWindowsVideoPipeline(state)
                    val discardedEvents = discardPendingWindowsMpvEvents(lib, ptr)
                    if (discardedEvents > 0) {
                        desktopPlayerTrace("embedded panel discarded pending mpv events count=$discardedEvents")
                    }
                    applyWindowsMpvSubtitleStyle(ptr, state.subtitleStyle)
                    val videoHeadersResult = runCatching {
                        lib.mpv_set_property_string(ptr, "http-header-fields", videoHeadersStr)
                    }.getOrDefault(-1)
                    if (videoHeadersResult < 0) {
                        state.pendingLoadRequestGeneration = null
                        state.terminalPlaybackError = "Failed to apply video headers"
                        state.terminalPlaybackErrorGeneration = loadGeneration
                        stateChangedNotifier.request()
                        desktopPlayerTrace("embedded panel video header setup failed result=$videoHeadersResult")
                        return@withLock
                    }
                    val loadResult = runCatching {
                        lib.mpv_command(ptr, arrayOf("loadfile", resolved.url, "replace", null))
                    }.getOrDefault(-1)
                    if (loadResult >= 0) {
                        committedGeneration = loadGeneration
                    }
                    state.pendingPlaylistEntryId = loadResult.takeIf { it >= 0 }
                        ?.let { readMpvPropertyString(lib, ptr, "playlist/0/id")?.toLongOrNull() }
                    state.pendingPlaylistEntryGeneration = state.pendingPlaylistEntryId?.let { loadGeneration }
                    selectWindowsPlaybackCommandError(loadResult)?.let { playbackError ->
                        state.terminalPlaybackError = playbackError
                        state.terminalPlaybackErrorGeneration = loadGeneration
                        stateChangedNotifier.request()
                        desktopPlayerTrace("embedded panel loadFile failed commandResult=$loadResult")
                    }
                    if (shouldScheduleWindowsAudioAdd(loadResult, true, false) && !resolved.audioUrl.isNullOrBlank()) {
                        scheduleWindowsSwingAction(500) {
                            val audioResult = withWindowsAudioAddIfCurrent(
                                lock = state.mpvCallLock,
                                isCurrent = {
                                    shouldScheduleWindowsAudioAdd(
                                        loadResult = loadResult,
                                        isCurrent = canCommitLoad(requestGeneration, ptr) &&
                                            state.sourceGeneration == loadGeneration,
                                        hasTerminalPlaybackError =
                                            state.terminalPlaybackErrorGeneration == loadGeneration ||
                                                state.terminalPlaybackEndGeneration == loadGeneration,
                                    )
                                },
                                videoHeaders = resolved.videoHeaders,
                                audioHeaders = resolved.audioHeaders,
                                setHeaders = { headerBlob ->
                                    lib.mpv_set_property_string(ptr, "http-header-fields", headerBlob)
                                },
                                audioAdd = {
                                    lib.mpv_command(ptr, arrayOf("audio-add", resolved.audioUrl, "select", null))
                                },
                            )
                            if (
                                audioResult != WindowsAudioAttachmentResult.Success &&
                                audioResult != WindowsAudioAttachmentResult.NotCurrent &&
                                canCommitLoad(requestGeneration, ptr) &&
                                state.sourceGeneration == loadGeneration &&
                                state.terminalPlaybackErrorGeneration != loadGeneration &&
                                state.terminalPlaybackEndGeneration != loadGeneration
                            ) {
                                state.terminalPlaybackError = if (
                                    audioResult == WindowsAudioAttachmentResult.HeaderRestoreFailed
                                ) {
                                    val stopResult = runCatching {
                                        lib.mpv_command(ptr, arrayOf("stop", null))
                                    }.getOrDefault(-1)
                                    if (stopResult < 0) {
                                        state.loadRequestGate.invalidate()
                                        state.pendingLoadRequestGeneration = null
                                        SwingUtilities.invokeLater { dispose() }
                                    }
                                    "Failed to restore video headers"
                                } else {
                                    "Failed to add audio track"
                                }
                                state.terminalPlaybackErrorGeneration = loadGeneration
                                stateChangedNotifier.request()
                                desktopPlayerTrace("embedded panel audio-add failed generation=$loadGeneration")
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
        val count = boundedWindowsMpvTrackCount(getString("track-list/count"))
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
