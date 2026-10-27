package com.nuvio.app.features.player.compose

import com.sun.jna.Pointer
import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.SurfaceOrigin

private const val VideoFrameSlotCount = 4
private const val MaxPendingFrames = 2
private const val FenceTimeoutNs = 100_000_000L

internal class VideoFrameSlot(
    val texture: Int,
    val framebuffer: Int,
    val width: Int,
    val height: Int,
) {
    var readFence: Pointer? = null
    var image: Image? = null
}

internal class VideoFrameRing(
    private val contexts: SharedGlContexts,
    private val stats: ComposeVideoStats,
    private val isPresenting: () -> Boolean,
) {
    private val lock = Any()
    private var slots: List<VideoFrameSlot> = emptyList()
    private val pending = VideoFrameQueue<VideoFrameSlot>(MaxPendingFrames)
    private var displayed: VideoFrameSlot? = null
    private val retired = ArrayList<VideoFrameSlot>()
    private var closed = false

    @Volatile
    var publishedFrames = 0L
        private set

    fun prepare(width: Int, height: Int): Boolean {
        val current = synchronized(lock) { slots }
        if (current.isNotEmpty() && current[0].width == width && current[0].height == height) return true
        val created = ArrayList<VideoFrameSlot>(VideoFrameSlotCount)
        repeat(VideoFrameSlotCount) {
            val slot = createSlot(width, height)
            if (slot == null) {
                created.forEach(::abandonSlot)
                return false
            }
            created += slot
        }
        synchronized(lock) {
            slots.forEach { old ->
                contexts.framebuffers.delete(old.framebuffer)
                retired += old
            }
            slots = created
            pending.clear()
        }
        composeVideoTrace("compose video ring allocated size=${width}x$height slots=$VideoFrameSlotCount")
        return true
    }

    fun beginWrite(): VideoFrameSlot? {
        var readFence: Pointer? = null
        val slot = synchronized(lock) {
            slots.firstOrNull { it !== displayed && it !in pending }?.also { slot ->
                readFence = slot.readFence
                slot.readFence = null
            }
        }
        contexts.sync.awaitAndDelete(readFence, FenceTimeoutNs)
        return slot
    }

    fun publish(slot: VideoFrameSlot, redraw: Boolean) {
        if (!contexts.sync.awaitAndDelete(contexts.sync.fence(), FenceTimeoutNs)) {
            composeVideoTrace("compose video frame fence timed out")
        }
        synchronized(lock) {
            val presenting = isPresenting()
            val dropped = pending.push(slot, redraw, presenting)
            if (presenting) repeat(dropped) { stats.recordUnshownFrame() }
            publishedFrames += 1
        }
    }

    fun keepNewestPending() {
        synchronized(lock) { pending.keepNewest() }
    }

    fun hasPendingFrames(): Boolean = synchronized(lock) { pending.size > 0 }

    fun acquireImage(): Image? {
        val disposals: List<VideoFrameSlot>
        val slot = synchronized(lock) {
            if (closed) return null
            stats.recordPendingDepth(pending.size)
            val next = pending.take()
            if (next != null) {
                displayed?.takeIf { it in slots }?.let { previous ->
                    contexts.sync.delete(previous.readFence)
                    previous.readFence = contexts.sync.fence()
                }
                displayed = next
                stats.recordPresent(System.nanoTime())
            }
            disposals = retired.filter { it !== displayed }
            retired.removeAll(disposals)
            displayed
        }
        disposals.forEach(::disposeOnSkiko)
        return slot?.let(::imageFor)
    }

    fun releaseFramebuffers() {
        val current = synchronized(lock) { slots }
        current.forEach { contexts.framebuffers.delete(it.framebuffer) }
    }

    fun disposeOnSkiko() {
        val all = synchronized(lock) {
            closed = true
            val everything = slots + retired
            slots = emptyList()
            retired.clear()
            pending.clear()
            displayed = null
            everything
        }
        all.forEach(::disposeOnSkiko)
    }

    private fun imageFor(slot: VideoFrameSlot): Image? {
        slot.image?.let { return it }
        val directContext = contexts.directContext ?: return null
        return Image.adoptTextureFrom(
            directContext,
            BackendTexture.makeGL(slot.width, slot.height, false, slot.texture, GL_TEXTURE_2D, GL_RGBA8),
            SurfaceOrigin.TOP_LEFT,
            ColorType.RGBA_8888,
            ColorAlphaType.OPAQUE,
        ).also { slot.image = it }
    }

    private fun disposeOnSkiko(slot: VideoFrameSlot) {
        contexts.sync.delete(slot.readFence)
        slot.readFence = null
        val image = slot.image
        slot.image = null
        if (image != null) image.close() else OpenGl32Library.INSTANCE.glDeleteTextures(1, intArrayOf(slot.texture))
    }

    private fun abandonSlot(slot: VideoFrameSlot) {
        contexts.framebuffers.delete(slot.framebuffer)
        OpenGl32Library.INSTANCE.glDeleteTextures(1, intArrayOf(slot.texture))
    }

    private fun createSlot(width: Int, height: Int): VideoFrameSlot? {
        val gl = OpenGl32Library.INSTANCE
        val textures = IntArray(1)
        gl.glGenTextures(1, textures)
        val texture = textures[0]
        gl.glBindTexture(GL_TEXTURE_2D, texture)
        gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        gl.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
        gl.glBindTexture(GL_TEXTURE_2D, 0)
        val fb = contexts.framebuffers
        val framebuffer = fb.gen()
        fb.bind(framebuffer)
        fb.attachTexture(texture)
        val status = fb.status()
        gl.glViewport(0, 0, width, height)
        gl.glClearColor(0f, 0f, 0f, 1f)
        gl.glClear(GL_COLOR_BUFFER_BIT)
        fb.bind(0)
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            composeVideoTrace("compose video framebuffer incomplete status=$status size=${width}x$height")
            fb.delete(framebuffer)
            gl.glDeleteTextures(1, intArrayOf(texture))
            return null
        }
        return VideoFrameSlot(texture, framebuffer, width, height)
    }
}
