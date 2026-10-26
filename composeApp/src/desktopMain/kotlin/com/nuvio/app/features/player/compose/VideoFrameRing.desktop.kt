package com.nuvio.app.features.player.compose

import com.sun.jna.Pointer
import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.SurfaceOrigin

private const val VideoFrameSlotCount = 3

internal class VideoFrameSlot(
    val texture: Int,
    val framebuffer: Int,
    val width: Int,
    val height: Int,
) {
    var writeFence: Pointer? = null
    var readFence: Pointer? = null
    var image: Image? = null
}

internal class VideoFrameRing(private val contexts: SharedGlContexts) {
    private val lock = Any()
    private var slots: List<VideoFrameSlot> = emptyList()
    private var latest: VideoFrameSlot? = null
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
        }
        composeVideoTrace("compose video ring allocated size=${width}x$height slots=$VideoFrameSlotCount")
        return true
    }

    fun beginWrite(): VideoFrameSlot? {
        var readFence: Pointer? = null
        var staleWriteFence: Pointer? = null
        val slot = synchronized(lock) {
            slots.firstOrNull { it !== latest && it !== displayed }?.also { slot ->
                readFence = slot.readFence
                staleWriteFence = slot.writeFence
                slot.readFence = null
                slot.writeFence = null
            }
        }
        contexts.sync.delete(staleWriteFence)
        contexts.sync.waitAndDelete(readFence)
        return slot
    }

    fun publish(slot: VideoFrameSlot) {
        val fence = contexts.sync.fence()
        OpenGl32Library.INSTANCE.glFlush()
        synchronized(lock) {
            slot.writeFence = fence
            latest = slot
            publishedFrames += 1
        }
    }

    fun acquireImage(): Image? {
        val disposals: List<VideoFrameSlot>
        val slot = synchronized(lock) {
            if (closed) return null
            val next = latest
            if (next != null && next !== displayed) {
                displayed?.takeIf { it in slots }?.let { previous ->
                    contexts.sync.delete(previous.readFence)
                    previous.readFence = contexts.sync.fence()
                }
                contexts.sync.waitAndDelete(next.writeFence)
                next.writeFence = null
                displayed = next
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
            latest = null
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
        contexts.sync.delete(slot.writeFence)
        slot.readFence = null
        slot.writeFence = null
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
