package com.nuvio.app.features.player

import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsOverlayInteractionTest {
    @Test
    fun overlayPressDoesNotRequestCanvasFocus() {
        var exitCount = 0
        val listener = createWindowsOverlayPointerListener { exitCount += 1 }

        listener.mousePressed(MouseEvent(JPanel(), MouseEvent.MOUSE_PRESSED, 0L, 0, 0, 0, 1, false))

        assertEquals(0, exitCount)
    }

    @Test
    fun overlayExitReportsPointerExit() {
        var exitCount = 0
        val listener = createWindowsOverlayPointerListener { exitCount += 1 }

        listener.mouseExited(MouseEvent(JPanel(), MouseEvent.MOUSE_EXITED, 0L, 0, 0, 0, 0, false))

        assertEquals(1, exitCount)
    }

    @Test
    fun stalledOverlayUsesDedicatedKeyboardActions() {
        assertEquals(
            WindowsStartupStallKeyAction.Retry,
            windowsStartupStallKeyAction(KeyEvent.VK_R),
        )
        assertEquals(
            WindowsStartupStallKeyAction.Back,
            windowsStartupStallKeyAction(KeyEvent.VK_ESCAPE),
        )
        assertEquals(
            WindowsStartupStallKeyAction.Consume,
            windowsStartupStallKeyAction(KeyEvent.VK_SPACE),
        )
    }
}
