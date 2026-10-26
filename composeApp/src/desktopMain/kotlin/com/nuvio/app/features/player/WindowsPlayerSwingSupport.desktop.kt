package com.nuvio.app.features.player

import java.awt.*
import java.awt.event.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.*
import kotlin.math.roundToInt

internal class SwingStateNotifier(
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

internal fun windowsComposeColor(color: androidx.compose.ui.graphics.Color): java.awt.Color =
    java.awt.Color(
        (color.red * 255f).roundToInt().coerceIn(0, 255),
        (color.green * 255f).roundToInt().coerceIn(0, 255),
        (color.blue * 255f).roundToInt().coerceIn(0, 255),
        (color.alpha * 255f).roundToInt().coerceIn(0, 255),
    )

internal fun Component.containsCurrentPointer(): Boolean {
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

internal fun createCoalescedSwingUpdate(update: () -> Unit): () -> Unit {
    val pending = AtomicBoolean(false)
    return {
        if (pending.compareAndSet(false, true)) {
            SwingUtilities.invokeLater {
                pending.set(false)
                update()
            }
        }
    }
}

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
