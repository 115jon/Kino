package com.nuvio.app.features.player

import com.sun.jna.Pointer

internal class JnaMpvClient(
    private val lib: WindowsMpvLibrary,
    private val ptr: Pointer,
) : MpvClient {
    private var wakeupCallback: MpvWakeupCallback? = null

    override val nativeHandle: Pointer = ptr

    val library: WindowsMpvLibrary get() = lib

    override fun setOption(name: String, value: String): Int = lib.mpv_set_option_string(ptr, name, value)

    override fun initialize(): Int = lib.mpv_initialize(ptr)

    override fun requestLogMessages(minLevel: String): Int = lib.mpv_request_log_messages(ptr, minLevel)

    override fun observeProperty(name: String, format: Int): Int = lib.mpv_observe_property(ptr, 0, name, format)

    override fun setProperty(name: String, value: String): Int = lib.mpv_set_property_string(ptr, name, value)

    override fun getProperty(name: String): String? {
        val property = runCatching { lib.mpv_get_property_string(ptr, name) }.getOrNull() ?: return null
        return try {
            property.getString(0)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { lib.mpv_free(property) }
        }
    }

    override fun command(vararg args: String): Int = lib.mpv_command(ptr, arrayOf<String?>(*args, null))

    override fun errorString(error: Int): String? = readMpvErrorString(lib, error)

    override fun waitEvent(timeoutSeconds: Double): MpvClientEvent? {
        val eventPtr = lib.mpv_wait_event(ptr, timeoutSeconds) ?: return null
        val event = MpvEvent(eventPtr)
        event.read()
        return when (event.event_id) {
            MPV_EVENT_NONE -> null
            MPV_EVENT_SHUTDOWN -> MpvClientEvent.Shutdown(event.event_id, event.error)
            MPV_EVENT_LOG_MESSAGE -> decodeLogMessage(event)
            MPV_EVENT_PROPERTY_CHANGE -> decodePropertyChange(event)
            MPV_EVENT_START_FILE -> MpvClientEvent.StartFile(
                id = event.event_id,
                error = event.error,
                playlistEntryId = readMpvStartFile(event.data)?.playlist_entry_id,
            )
            MPV_EVENT_END_FILE -> MpvClientEvent.EndFile(
                id = event.event_id,
                error = event.error,
                data = readMpvEndFile(event.data)?.let { endFile ->
                    MpvEndFileData(
                        reason = endFile.reason,
                        error = endFile.error,
                        playlistEntryId = endFile.playlist_entry_id,
                    )
                },
            )
            else -> MpvClientEvent.Other(event.event_id, event.error)
        }
    }

    override fun setWakeupListener(listener: (() -> Unit)?) {
        val callback = listener?.let {
            object : MpvWakeupCallback {
                override fun invoke(ctx: Pointer?) {
                    it()
                }
            }
        }
        lib.mpv_set_wakeup_callback(ptr, callback, null)
        wakeupCallback = callback
    }

    override fun terminateDestroy() {
        lib.mpv_terminate_destroy(ptr)
    }

    private fun decodeLogMessage(event: MpvEvent): MpvClientEvent.LogMessage =
        try {
            val message = event.data?.let { MpvLogMessage(it).apply { read() } }
            MpvClientEvent.LogMessage(
                id = event.event_id,
                error = event.error,
                prefix = mpvLogText(message?.prefix),
                level = mpvLogText(message?.level),
                text = mpvLogText(message?.text),
            )
        } catch (error: Throwable) {
            MpvClientEvent.LogMessage(
                id = event.event_id,
                error = event.error,
                prefix = "",
                level = "",
                text = "",
                decodeError = error,
            )
        }

    private fun decodePropertyChange(event: MpvEvent): MpvClientEvent.PropertyChange {
        val property = event.data?.let { pointer ->
            runCatching { MpvEventPropertyChange(pointer).apply { read() } }.getOrNull()
        }
        val name = runCatching { property?.name?.getString(0) }.getOrNull().orEmpty()
        val value = property?.let { change ->
            runCatching { readMpvPropertyValue(change.format, change.data) }.getOrNull()
        }
        return MpvClientEvent.PropertyChange(
            id = event.event_id,
            error = event.error,
            name = name,
            value = value,
        )
    }
}

internal fun readMpvPropertyValue(format: Int, data: Pointer?): Any? {
    val pointer = data ?: return null
    return when (format) {
        MPV_FORMAT_FLAG -> pointer.getInt(0) != 0
        MPV_FORMAT_INT64 -> pointer.getLong(0)
        MPV_FORMAT_DOUBLE -> pointer.getDouble(0)
        MPV_FORMAT_STRING -> pointer.getPointer(0)?.getString(0)
        else -> null
    }
}

internal fun createJnaMpvClient(): MpvClient {
    val lib = WindowsMpvLibrary.INSTANCE
    val ptr = lib.mpv_create() ?: throw RuntimeException("Failed to create libmpv instance")
    return JnaMpvClient(lib, ptr)
}

private fun mpvLogText(pointer: Pointer?): String = pointer?.getString(0).orEmpty()
