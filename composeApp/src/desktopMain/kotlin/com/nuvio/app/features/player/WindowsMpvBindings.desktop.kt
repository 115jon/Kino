package com.nuvio.app.features.player

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference

// JNA Bindings to libmpv
internal interface WindowsMpvLibrary : Library {
    companion object {
        val INSTANCE: WindowsMpvLibrary by lazy {
            WindowsMpvNativeLoader.load()
        }
    }

    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_set_option_string(ctx: Pointer, name: String, value: String): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, value: String): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_free(data: Pointer)
    fun mpv_error_string(error: Int): Pointer?
    fun mpv_command(ctx: Pointer, args: Array<String?>): Int
    fun mpv_set_wakeup_callback(ctx: Pointer, callback: MpvWakeupCallback?, callbackCtx: Pointer?)
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer?
    fun mpv_request_log_messages(ctx: Pointer, minLevel: String): Int
    fun mpv_observe_property(ctx: Pointer, replyUserdata: Long, name: String, format: Int): Int
    fun mpv_render_context_create(res: PointerByReference, ctx: Pointer, params: Pointer): Int
    fun mpv_render_context_set_update_callback(ctx: Pointer, callback: MpvRenderUpdateCallback?, callbackCtx: Pointer?)
    fun mpv_render_context_update(ctx: Pointer): Long
    fun mpv_render_context_render(ctx: Pointer, params: Pointer): Int
    fun mpv_render_context_free(ctx: Pointer)
}

private const val MPV_RENDER_PARAM_INVALID = 0
internal const val MPV_RENDER_PARAM_API_TYPE = 1
internal const val MPV_RENDER_PARAM_OPENGL_INIT_PARAMS = 2
internal const val MPV_RENDER_PARAM_OPENGL_FBO = 3
internal const val MPV_RENDER_PARAM_FLIP_Y = 4
internal const val MPV_RENDER_UPDATE_FRAME = 1L
internal const val MPV_RENDER_API_TYPE_OPENGL = "opengl"

internal const val MPV_EVENT_NONE = 0
internal const val MPV_EVENT_SHUTDOWN = 1
internal const val MPV_EVENT_LOG_MESSAGE = 2
internal const val MPV_EVENT_PROPERTY_CHANGE = 22
internal const val MPV_EVENT_START_FILE = 6
internal const val MPV_EVENT_END_FILE = 7
internal const val MPV_EVENT_FILE_LOADED = 8
internal const val MPV_FORMAT_STRING = 1
internal const val MPV_FORMAT_FLAG = 3
internal const val MPV_FORMAT_INT64 = 4
internal const val MPV_FORMAT_DOUBLE = 5

internal interface MpvRenderUpdateCallback : Callback {
    fun invoke(ctx: Pointer?)
}

internal interface MpvWakeupCallback : Callback {
    fun invoke(ctx: Pointer?)
}

interface MpvOpenGlGetProcAddressCallback : Callback {
    fun invoke(ctx: Pointer?, name: String?): Pointer?
}

@Structure.FieldOrder("type", "data")
open class MpvRenderParam : Structure() {
    @JvmField
    var type: Int = MPV_RENDER_PARAM_INVALID

    @JvmField
    var data: Pointer? = null
}

@Structure.FieldOrder("get_proc_address", "get_proc_address_ctx")
open class MpvOpenGlInitParams : Structure() {
    @JvmField
    var get_proc_address: MpvOpenGlGetProcAddressCallback? = null

    @JvmField
    var get_proc_address_ctx: Pointer? = null
}

@Structure.FieldOrder("fbo", "w", "h", "internal_format")
open class MpvOpenGlFbo : Structure() {
    @JvmField
    var fbo: Int = 0

    @JvmField
    var w: Int = 0

    @JvmField
    var h: Int = 0

    @JvmField
    var internal_format: Int = 0
}

@Structure.FieldOrder("event_id", "error", "reply_userdata", "data")
open class MpvEvent(pointer: Pointer? = null) : Structure(pointer) {
    @JvmField
    var event_id: Int = 0

    @JvmField
    var error: Int = 0

    @JvmField
    var reply_userdata: Long = 0

    @JvmField
    var data: Pointer? = null
}

@Structure.FieldOrder("playlist_entry_id")
internal class MpvEventStartFile(pointer: Pointer? = null) : Structure(pointer) {
    @JvmField
    var playlist_entry_id: Long = 0L
}

@Structure.FieldOrder("reason", "error", "playlist_entry_id")
internal class MpvEventEndFile(pointer: Pointer? = null) : Structure(pointer) {
    @JvmField
    var reason: Int = 0

    @JvmField
    var error: Int = 0

    @JvmField
    var playlist_entry_id: Long = 0L
}

@Structure.FieldOrder("prefix", "level", "text", "log_level")
internal class MpvLogMessage(pointer: Pointer? = null) : Structure(pointer) {
    @JvmField
    var prefix: Pointer? = null

    @JvmField
    var level: Pointer? = null

    @JvmField
    var text: Pointer? = null

    @JvmField
    var log_level: Pointer? = null
}

@Structure.FieldOrder("name", "format", "data")
internal class MpvEventPropertyChange(pointer: Pointer? = null) : Structure(pointer) {
    @JvmField
    var name: Pointer? = null

    @JvmField
    var format: Int = 0

    @JvmField
    var data: Pointer? = null
}

internal fun readMpvErrorString(lib: WindowsMpvLibrary, error: Int): String? =
    lib.mpv_error_string(error)?.getString(0)?.takeIf { it.isNotBlank() && it != "success" }

internal fun readMpvStartFile(data: Pointer?): MpvEventStartFile? =
    data?.let { pointer -> runCatching { MpvEventStartFile(pointer).apply { read() } }.getOrNull() }

internal fun readMpvEndFile(data: Pointer?): MpvEventEndFile? =
    data?.let { pointer -> runCatching { MpvEventEndFile(pointer).apply { read() } }.getOrNull() }

internal fun createRenderParams(vararg values: Pair<Int, Pointer?>): Array<MpvRenderParam> {
    @Suppress("UNCHECKED_CAST")
    val params = MpvRenderParam().toArray(values.size + 1) as Array<MpvRenderParam>
    values.forEachIndexed { index, (type, data) ->
        params[index].type = type
        params[index].data = data
        params[index].write()
    }
    params[values.size].type = MPV_RENDER_PARAM_INVALID
    params[values.size].data = null
    params[values.size].write()
    return params
}

internal fun cStringMemory(value: String): Memory {
    val bytes = value.toByteArray(Charsets.UTF_8)
    return Memory((bytes.size + 1).toLong()).apply {
        write(0, bytes, 0, bytes.size)
        setByte(bytes.size.toLong(), 0)
    }
}

private fun intPairMemory(first: Int, second: Int): Memory =
    Memory(8L).apply {
        setInt(0L, first)
        setInt(4L, second)
    }

private fun longMemory(value: Long): Memory =
    Memory(8L).apply {
        setLong(0L, value)
    }

internal fun intMemory(value: Int): Memory =
    Memory(4L).apply {
        setInt(0L, value)
    }

private interface WglLibrary : Library {
    companion object {
        val INSTANCE: WglLibrary by lazy { Native.load("opengl32", WglLibrary::class.java) }
    }

    fun wglGetProcAddress(name: String): Pointer?
}

private interface Kernel32Library : Library {
    companion object {
        val INSTANCE: Kernel32Library by lazy { Native.load("kernel32", Kernel32Library::class.java) }
    }

    fun GetModuleHandleA(name: String): Pointer?
    fun LoadLibraryA(name: String): Pointer?
    fun GetProcAddress(module: Pointer, name: String): Pointer?
}

internal fun resolveOpenGlProcAddress(name: String): Pointer? {
    val wglAddress = WglLibrary.INSTANCE.wglGetProcAddress(name)
    val nativeAddress = wglAddress?.let(Pointer::nativeValue) ?: 0L
    if (nativeAddress != 0L && nativeAddress != 1L && nativeAddress != 2L && nativeAddress != 3L && nativeAddress != -1L) {
        return wglAddress
    }
    val kernel32 = Kernel32Library.INSTANCE
    val module = kernel32.GetModuleHandleA("opengl32.dll") ?: kernel32.LoadLibraryA("opengl32.dll") ?: return null
    return kernel32.GetProcAddress(module, name)
}
