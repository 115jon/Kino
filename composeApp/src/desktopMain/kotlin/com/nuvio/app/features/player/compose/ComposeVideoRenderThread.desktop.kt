package com.nuvio.app.features.player.compose

import com.nuvio.app.features.player.MpvEngine
import com.nuvio.app.features.player.RenderApiOutput
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

private const val RenderIdleParkNs = 250_000_000L

internal class ComposeVideoRenderThread(
    private val engine: MpvEngine,
    private val contexts: SharedGlContexts,
    private val ring: VideoFrameRing,
    private val stats: ComposeVideoStats,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onStarted()
        fun onStartFailed(message: String)
        fun onBeforeRender()
        fun onFramePublished()
        fun onStopped()
    }

    private val output = RenderApiOutput(
        extraOptions = composeVideoMpvOptions(),
        logLevel = "info",
    ) {
        stats.recordUpdateCallback()
        requestRender(force = false)
    }
    private val pending = AtomicBoolean(false)
    private val forceRequested = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val reportSwap = composeVideoReportsSwap()

    @Volatile
    private var targetWidth = 1920

    @Volatile
    private var targetHeight = 1080

    @Volatile
    private var requestedAtNs = 0L

    private val thread = Thread(::run, "Kino-video-render").apply {
        isDaemon = true
        priority = Thread.MAX_PRIORITY - 1
    }

    fun start() {
        thread.start()
    }

    fun setTargetSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == targetWidth && height == targetHeight) return
        targetWidth = width
        targetHeight = height
        requestRender(force = true)
    }

    fun requestRender(force: Boolean) {
        if (force) forceRequested.set(true)
        if (pending.compareAndSet(false, true)) requestedAtNs = System.nanoTime()
        LockSupport.unpark(thread)
    }

    fun stop() {
        stopRequested.set(true)
        output.clearUpdateCallback()
        LockSupport.unpark(thread)
    }

    private fun run() {
        if (!contexts.makeMpvCurrent()) {
            callbacks.onStartFailed("mpv gl context could not be made current")
            cleanup(engineStarted = false)
            return
        }
        val started = runCatching { engine.start(output) }
        started.exceptionOrNull()?.let { error ->
            callbacks.onStartFailed(error.message ?: "Compose video initialization failed")
            cleanup(engineStarted = false)
            return
        }
        val gl = OpenGl32Library.INSTANCE
        composeVideoTrace(
            "compose video render thread started glVendor=${gl.readString(GL_VENDOR)} " +
                "glRenderer=${gl.readString(GL_RENDERER)} glVersion=${gl.readString(GL_VERSION)}",
        )
        callbacks.onStarted()
        requestRender(force = true)
        while (!stopRequested.get()) {
            if (!pending.getAndSet(false)) {
                LockSupport.parkNanos(this, RenderIdleParkNs)
                continue
            }
            renderOnce()
        }
        cleanup(engineStarted = true)
    }

    private fun renderOnce() {
        val queuedNs = requestedAtNs
        val force = forceRequested.getAndSet(false)
        if (!output.hasNewFrame() && !force) return
        if (!ring.prepare(targetWidth, targetHeight)) return
        val slot = ring.beginWrite() ?: run {
            requestRender(force)
            return
        }
        callbacks.onBeforeRender()
        val startNs = System.nanoTime()
        val result = output.render(slot.framebuffer, slot.width, slot.height, GL_RGBA8) ?: return
        ring.publish(slot)
        if (reportSwap) output.reportSwap()
        stats.recordRender(System.nanoTime() - startNs, startNs - queuedNs)
        if (result < 0) {
            composeVideoTrace("compose video render failed result=$result")
            return
        }
        callbacks.onFramePublished()
    }

    private fun cleanup(engineStarted: Boolean) {
        if (engineStarted) engine.shutdown()
        if (contexts.makeMpvCurrent()) ring.releaseFramebuffers()
        contexts.deleteMpvContext()
        composeVideoTrace("compose video render thread stopped")
        callbacks.onStopped()
    }
}
