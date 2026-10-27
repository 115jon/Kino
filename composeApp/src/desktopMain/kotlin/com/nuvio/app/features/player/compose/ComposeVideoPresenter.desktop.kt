package com.nuvio.app.features.player.compose

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.nuvio.app.features.player.MpvEngine
import java.awt.Window
import javax.swing.SwingUtilities
import org.jetbrains.skia.Image

internal class ComposeVideoPresenter(
    private val engine: MpvEngine,
    private val stats: ComposeVideoStats,
    private val onStarted: () -> Unit,
    private val onFailed: (String) -> Unit,
) {
    val frameSerial = mutableIntStateOf(0)
    val visible = mutableStateOf(false)
    private val tracker = ComposeVideoRevealTracker()
    private var contexts: SharedGlContexts? = null
    private var ring: VideoFrameRing? = null
    private var renderThread: ComposeVideoRenderThread? = null

    @Volatile
    private var released = false

    fun start(window: Window): String? {
        check(SwingUtilities.isEventDispatchThread())
        val skiko = SkikoGlContext.find(window) ?: return "skia layer not found"
        val shared = SharedGlContexts.create(skiko).getOrElse { return it.message ?: "shared gl context unavailable" }
        val frames = VideoFrameRing(shared, stats) { visible.value }
        contexts = shared
        ring = frames
        renderThread = ComposeVideoRenderThread(engine, shared, frames, stats, renderCallbacks).also { it.start() }
        return null
    }

    fun setTargetSize(width: Int, height: Int) {
        renderThread?.setTargetSize(width, height)
    }

    fun requestRender(force: Boolean) {
        renderThread?.requestRender(force)
    }

    fun onPlaybackRestart(generation: Long) {
        tracker.onPlaybackRestart(generation)
        renderThread?.requestRender(force = true)
    }

    fun hide() {
        tracker.reset()
        if (visible.value) {
            visible.value = false
            composeVideoTrace("compose video visible=false generation=${engine.playback.sourceGeneration}")
        }
    }

    fun acquireImage(): Image? {
        if (released) return null
        val shared = contexts ?: return null
        if (!shared.ensureSkikoCurrent()) return null
        return ring?.acquireImage()
    }

    fun release() {
        check(SwingUtilities.isEventDispatchThread())
        if (released) return
        released = true
        val thread = renderThread
        if (thread == null) {
            contexts?.deleteMpvContext()
            return
        }
        thread.stop()
    }

    private fun disposeFrames() {
        val shared = contexts ?: return
        val frames = ring ?: return
        if (shared.ensureSkikoCurrent()) {
            frames.disposeOnSkiko()
            composeVideoTrace("compose video frames released")
        } else {
            composeVideoTrace("compose video frames abandoned reason=skiko-context-unavailable")
        }
    }

    private val renderCallbacks = object : ComposeVideoRenderThread.Callbacks {
        override fun onStarted() {
            onStarted.invoke()
        }

        override fun onStartFailed(message: String) {
            SwingUtilities.invokeLater { onFailed(message) }
        }

        override fun onBeforeRender() {
            tracker.armForRender()
        }

        override fun onFramePublished() {
            frameSerial.intValue += 1
            if (!visible.value && tracker.shouldReveal(engine.playback)) {
                visible.value = true
                composeVideoTrace("compose video visible=true generation=${engine.playback.sourceGeneration}")
            }
        }

        override fun onStopped() {
            SwingUtilities.invokeLater(::disposeFrames)
        }
    }
}
