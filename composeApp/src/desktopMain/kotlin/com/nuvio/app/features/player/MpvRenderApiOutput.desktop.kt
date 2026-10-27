package com.nuvio.app.features.player

import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

internal class RenderApiOutput(
    private val extraOptions: List<MpvOption> = emptyList(),
    override val logLevel: String = "warn",
    private val onRenderUpdate: () -> Unit,
) : MpvVideoOutput {
    private var lib: WindowsMpvLibrary? = null
    private var renderUpdateCallback: MpvRenderUpdateCallback? = null
    private var glProcAddressCallback: MpvOpenGlGetProcAddressCallback? = null
    private var glInitParams: MpvOpenGlInitParams? = null
    private val renderFbo = MpvOpenGlFbo()
    private val renderFlipY = intMemory(0)
    private val renderBlock = intMemory(1)
    private val renderParams = createRenderParams(
        MPV_RENDER_PARAM_OPENGL_FBO to renderFbo.pointer,
        MPV_RENDER_PARAM_FLIP_Y to renderFlipY,
        MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME to renderBlock,
    )

    @Volatile
    var renderContext: Pointer? = null
        private set

    override fun options(): List<MpvOption> = listOf(MpvOption("vo", "libmpv")) + extraOptions

    override fun attach(client: MpvClient) {
        val jnaClient = client as? JnaMpvClient
            ?: throw RuntimeException("Render API output requires a native libmpv client")
        val library = jnaClient.library
        lib = library
        glProcAddressCallback = object : MpvOpenGlGetProcAddressCallback {
            override fun invoke(ctx: Pointer?, name: String?): Pointer? =
                name?.let(::resolveOpenGlProcAddress)
        }
        glInitParams = MpvOpenGlInitParams().apply {
            get_proc_address = glProcAddressCallback
            get_proc_address_ctx = null
            write()
        }
        val createParams = createRenderParams(
            MPV_RENDER_PARAM_API_TYPE to cStringMemory(MPV_RENDER_API_TYPE_OPENGL),
            MPV_RENDER_PARAM_OPENGL_INIT_PARAMS to glInitParams?.pointer,
        )
        val renderContextRef = PointerByReference()
        val renderRet = library.mpv_render_context_create(renderContextRef, jnaClient.nativeHandle, createParams[0].pointer)
        if (renderRet < 0) {
            throw RuntimeException("Failed to initialize libmpv render context: error code $renderRet")
        }
        val createdRenderContext = renderContextRef.value
            ?: throw RuntimeException("Failed to allocate libmpv render context")
        renderContext = createdRenderContext
        renderUpdateCallback = object : MpvRenderUpdateCallback {
            override fun invoke(ctx: Pointer?) {
                onRenderUpdate()
            }
        }
        library.mpv_render_context_set_update_callback(createdRenderContext, renderUpdateCallback, null)
    }

    fun clearUpdateCallback() {
        val library = lib
        renderContext?.let { renderPtr ->
            library?.mpv_render_context_set_update_callback(renderPtr, null, null)
        }
        renderUpdateCallback = null
        glProcAddressCallback = null
    }

    override fun detach(client: MpvClient) {
        val renderPtr = renderContext
        renderContext = null
        glInitParams = null
        if (renderPtr != null) {
            lib?.mpv_render_context_free(renderPtr)
        }
    }

    fun hasNewFrame(): Boolean {
        val renderPtr = renderContext ?: return false
        val library = lib ?: return false
        return (library.mpv_render_context_update(renderPtr) and MPV_RENDER_UPDATE_FRAME) != 0L
    }

    fun reportSwap() {
        val renderPtr = renderContext ?: return
        lib?.mpv_render_context_report_swap(renderPtr)
    }

    fun render(framebuffer: Int, width: Int, height: Int, internalFormat: Int = 0): Int? {
        val renderPtr = renderContext ?: return null
        val library = lib ?: return null
        renderFbo.fbo = framebuffer
        renderFbo.w = width
        renderFbo.h = height
        renderFbo.internal_format = internalFormat
        renderFbo.write()
        return library.mpv_render_context_render(renderPtr, renderParams[0].pointer)
    }
}
