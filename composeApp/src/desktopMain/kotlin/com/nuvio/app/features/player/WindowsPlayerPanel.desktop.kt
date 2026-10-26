package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.RenderSettings
import androidx.compose.ui.graphics.Color as ComposeColor
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import androidx.compose.runtime.CompositionLocalProvider
import java.awt.*
import java.awt.event.*
import java.awt.image.BufferedImage
import javax.swing.*
import kotlin.concurrent.withLock
import kotlin.math.roundToInt

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
        override fun update(g: Graphics) = Unit

        override fun addNotify() {
            super.addNotify()
            urlResolutionScope.launch {
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

        override fun removeNotify() {
            shutdownMpv()
            super.removeNotify()
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
    private var videoSurfaceVisible = false
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
                    hideControls()
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
        background = playerTheme.panelBgColor
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
                if (shouldForwardWindowsPlayerClick(WindowsPlayerPointerTarget.Video)) {
                    onSurfaceInteraction(true)
                }
                showControls()
                canvas.requestFocusInWindow()
            }

            override fun mouseExited(e: MouseEvent) {
                if (!updatePointerPresence()) {
                    hideControls()
                }
            }
        }
        val overlayInteractionListener = object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                pointerInside = true
                onSurfaceInteraction(false)
                showControls()
            }
        }

        canvas.addMouseListener(interactionListener)
        canvas.addMouseMotionListener(interactionListener)
        overlayPanel.addMouseListener(createWindowsOverlayPointerListener {
            if (!updatePointerPresence()) {
                hideControls()
            }
        })
        overlayPanel.addMouseMotionListener(overlayInteractionListener)
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
            requireWindowsMpvOption(lib, ptr, "d3d11-exclusive-fs", "no")
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
        hideVideoSurfaceForLoad()
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
                        desktopPlayerTrace("native panel playback URL resolution failed generation=$loadGeneration")
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
                    val videoHeadersResult = runCatching {
                        lib.mpv_set_property_string(ptr, "http-header-fields", videoHeadersStr)
                    }.getOrDefault(-1)
                    if (videoHeadersResult < 0) {
                        state.pendingLoadRequestGeneration = null
                        state.terminalPlaybackError = "Failed to apply video headers"
                        state.terminalPlaybackErrorGeneration = loadGeneration
                        stateChangedNotifier.request()
                        desktopPlayerTrace("native panel video header setup failed result=$videoHeadersResult")
                        return@withLock
                    }
                    resetWindowsVideoPipeline(state)
                    desktopPlayerTrace(
                        "native panel loadFile host=${playbackUrlForLog(url)} " +
                            "effectiveHost=${playbackUrlForLog(resolved.url)} " +
                            "audioHost=${playbackUrlForLog(audioUrl)} " +
                            "effectiveAudioHost=${playbackUrlForLog(resolved.audioUrl)} " +
                            "videoHeaderKeys=${resolved.videoHeaders.keys.joinToString()} " +
                                "audioHeaderKeys=${resolved.audioHeaders.keys.joinToString()} " +
                                "videoHeaderBlobLength=${videoHeadersStr.length}"
                    )
                    applyWindowsMpvSubtitleStyle(ptr, state.subtitleStyle)
                    val discardedEvents = discardPendingWindowsMpvEvents(lib, ptr)
                    if (discardedEvents > 0) {
                        desktopPlayerTrace("native panel discarded pending mpv events count=$discardedEvents")
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
                        desktopPlayerTrace("native panel loadFile failed commandResult=$loadResult")
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
                                desktopPlayerTrace("native panel audio-add failed generation=$loadGeneration")
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
        videoOutputReady: Boolean,
    ) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater {
                updatePlaybackState(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    isPlaying = isPlaying,
                    isLoading = isLoading,
                    speed = speed,
                    volumePercent = volumePercent,
                    videoOutputReady = videoOutputReady,
                )
            }
            return
        }
        setVideoSurfaceVisible(videoOutputReady)
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
        desktopPlayerTrace(
            "native panel dispose start displayable=$isDisplayable " +
                "overlayVisible=${overlayWindow?.isVisible} overlayShowing=${overlayWindow?.isShowing}",
        )
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
        eventPump.closeAndAwait()
        state.mpvCallLock.withLock {
            val ptr = initializedPlayerPtr ?: return@withLock
            desktopPlayerTrace("native panel mpv shutdown start")
            if (state.playerPtr == ptr) {
                state.playerPtr = null
            }
            initializedPlayerPtr = null
            WindowsMpvLibrary.INSTANCE.mpv_set_wakeup_callback(ptr, null, null)
            WindowsMpvLibrary.INSTANCE.mpv_terminate_destroy(ptr)
            desktopPlayerTrace("native panel mpv shutdown complete")
        }
        wakeupCallback = null
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
