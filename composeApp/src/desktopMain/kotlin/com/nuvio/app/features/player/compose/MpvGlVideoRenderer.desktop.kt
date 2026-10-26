package com.nuvio.app.features.player.compose

import androidx.compose.runtime.mutableIntStateOf
import com.nuvio.app.features.player.MpvOpenGlFbo
import com.nuvio.app.features.player.MpvOpenGlGetProcAddressCallback
import com.nuvio.app.features.player.MpvOpenGlInitParams
import com.nuvio.app.features.player.MpvRenderUpdateCallback
import com.nuvio.app.features.player.WindowsMpvLibrary
import com.nuvio.app.features.player.cStringMemory
import com.nuvio.app.features.player.createRenderParams
import com.nuvio.app.features.player.intMemory
import com.nuvio.app.features.player.resolveOpenGlProcAddress
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.awt.Window
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlinx.coroutines.channels.Channel
import org.jetbrains.skia.Image

private const val MPV_RENDER_PARAM_API_TYPE = 1
private const val MPV_RENDER_PARAM_OPENGL_INIT_PARAMS = 2
private const val MPV_RENDER_PARAM_OPENGL_FBO = 3
private const val MPV_RENDER_PARAM_FLIP_Y = 4
private const val MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME = 12
private const val MPV_RENDER_UPDATE_FRAME = 1L

internal class MpvGlVideoRenderer(
    private val stats: ComposeVideoStats,
) {
    val frameVersion = mutableIntStateOf(0)

    @Volatile
    private var released = false
    private var contexts: SharedGlContexts? = null
    private var renderContext: Pointer? = null
    private var target: SharedVideoTarget? = null
    private var updateCallback: MpvRenderUpdateCallback? = null
    private var procAddressCallback: MpvOpenGlGetProcAddressCallback? = null
    private var initParams: MpvOpenGlInitParams? = null
    private val renderFbo = MpvOpenGlFbo()
    private val renderParams = createRenderParams(
        MPV_RENDER_PARAM_OPENGL_FBO to renderFbo.pointer,
        MPV_RENDER_PARAM_FLIP_Y to intMemory(0),
        MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME to intMemory(0),
    )
    private val renderRequests = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var requestedAtNs = 0L
    private val forceRequested = AtomicBoolean(false)
    private val swapReportScheduled = AtomicBoolean(false)
    private var lastReportedVersion = 0

    @Volatile
    private var desiredWidth = 1920

    @Volatile
    private var desiredHeight = 1080

    @Volatile
    var onFrameRendered: () -> Unit = {}

    @Volatile
    var onBeforeRender: () -> Unit = {}

    fun attach(window: Window?, mpv: Pointer): String? {
        check(SwingUtilities.isEventDispatchThread())
        val skiko = SkikoGlContext.find(window) ?: return "skia layer not found"
        val shared = SharedGlContexts.create(skiko).getOrElse { return it.message }
        contexts = shared
        if (!shared.makeMpvCurrent()) return "mpv gl context could not be made current"
        procAddressCallback = object : MpvOpenGlGetProcAddressCallback {
            override fun invoke(ctx: Pointer?, name: String?): Pointer? = name?.let(::resolveOpenGlProcAddress)
        }
        initParams = MpvOpenGlInitParams().apply {
            get_proc_address = procAddressCallback
            get_proc_address_ctx = null
            write()
        }
        val createParams = createRenderParams(
            MPV_RENDER_PARAM_API_TYPE to cStringMemory("opengl"),
            MPV_RENDER_PARAM_OPENGL_INIT_PARAMS to initParams?.pointer,
        )
        val lib = WindowsMpvLibrary.INSTANCE
        val reference = PointerByReference()
        val startNs = System.nanoTime()
        val result = lib.mpv_render_context_create(reference, mpv, createParams[0].pointer)
        val gl = OpenGl32Library.INSTANCE
        val glInfo = "glVendor=${gl.readString(GL_VENDOR)} glRenderer=${gl.readString(GL_RENDERER)} " +
            "glVersion=${gl.readString(GL_VERSION)}"
        shared.makeSkikoCurrent()
        if (result < 0 || reference.value == null) return "mpv_render_context_create failed result=$result"
        renderContext = reference.value
        updateCallback = object : MpvRenderUpdateCallback {
            override fun invoke(ctx: Pointer?) {
                stats.recordUpdateCallback()
                requestRender(force = false)
            }
        }
        lib.mpv_render_context_set_update_callback(reference.value, updateCallback, null)
        composeVideoTrace(
            "compose video render context created ms=${(System.nanoTime() - startNs) / 1_000_000} $glInfo " +
                "sharedContext=true",
        )
        return null
    }

    fun setTargetSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == desiredWidth && height == desiredHeight) return
        desiredWidth = width
        desiredHeight = height
        requestRender(force = true)
    }

    fun currentImage(): Image? = target?.image

    fun requestRender(force: Boolean) {
        if (released) return
        if (force) forceRequested.set(true)
        requestedAtNs = System.nanoTime()
        renderRequests.trySend(Unit)
    }

    suspend fun awaitRenderRequest() {
        renderRequests.receive()
    }

    private val reportSwap = System.getProperty("kino.windows.compose-report-swap") == "true"

    fun onFrameDrawn(version: Int) {
        if (!reportSwap || version == lastReportedVersion || released) return
        lastReportedVersion = version
        if (!swapReportScheduled.compareAndSet(false, true)) return
        SwingUtilities.invokeLater {
            swapReportScheduled.set(false)
            val context = renderContext ?: return@invokeLater
            if (!released) MpvRenderSwapLibrary.INSTANCE.mpv_render_context_report_swap(context)
        }
    }

    fun renderNow() {
        val context = renderContext ?: return
        val shared = contexts ?: return
        if (released) return
        val queuedNs = requestedAtNs
        if (!shared.isSkikoContextCurrentHandle()) {
            composeVideoTrace("compose video skiko gl context changed; rendering disabled")
            return
        }
        val lib = WindowsMpvLibrary.INSTANCE
        val force = forceRequested.getAndSet(false)
        val flags = lib.mpv_render_context_update(context)
        if (!force && (flags and MPV_RENDER_UPDATE_FRAME) == 0L) return
        val currentTarget = ensureTarget(shared) ?: return
        val readsDone = shared.fenceInSkiko() ?: return
        if (!shared.makeMpvCurrent()) return
        shared.sync.waitAndDelete(readsDone)
        renderFbo.fbo = currentTarget.framebuffer
        renderFbo.w = currentTarget.width
        renderFbo.h = currentTarget.height
        renderFbo.internal_format = GL_RGBA8
        renderFbo.write()
        onBeforeRender()
        val renderStartNs = System.nanoTime()
        val result = lib.mpv_render_context_render(context, renderParams[0].pointer)
        val writesDone = shared.sync.fence()
        OpenGl32Library.INSTANCE.glFlush()
        shared.makeSkikoCurrent()
        shared.sync.waitAndDelete(writesDone)
        stats.recordRender(System.nanoTime() - renderStartNs, renderStartNs - queuedNs)
        if (renderStartNs - queuedNs > 30_000_000L) {
            composeVideoTrace("compose video late frame queueMs=${(renderStartNs - queuedNs) / 1_000_000}")
        }
        if (result < 0) {
            composeVideoTrace("compose video render failed result=$result")
            return
        }
        frameVersion.intValue += 1
        onFrameRendered()
    }

    private fun ensureTarget(shared: SharedGlContexts): SharedVideoTarget? {
        val width = desiredWidth
        val height = desiredHeight
        target?.let { existing ->
            if (existing.width == width && existing.height == height) return existing
        }
        target?.let { existing -> existing.release(shared) }
        target = null
        return SharedVideoTarget.create(shared, width, height)?.also { created ->
            target = created
            composeVideoTrace("compose video target allocated size=${width}x$height")
        }
    }

    fun release() {
        check(SwingUtilities.isEventDispatchThread())
        if (released) return
        released = true
        val context = renderContext
        renderContext = null
        val shared = contexts
        contexts = null
        val usable = shared != null && shared.isSkikoContextCurrentHandle() && shared.makeMpvCurrent()
        if (context != null) {
            WindowsMpvLibrary.INSTANCE.mpv_render_context_set_update_callback(context, null, null)
            if (usable) WindowsMpvLibrary.INSTANCE.mpv_render_context_free(context)
        }
        if (usable) {
            target?.release(shared)
            shared.destroyMpvContext()
        }
        target = null
        updateCallback = null
        procAddressCallback = null
        initParams = null
        composeVideoTrace("compose video renderer released glUsable=$usable")
    }
}
