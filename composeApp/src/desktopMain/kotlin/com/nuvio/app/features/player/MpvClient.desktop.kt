package com.nuvio.app.features.player

import com.sun.jna.Pointer

internal interface MpvClient {
    val nativeHandle: Pointer?

    fun setOption(name: String, value: String): Int

    fun initialize(): Int

    fun requestLogMessages(minLevel: String): Int

    fun observeProperty(name: String, format: Int): Int

    fun setProperty(name: String, value: String): Int

    fun getProperty(name: String): String?

    fun command(vararg args: String): Int

    fun errorString(error: Int): String?

    fun waitEvent(timeoutSeconds: Double): MpvClientEvent?

    fun setWakeupListener(listener: (() -> Unit)?)

    fun terminateDestroy()
}

internal sealed interface MpvClientEvent {
    val id: Int
    val error: Int

    data class Shutdown(override val id: Int, override val error: Int) : MpvClientEvent

    data class LogMessage(
        override val id: Int,
        override val error: Int,
        val prefix: String,
        val level: String,
        val text: String,
        val decodeError: Throwable? = null,
    ) : MpvClientEvent

    data class PropertyChange(
        override val id: Int,
        override val error: Int,
        val name: String,
        val value: Any?,
    ) : MpvClientEvent

    data class StartFile(
        override val id: Int,
        override val error: Int,
        val playlistEntryId: Long?,
    ) : MpvClientEvent

    data class EndFile(
        override val id: Int,
        override val error: Int,
        val data: MpvEndFileData?,
    ) : MpvClientEvent

    data class Other(override val id: Int, override val error: Int) : MpvClientEvent
}

internal data class MpvEndFileData(
    val reason: Int,
    val error: Int,
    val playlistEntryId: Long,
)

internal fun MpvClient.readNonBlankProperty(name: String): String? =
    runCatching { getProperty(name) }.getOrNull()?.takeIf { it.isNotBlank() }
