package com.nuvio.app.features.player

import java.awt.Canvas
import java.awt.Component
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.im.InputMethodRequests
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsPlayerShortcutDispatcherTest {
    private val engine = MpvEngine(tracePrefix = "test panel", clientFactory = { FakeMpvClient() }, dispatcher = ManualMpvDispatcher())
    private val windowState = WindowsPlayerWindowState(engine)
    private val callbacks = WindowsPlayerCallbacks()
    private val panel = RecordingPanel()
    private val dispatcher = WindowsPlayerShortcutDispatcher(windowState, callbacks)
    private val received = mutableListOf<PlayerShortcut>()
    private val videoComponent = Canvas()

    init {
        windowState.panelRef = panel
        callbacks.onShortcut = { received += it; true }
    }

    @AfterTest
    fun tearDown() {
        engine.shutdown()
    }

    @Test
    fun `awt key codes map through the shared shortcut table`() {
        assertEquals(PlayerShortcut.TogglePlayback, keyEvent(KeyEvent.VK_SPACE).toPlayerShortcut())
        assertEquals(PlayerShortcut.ToggleFullscreen, keyEvent(KeyEvent.VK_F11).toPlayerShortcut())
        assertEquals(
            PlayerShortcut.ToggleFullscreen,
            keyEvent(KeyEvent.VK_F, location = KeyEvent.KEY_LOCATION_UNKNOWN).toPlayerShortcut(),
        )
        assertEquals(PlayerShortcut.Back, keyEvent(KeyEvent.VK_ESCAPE).toPlayerShortcut())
        assertEquals(PlayerShortcut.Speed(1), keyEvent(KeyEvent.VK_CLOSE_BRACKET).toPlayerShortcut())
        assertEquals(PlayerShortcut.Seek(-30_000L), keyEvent(KeyEvent.VK_LEFT, KeyEvent.SHIFT_DOWN_MASK).toPlayerShortcut())
        assertEquals(PlayerShortcut.ShortcutHelp, keyEvent(KeyEvent.VK_SLASH, KeyEvent.SHIFT_DOWN_MASK).toPlayerShortcut())
        assertEquals(
            PlayerShortcut.SeekToTenth(7),
            keyEvent(KeyEvent.VK_NUMPAD7, location = KeyEvent.KEY_LOCATION_NUMPAD).toPlayerShortcut(),
        )
        assertEquals(null, keyEvent(KeyEvent.VK_C, KeyEvent.CTRL_DOWN_MASK).toPlayerShortcut())
    }

    @Test
    fun `shortcuts fire regardless of which player component has focus`() {
        assertTrue(dispatcher.dispatchKeyEvent(keyEvent(KeyEvent.VK_ESCAPE)))
        assertTrue(dispatcher.dispatchKeyEvent(keyEvent(KeyEvent.VK_F)))
        assertEquals(listOf(PlayerShortcut.Back, PlayerShortcut.ToggleFullscreen), received)
    }

    @Test
    fun `keys from other windows pass through`() {
        panel.accepts = false
        assertFalse(dispatcher.dispatchKeyEvent(keyEvent(KeyEvent.VK_ESCAPE)))
        assertTrue(received.isEmpty())
    }

    @Test
    fun `text fields keep their keys except escape`() {
        val textField = TextInputComponent()
        assertFalse(dispatcher.dispatchKeyEvent(keyEvent(KeyEvent.VK_K, source = textField)))
        assertFalse(dispatcher.dispatchKeyEvent(keyEvent(KeyEvent.VK_SPACE, source = textField)))
        assertTrue(dispatcher.dispatchKeyEvent(keyEvent(KeyEvent.VK_ESCAPE, source = textField)))
        assertEquals(listOf<PlayerShortcut>(PlayerShortcut.Back), received)
    }

    @Test
    fun `held toggles fire once while held seeks repeat`() {
        repeat(3) { dispatcher.dispatchKeyEvent(keyEvent(KeyEvent.VK_F)) }
        repeat(3) { dispatcher.dispatchKeyEvent(keyEvent(KeyEvent.VK_RIGHT)) }
        dispatcher.dispatchKeyEvent(keyEvent(KeyEvent.VK_F, id = KeyEvent.KEY_RELEASED))
        dispatcher.dispatchKeyEvent(keyEvent(KeyEvent.VK_F))
        assertEquals(
            listOf(PlayerShortcut.ToggleFullscreen) + List(3) { PlayerShortcut.Seek(10_000L) } + PlayerShortcut.ToggleFullscreen,
            received,
        )
    }

    @Test
    fun `unmapped keys pass through`() {
        assertFalse(dispatcher.dispatchKeyEvent(keyEvent(KeyEvent.VK_ENTER)))
        assertFalse(dispatcher.dispatchKeyEvent(keyEvent(KeyEvent.VK_TAB)))
        assertTrue(received.isEmpty())
    }

    @Test
    fun `startup stall keeps retry and back`() {
        windowState.playback.startupStalled = true
        assertTrue(dispatcher.dispatchKeyEvent(keyEvent(KeyEvent.VK_R)))
        assertTrue(dispatcher.dispatchKeyEvent(keyEvent(KeyEvent.VK_SPACE)))
        assertTrue(dispatcher.dispatchKeyEvent(keyEvent(KeyEvent.VK_ESCAPE)))
        assertEquals(1, panel.retries)
        assertEquals(listOf<PlayerShortcut>(PlayerShortcut.Back), received)
    }

    private fun keyEvent(
        keyCode: Int,
        modifiers: Int = 0,
        location: Int = KeyEvent.KEY_LOCATION_STANDARD,
        id: Int = KeyEvent.KEY_PRESSED,
        source: Component = videoComponent,
    ) = KeyEvent(source, id, 0L, modifiers, keyCode, KeyEvent.CHAR_UNDEFINED, location)

    private class TextInputComponent : Canvas() {
        override fun getInputMethodRequests(): InputMethodRequests? =
            java.lang.reflect.Proxy.newProxyInstance(
                InputMethodRequests::class.java.classLoader,
                arrayOf(InputMethodRequests::class.java),
            ) { _, _, _ -> null } as InputMethodRequests
    }

    private class RecordingPanel : WindowsPlaybackPanel {
        var accepts = true
        var retries = 0

        override fun acceptsShortcutsFrom(window: Window?): Boolean = accepts
        override fun loadFile(
            url: String,
            audioUrl: String?,
            headers: Map<String, String>,
            onCommitted: ((Long) -> Unit)?,
            forceResolve: Boolean,
            startPositionMs: Long?,
        ) = Unit
        override fun loadSubtitleUrl(url: String) = Unit
        override fun applyResizeMode(resizeMode: PlayerResizeMode) = Unit
        override fun retryPlayback() {
            retries += 1
        }
        override fun clearExternalSubtitles() = Unit
        override fun clearExternalSubtitlesAndSelect(trackIndex: Int) = Unit
        override fun dispose() = Unit
    }
}
