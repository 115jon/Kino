package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.input.key.Key
import java.awt.Component
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities

internal class WindowsPlayerShortcutDispatcher(
    private val windowState: WindowsPlayerWindowState,
    private val callbacks: WindowsPlayerCallbacks,
) : KeyEventDispatcher {
    private val heldKeys = mutableSetOf<Int>()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.id == KeyEvent.KEY_RELEASED) heldKeys.remove(event.keyCode)
        val panel = windowState.panelRef ?: return false
        if (!panel.acceptsShortcutsFrom(event.component?.windowOrSelf())) return false
        if (event.id != KeyEvent.KEY_PRESSED) return false
        val repeated = !heldKeys.add(event.keyCode)
        val typing = event.component?.inputMethodRequests != null
        if (windowState.playback.startupStalled) {
            return when (windowsStartupStallKeyAction(event.keyCode)) {
                WindowsStartupStallKeyAction.Retry -> {
                    if (!typing && !repeated) panel.retryPlayback()
                    !typing
                }
                WindowsStartupStallKeyAction.Back -> dispatch(PlayerShortcut.Back)
                WindowsStartupStallKeyAction.Consume -> !typing
            }
        }
        val shortcut = event.toPlayerShortcut() ?: return false
        if (typing && !shortcut.allowedWhileTyping) return false
        if (repeated && !shortcut.repeatable) return true
        return dispatch(shortcut)
    }

    private fun dispatch(shortcut: PlayerShortcut): Boolean = callbacks.onShortcut?.invoke(shortcut) == true
}

internal fun KeyEvent.toPlayerShortcut(): PlayerShortcut? = playerShortcutFor(
    key = Key(keyCode, keyLocation.takeIf { it != KeyEvent.KEY_LOCATION_UNKNOWN } ?: KeyEvent.KEY_LOCATION_STANDARD),
    isShiftPressed = isShiftDown,
    isCtrlPressed = isControlDown,
    isAltPressed = isAltDown,
    isMetaPressed = isMetaDown,
)

private fun Component.windowOrSelf(): Window? = this as? Window ?: SwingUtilities.getWindowAncestor(this)

@Composable
internal fun WindowsPlayerShortcutEffect(
    windowState: WindowsPlayerWindowState,
    callbacks: WindowsPlayerCallbacks,
) {
    DisposableEffect(windowState, callbacks) {
        val dispatcher = WindowsPlayerShortcutDispatcher(windowState, callbacks)
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        focusManager.addKeyEventDispatcher(dispatcher)
        onDispose { focusManager.removeKeyEventDispatcher(dispatcher) }
    }
}
