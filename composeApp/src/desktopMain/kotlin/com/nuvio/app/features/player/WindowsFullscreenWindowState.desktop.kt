package com.nuvio.app.features.player

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.*

private const val WindowsGwlStyle = -16
private const val WindowsWsBorder = 0x00800000L
private const val WindowsWsCaption = 0x00C00000L
private const val WindowsWsThickFrame = 0x00040000L
private const val WindowsWsMinimizeBox = 0x00020000L
private const val WindowsWsMaximizeBox = 0x00010000L
private const val WindowsWsSysMenu = 0x00080000L
private const val WindowsWsPopup = 0x80000000L
private const val WindowsSwpNoZOrder = 0x0004
private const val WindowsSwpNoActivate = 0x0010
private const val WindowsSwpFrameChanged = 0x0020

private const val WindowsFullscreenDecorationMask =
    WindowsWsBorder or WindowsWsCaption or WindowsWsThickFrame or
        WindowsWsMinimizeBox or WindowsWsMaximizeBox or WindowsWsSysMenu

internal fun toWindowsBorderlessStyle(style: Long): Long =
    (style and WindowsFullscreenDecorationMask.inv()) or WindowsWsPopup

private interface WindowsFullscreenLibrary : Library {
    fun GetWindowLongPtrW(window: Pointer, index: Int): Pointer

    fun SetWindowLongPtrW(window: Pointer, index: Int, value: Pointer): Pointer

    fun SetWindowPos(
        window: Pointer,
        insertAfter: Pointer?,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        flags: Int,
    ): Boolean

    companion object {
        val INSTANCE: WindowsFullscreenLibrary by lazy {
            Native.load("user32", WindowsFullscreenLibrary::class.java)
        }
    }
}

internal data class WindowedPresentationState(
    val bounds: Rectangle,
    val extendedState: Int?,
    val background: Color,
    val style: Long?,
)

internal class FullscreenWindowState(
    private val onActiveChanged: (Boolean) -> Unit = {},
) {
    var activeDevice: GraphicsDevice? = null
    var windowedState: WindowedPresentationState? = null
    private var activeWindow: Window? = null
    private var usesExclusiveFallback = false

    fun enter(window: Window) {
        val targetDevice = window.graphicsConfiguration?.device
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        if (activeDevice === targetDevice && activeWindow === window) return

        exit(window)
        windowedState = WindowedPresentationState(
            bounds = window.bounds,
            extendedState = (window as? Frame)?.extendedState,
            background = window.background,
            style = readWindowsWindowStyle(window),
        )
        activeDevice = targetDevice
        activeWindow = window
        val originalStyle = windowedState?.style
        val borderlessStyleApplied = originalStyle != null && applyWindowsWindowStyle(
            window,
            toWindowsBorderlessStyle(originalStyle),
        )
        usesExclusiveFallback = !borderlessStyleApplied
        if (usesExclusiveFallback) {
            targetDevice.fullScreenWindow = window
        } else {
            (window as? Frame)?.extendedState = Frame.NORMAL
            window.background = Color.BLACK
            window.bounds = targetDevice.defaultConfiguration.bounds
            window.validate()
        }
        onActiveChanged(true)
    }

    fun exit(window: Window?) {
        val wasActive = this.activeWindow != null
        val activeWindow = this.activeWindow ?: window ?: activeDevice?.fullScreenWindow
        val device = activeDevice
        if (device != null && (usesExclusiveFallback || device.fullScreenWindow === activeWindow)) {
            device.fullScreenWindow = null
        }
        if (activeWindow != null) {
            windowedState?.let { previous ->
                val frame = activeWindow as? Frame
                if (!usesExclusiveFallback && previous.style != null) {
                    applyWindowsWindowStyle(activeWindow, previous.style)
                }
                frame?.extendedState = Frame.NORMAL
                activeWindow.background = previous.background
                activeWindow.bounds = previous.bounds
                val targetExtendedState = previous.extendedState
                if (frame != null && targetExtendedState != null && targetExtendedState != Frame.NORMAL) {
                    frame.extendedState = targetExtendedState
                }
            }
        }
        activeDevice = null
        this.activeWindow = null
        windowedState = null
        usesExclusiveFallback = false
        if (wasActive) onActiveChanged(false)
    }

    fun isActive(window: Window): Boolean = activeWindow === window

    fun restore(window: Window) {
        val device = activeDevice ?: return
        if (activeWindow !== window) return
        if (usesExclusiveFallback) {
            if (device.fullScreenWindow !== window) device.fullScreenWindow = window
        } else {
            applyWindowsWindowStyle(window, toWindowsBorderlessStyle(windowedState?.style ?: return))
            window.background = Color.BLACK
            window.bounds = device.defaultConfiguration.bounds
        }
        (window as? Frame)?.extendedState = Frame.NORMAL
    }
}

private fun readWindowsWindowStyle(window: Window): Long? = runCatching {
    val handle = Native.getComponentPointer(window)
    if (Pointer.nativeValue(handle) == 0L) return@runCatching null
    Pointer.nativeValue(WindowsFullscreenLibrary.INSTANCE.GetWindowLongPtrW(handle, WindowsGwlStyle))
}.getOrNull()

private fun applyWindowsWindowStyle(window: Window, style: Long): Boolean = runCatching {
    val handle = Native.getComponentPointer(window)
    if (Pointer.nativeValue(handle) == 0L) return@runCatching false
    val previousStyle = WindowsFullscreenLibrary.INSTANCE.SetWindowLongPtrW(
        handle,
        WindowsGwlStyle,
        Pointer.createConstant(style),
    )
    val frameChanged = WindowsFullscreenLibrary.INSTANCE.SetWindowPos(
        handle,
        null,
        window.x,
        window.y,
        window.width,
        window.height,
        WindowsSwpNoZOrder or WindowsSwpNoActivate or WindowsSwpFrameChanged,
    )
    if (!frameChanged) {
        WindowsFullscreenLibrary.INSTANCE.SetWindowLongPtrW(handle, WindowsGwlStyle, previousStyle)
    }
    frameChanged
}.getOrDefault(false)
