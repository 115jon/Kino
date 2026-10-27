package com.nuvio.app

import co.touchlab.kermit.Logger
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.awt.EventQueue
import java.awt.Window
import org.jetbrains.skia.Canvas
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkiaLayerProperties
import org.jetbrains.skiko.SkikoRenderDelegate

private const val CompositorClockTimeoutMs = 100
private const val IdleFrameGapNs = 25_000_000L
private const val WaitFailed = -1

private val compositorClockLogger = Logger.withTag("WindowsCompositorClock")

private interface DCompositionLibrary : Library {
    fun DCompositionWaitForCompositorClock(count: Int, handles: Pointer?, timeoutInMs: Int): Int

    companion object {
        val INSTANCE: DCompositionLibrary? by lazy {
            runCatching {
                NativeLibrary.getInstance("dcomp").getFunction("DCompositionWaitForCompositorClock")
                Native.load("dcomp", DCompositionLibrary::class.java)
            }.getOrNull()
        }
    }
}

private class WindowsCompositorClockPacing(
    private val layer: SkiaLayer,
    private val delegate: SkikoRenderDelegate,
    private val clock: DCompositionLibrary,
) : SkikoRenderDelegate, AutoCloseable {
    private var paced = false
    private var closed = false
    private var lastRenderNs = 0L

    init {
        layer.renderDelegate = this
        layer.onStateChanged(SkiaLayer.PropertyKind.Renderer) {
            EventQueue.invokeLater(::updatePacing)
        }
        updatePacing()
    }

    private fun updatePacing() {
        if (closed) return
        val shouldPace = shouldPaceWithCompositorClock(layer.renderApi)
        paced = shouldPace && setDwmFlush(enabled = false)
        if (!paced) setDwmFlush(enabled = true)
        compositorClockLogger.i { "compositor clock pacing renderApi=${layer.renderApi} paced=$paced" }
    }

    override fun onRender(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
        var frameNanos = nanoTime
        if (paced && System.nanoTime() - lastRenderNs < IdleFrameGapNs) {
            if (clock.DCompositionWaitForCompositorClock(0, null, CompositorClockTimeoutMs) == WaitFailed) {
                paced = false
                setDwmFlush(enabled = true)
                compositorClockLogger.w { "compositor clock wait failed; falling back to DwmFlush" }
            }
            frameNanos = System.nanoTime()
        }
        lastRenderNs = System.nanoTime()
        delegate.onRender(canvas, width, height, frameNanos)
    }

    private fun setDwmFlush(enabled: Boolean): Boolean = runCatching {
        if (layer.properties.isVsyncEnabled != enabled) vsyncField.setBoolean(layer.properties, enabled)
        layer.properties.isVsyncEnabled == enabled
    }.getOrDefault(false)

    override fun close() {
        if (closed) return
        closed = true
        paced = false
        setDwmFlush(enabled = true)
        if (layer.renderDelegate === this) layer.renderDelegate = delegate
    }

    companion object {
        private val vsyncField by lazy {
            SkiaLayerProperties::class.java.getDeclaredField("isVsyncEnabled").apply { isAccessible = true }
        }
    }
}

internal fun shouldPaceWithCompositorClock(renderApi: GraphicsApi): Boolean = renderApi == GraphicsApi.OPENGL

internal fun installWindowsCompositorClockPacing(window: Window): AutoCloseable? {
    if (!isWindowsPlatform()) return null
    if (System.getProperty("kino.windows.compositor-clock-pacing") == "false") return null
    val layer = findSkiaLayer(window) ?: return null
    if (!layer.properties.isVsyncEnabled) return null
    val delegate = layer.renderDelegate ?: return null
    val clock = DCompositionLibrary.INSTANCE ?: return null
    return WindowsCompositorClockPacing(layer, delegate, clock)
}
