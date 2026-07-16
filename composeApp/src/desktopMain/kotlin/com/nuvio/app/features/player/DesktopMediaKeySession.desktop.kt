package com.nuvio.app.features.player

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class DesktopMediaKeySession : AutoCloseable {
    private val controller = AtomicReference<PlayerEngineController?>()
    private val isClosed = AtomicBoolean(false)
    private val windowsSession = if (isWindows()) {
        WindowsMediaSession(::dispatch)
    } else {
        null
    }

    fun bind(controller: PlayerEngineController): PlayerEngineController {
        val boundController = object : PlayerEngineController by controller {
            override fun updateNowPlayingMetadata(info: PlayerNowPlayingInfo) {
                controller.updateNowPlayingMetadata(info)
                windowsSession?.updateMetadata(info.toMediaSessionMetadata())
            }

            override fun clearNowPlayingInfo() {
                controller.clearNowPlayingInfo()
                windowsSession?.clearMetadata()
            }
        }
        this.controller.set(boundController)
        return boundController
    }

    fun updatePlayback(snapshot: PlayerPlaybackSnapshot) {
        if (isClosed.get()) return
        windowsSession?.updatePlayback(snapshot.toMediaSessionPlaybackState())
    }

    fun updateFocus(isFocused: Boolean, windowHandle: Long?) {
        if (isClosed.get()) return
        windowsSession?.updateFocus(isFocused, windowHandle)
    }

    fun sourceChanged() {
        if (isClosed.get()) return
        windowsSession?.sourceChanged()
    }

    override fun close() {
        if (!isClosed.compareAndSet(false, true)) return
        controller.set(null)
        windowsSession?.close()
    }

    private fun dispatch(command: MediaKeyCommand) {
        if (isClosed.get()) return
        controller.get()?.let { activeController ->
            dispatchMediaKeyCommand(command, activeController)
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)
}
