package com.nuvio.app.features.player.compose

import com.nuvio.app.features.player.resolveOpenGlProcAddress
import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

internal const val GL_TEXTURE_2D = 0x0DE1
internal const val GL_RGBA = 0x1908
internal const val GL_RGBA8 = 0x8058
internal const val GL_UNSIGNED_BYTE = 0x1401
internal const val GL_TEXTURE_MIN_FILTER = 0x2801
internal const val GL_TEXTURE_MAG_FILTER = 0x2800
internal const val GL_TEXTURE_WRAP_S = 0x2802
internal const val GL_TEXTURE_WRAP_T = 0x2803
internal const val GL_LINEAR = 0x2601
internal const val GL_CLAMP_TO_EDGE = 0x812F
internal const val GL_FRAMEBUFFER = 0x8D40
internal const val GL_COLOR_ATTACHMENT0 = 0x8CE0
internal const val GL_FRAMEBUFFER_COMPLETE = 0x8CD5
internal const val GL_FRAMEBUFFER_BINDING = 0x8CA6
internal const val GL_TEXTURE_BINDING_2D = 0x8069
internal const val GL_COLOR_BUFFER_BIT = 0x4000
internal const val GL_VENDOR = 0x1F00
internal const val GL_RENDERER = 0x1F01
internal const val GL_VERSION = 0x1F02
internal const val GL_SYNC_GPU_COMMANDS_COMPLETE = 0x9117
private const val GL_TIMEOUT_IGNORED = -1L

internal interface OpenGl32Library : Library {
    fun glGenTextures(count: Int, textures: IntArray)
    fun glDeleteTextures(count: Int, textures: IntArray)
    fun glBindTexture(target: Int, texture: Int)
    fun glTexParameteri(target: Int, name: Int, value: Int)
    fun glTexImage2D(
        target: Int,
        level: Int,
        internalFormat: Int,
        width: Int,
        height: Int,
        border: Int,
        format: Int,
        type: Int,
        pixels: Pointer?,
    )
    fun glGetIntegerv(name: Int, values: IntArray)
    fun glGetString(name: Int): Pointer?
    fun glGetError(): Int
    fun glFlush()
    fun glViewport(x: Int, y: Int, width: Int, height: Int)
    fun glClearColor(red: Float, green: Float, blue: Float, alpha: Float)
    fun glClear(mask: Int)
    fun wglGetCurrentContext(): Pointer?
    fun wglGetCurrentDC(): Pointer?
    fun wglMakeCurrent(hdc: Pointer?, hglrc: Pointer?): Boolean
    fun wglDeleteContext(hglrc: Pointer?): Boolean

    companion object {
        val INSTANCE: OpenGl32Library by lazy { Native.load("opengl32", OpenGl32Library::class.java) }
    }
}

internal class GlFramebufferFunctions private constructor(
    private val genFramebuffers: Function,
    private val deleteFramebuffers: Function,
    private val bindFramebuffer: Function,
    private val framebufferTexture2D: Function,
    private val checkFramebufferStatus: Function,
) {
    fun gen(): Int {
        val ids = IntArray(1)
        genFramebuffers.invoke(arrayOf(1, ids))
        return ids[0]
    }

    fun delete(id: Int) {
        if (id != 0) deleteFramebuffers.invoke(arrayOf(1, intArrayOf(id)))
    }

    fun bind(id: Int) {
        bindFramebuffer.invoke(arrayOf(GL_FRAMEBUFFER, id))
    }

    fun attachTexture(texture: Int) {
        framebufferTexture2D.invoke(arrayOf(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0))
    }

    fun status(): Int = checkFramebufferStatus.invokeInt(arrayOf(GL_FRAMEBUFFER))

    companion object {
        fun resolve(): GlFramebufferFunctions? {
            fun function(name: String): Function? =
                resolveOpenGlProcAddress(name)
                    ?.takeIf { Pointer.nativeValue(it) != 0L }
                    ?.let { Function.getFunction(it, Function.ALT_CONVENTION) }
            return GlFramebufferFunctions(
                genFramebuffers = function("glGenFramebuffers") ?: return null,
                deleteFramebuffers = function("glDeleteFramebuffers") ?: return null,
                bindFramebuffer = function("glBindFramebuffer") ?: return null,
                framebufferTexture2D = function("glFramebufferTexture2D") ?: return null,
                checkFramebufferStatus = function("glCheckFramebufferStatus") ?: return null,
            )
        }
    }
}

internal class GlSyncFunctions private constructor(
    private val fenceSync: Function,
    private val waitSync: Function,
    private val deleteSync: Function,
    private val createContextAttribs: Function,
) {
    fun fence(): Pointer? = fenceSync.invokePointer(arrayOf(GL_SYNC_GPU_COMMANDS_COMPLETE, 0))

    fun waitAndDelete(sync: Pointer?) {
        if (sync == null) return
        waitSync.invokeVoid(arrayOf(sync, 0, GL_TIMEOUT_IGNORED))
        deleteSync.invokeVoid(arrayOf(sync))
    }

    fun createSharedContext(hdc: Pointer, share: Pointer): Pointer? =
        createContextAttribs.invokePointer(arrayOf(hdc, share, intArrayOf(0)))

    companion object {
        fun resolve(): GlSyncFunctions? {
            fun function(name: String): Function? =
                resolveOpenGlProcAddress(name)
                    ?.takeIf { Pointer.nativeValue(it) != 0L }
                    ?.let { Function.getFunction(it, Function.ALT_CONVENTION) }
            return GlSyncFunctions(
                fenceSync = function("glFenceSync") ?: return null,
                waitSync = function("glWaitSync") ?: return null,
                deleteSync = function("glDeleteSync") ?: return null,
                createContextAttribs = function("wglCreateContextAttribsARB") ?: return null,
            )
        }
    }
}

internal fun OpenGl32Library.readString(name: Int): String =
    runCatching { glGetString(name)?.getString(0) }.getOrNull().orEmpty()
