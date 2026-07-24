package com.nuvio.app

import java.awt.Container
import java.awt.EventQueue
import java.awt.Window
import java.awt.Color as AwtColor
import javax.swing.JPanel
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkiaLayer

private class WindowsVrrCompatibility(
    private val layer: SkiaLayer,
) : AutoCloseable {
    private var locker: JPanel? = null
    private var closed = false

    init {
        layer.onStateChanged(SkiaLayer.PropertyKind.Renderer) {
            if (EventQueue.isDispatchThread()) {
                update()
            } else {
                EventQueue.invokeLater {
                    if (!closed) update()
                }
            }
        }
        update()
    }

    private fun update() {
        if (closed) return
        val shouldLock = shouldInstallWindowsVrrCompatibility(layer.renderApi)
        if (shouldLock && locker == null) {
            val newLocker = JPanel().apply {
                isFocusable = false
                background = AwtColor(0, 0, 0, 0)
                setBounds(0, 0, 1, 1)
            }
            locker = newLocker
            layer.add(newLocker, 0)
            layer.revalidate()
        } else if (!shouldLock) {
            locker?.let {
                layer.remove(it)
                locker = null
                layer.revalidate()
                layer.repaint()
            }
        }
    }

    override fun close() {
        closed = true
        locker?.let {
            layer.remove(it)
            locker = null
            layer.revalidate()
            layer.repaint()
        }
    }
}

internal fun shouldInstallWindowsVrrCompatibility(renderApi: GraphicsApi): Boolean =
    renderApi == GraphicsApi.DIRECT3D || renderApi == GraphicsApi.OPENGL

internal fun installWindowsVrrCompatibility(window: Window): AutoCloseable? {
    if (!isWindowsPlatform()) return null
    val layer = findSkiaLayer(window) ?: return null
    return WindowsVrrCompatibility(layer)
}

private fun findSkiaLayer(container: Container): SkiaLayer? {
    container.components.forEach { component ->
        if (component is SkiaLayer) return component
        if (component is Container) {
            findSkiaLayer(component)?.let { return it }
        }
    }
    return null
}
