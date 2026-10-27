package com.nuvio.app.features.player.compose

import com.sun.jna.Pointer
import org.jetbrains.skia.DirectContext

internal class SharedGlContexts private constructor(
    private val skiko: SkikoGlContext,
    private val device: Pointer,
    private val skikoContextHandle: Long,
    private val mpvContext: Pointer,
    val sync: GlSyncFunctions,
    val framebuffers: GlFramebufferFunctions,
) {
    @Volatile
    private var mpvContextDeleted = false

    val directContext: DirectContext? get() = skiko.directContext

    fun isSkikoContextAlive(): Boolean = skiko.contextHandle == skikoContextHandle

    fun ensureSkikoCurrent(): Boolean {
        val current = OpenGl32Library.INSTANCE.wglGetCurrentContext()
        if (current != null && Pointer.nativeValue(current) == skikoContextHandle) return true
        return isSkikoContextAlive() && skiko.makeCurrent()
    }

    fun makeMpvCurrent(): Boolean =
        !mpvContextDeleted && OpenGl32Library.INSTANCE.wglMakeCurrent(device, mpvContext)

    fun deleteMpvContext() {
        if (mpvContextDeleted) return
        mpvContextDeleted = true
        val gl = OpenGl32Library.INSTANCE
        gl.wglMakeCurrent(null, null)
        gl.wglDeleteContext(mpvContext)
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
