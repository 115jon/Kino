package com.nuvio.app.features.player.compose

import com.sun.jna.Pointer
import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.SurfaceOrigin

internal class SharedGlContexts private constructor(
    private val skiko: SkikoGlContext,
    private val device: Pointer,
    private val skikoContextHandle: Long,
    private var mpvContext: Pointer?,
    val sync: GlSyncFunctions,
    val framebuffers: GlFramebufferFunctions,
) {
    val directContext: DirectContext? get() = skiko.directContext

    fun isSkikoContextCurrentHandle(): Boolean = skiko.contextHandle == skikoContextHandle

    fun makeSkikoCurrent(): Boolean = skiko.makeCurrent()

    fun makeMpvCurrent(): Boolean {
        val context = mpvContext ?: return false
        return OpenGl32Library.INSTANCE.wglMakeCurrent(device, context)
    }

    fun fenceInSkiko(): Pointer? {
        if (!makeSkikoCurrent()) return null
        val fence = sync.fence()
        OpenGl32Library.INSTANCE.glFlush()
        return fence
    }

    fun destroyMpvContext() {
        val context = mpvContext ?: return
        mpvContext = null
        makeSkikoCurrent()
        OpenGl32Library.INSTANCE.wglDeleteContext(context)
    }

    companion object {
        fun create(skiko: SkikoGlContext): Result<SharedGlContexts> = runCatching {
            check(skiko.makeCurrent()) { "skiko renderApi=${skiko.renderApi} is not an OpenGL context" }
            val contextHandle = skiko.contextHandle
            val deviceHandle = skiko.deviceHandle
            check(contextHandle != 0L && deviceHandle != 0L) { "skiko gl handles unavailable" }
            val sync = checkNotNull(GlSyncFunctions.resolve()) { "gl sync entry points unavailable" }
            val framebuffers = checkNotNull(GlFramebufferFunctions.resolve()) { "glFramebuffer entry points unavailable" }
            val device = Pointer(deviceHandle)
            val mpvContext = checkNotNull(sync.createSharedContext(device, Pointer(contextHandle))) {
                "wglCreateContextAttribsARB failed"
            }
            skiko.makeCurrent()
            SharedGlContexts(skiko, device, contextHandle, mpvContext, sync, framebuffers)
        }
    }
}

internal class SharedVideoTarget private constructor(
    val width: Int,
    val height: Int,
    val framebuffer: Int,
    val image: Image,
) {
    fun release(shared: SharedGlContexts) {
        if (shared.makeMpvCurrent()) shared.framebuffers.delete(framebuffer)
        shared.makeSkikoCurrent()
        image.close()
    }

    companion object {
        fun create(shared: SharedGlContexts, width: Int, height: Int): SharedVideoTarget? {
            if (!shared.makeSkikoCurrent()) return null
            val directContext = shared.directContext ?: return null
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
            gl.glFlush()
            directContext.resetGLAll()
            val image = Image.adoptTextureFrom(
                directContext,
                BackendTexture.makeGL(width, height, false, texture, GL_TEXTURE_2D, GL_RGBA8),
                SurfaceOrigin.TOP_LEFT,
                ColorType.RGBA_8888,
                ColorAlphaType.OPAQUE,
            )
            if (!shared.makeMpvCurrent()) {
                shared.makeSkikoCurrent()
                image.close()
                return null
            }
            val fb = shared.framebuffers
            val framebuffer = fb.gen()
            fb.bind(framebuffer)
            fb.attachTexture(texture)
            val status = fb.status()
            gl.glViewport(0, 0, width, height)
            gl.glClearColor(0f, 0f, 0f, 1f)
            gl.glClear(GL_COLOR_BUFFER_BIT)
            gl.glFlush()
            shared.makeSkikoCurrent()
            if (status != GL_FRAMEBUFFER_COMPLETE) {
                composeVideoTrace("compose video framebuffer incomplete status=$status size=${width}x$height")
                if (shared.makeMpvCurrent()) fb.delete(framebuffer)
                shared.makeSkikoCurrent()
                image.close()
                return null
            }
            return SharedVideoTarget(width, height, framebuffer, image)
        }
    }
}
