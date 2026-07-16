package com.nuvio.app.features.player

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference
import co.touchlab.kermit.Logger
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

private val windowsMediaSessionLog = Logger.withTag("WindowsMediaSession")

private const val MEDIA_PLAYBACK_STATUS_STOPPED = 2
private const val MEDIA_PLAYBACK_STATUS_PLAYING = 3
private const val MEDIA_PLAYBACK_STATUS_PAUSED = 4
private const val MEDIA_PLAYBACK_STATUS_CHANGING = 1

private const val SYSTEM_BUTTON_PLAY = 0
private const val SYSTEM_BUTTON_PAUSE = 1
private const val SYSTEM_BUTTON_STOP = 2
private const val SYSTEM_BUTTON_NEXT = 6
private const val SYSTEM_BUTTON_PREVIOUS = 7

internal interface WindowsMediaSessionLibrary : Library {
    fun kino_windows_media_session_create(
        windowHandle: Pointer,
        callback: WindowsMediaSessionCallback,
        callbackContext: Pointer?,
        session: PointerByReference,
    ): Int

    fun kino_windows_media_session_update(session: Pointer, playbackStatus: Int): Int

    fun kino_windows_media_session_update_metadata(
        session: Pointer,
        title: WString,
        subtitle: WString?,
        artworkUrl: WString?,
    ): Int

    fun kino_windows_media_session_clear_metadata(session: Pointer): Int

    fun kino_windows_media_session_dispose(session: Pointer): Int
}

internal fun interface WindowsMediaSessionCallback : Callback {
    fun invoke(callbackContext: Pointer?, button: Int)
}

internal class WindowsMediaSession(
    private val onCommand: (MediaKeyCommand) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private val isClosed = AtomicBoolean(false)
    private val callback = WindowsMediaSessionCallback { _, button ->
        val command = when (button) {
            SYSTEM_BUTTON_PLAY,
            SYSTEM_BUTTON_PAUSE,
            -> MediaKeyCommand.PlayPause
            SYSTEM_BUTTON_STOP -> MediaKeyCommand.Stop
            SYSTEM_BUTTON_PREVIOUS -> MediaKeyCommand.Previous
            SYSTEM_BUTTON_NEXT -> MediaKeyCommand.Next
            else -> null
        }
        if (command != null && !isClosed.get()) {
            SwingUtilities.invokeLater {
                if (!isClosed.get()) onCommand(command)
            }
        }
    }
    private var nativeLibrary: WindowsMediaSessionLibrary? = null
    private var nativeSession: Pointer? = null
    private var focused = false
    private var windowHandle: Long? = null
    private var playbackState = MediaSessionPlaybackState.Changing
    private var metadata: MediaSessionMetadata? = null
    private var metadataDirty = true
    private var lifetimeState = MediaSessionLifetimeState()

    fun updatePlayback(state: MediaSessionPlaybackState) {
        synchronized(lock) {
            if (isClosed.get()) return
            playbackState = state
            lifetimeState = lifetimeState.mediaStarted()
            syncNativeState()
        }
    }

    fun updateFocus(isFocused: Boolean, handle: Long?) {
        synchronized(lock) {
            if (isClosed.get()) return
            focused = isFocused
            lifetimeState = lifetimeState.focusChanged(isFocused)
            if (handle != null && handle != windowHandle) {
                disposeNative()
                windowHandle = handle
            } else if (handle != null) {
                windowHandle = handle
            }
            syncNativeState()
        }
    }

    override fun close() {
        if (!isClosed.compareAndSet(false, true)) return
        synchronized(lock) {
            focused = false
            lifetimeState = lifetimeState.surfaceTornDown().focusChanged(false)
            disposeNative()
            nativeLibrary = null
        }
    }

    private fun ensureNative() {
        if (nativeSession != null) return
        val handle = windowHandle ?: return
        if (handle == 0L) return
        try {
            val library = nativeLibrary ?: WindowsMpvNativeLoader.loadMediaSession().also { nativeLibrary = it }
            val session = PointerByReference()
            val result = library.kino_windows_media_session_create(
                Pointer.createConstant(handle),
                callback,
                null,
                session,
            )
            logNativeResult("create", result)
            if (result == 0) {
                nativeSession = session.value
                if (nativeSession != null) {
                    metadataDirty = true
                    updateNativeState()
                }
            }
        } catch (error: Throwable) {
            windowsMediaSessionLog.e(error) { "Native session creation failed" }
            nativeSession = null
        }
    }

    private fun updateNativeState() {
        val library = nativeLibrary ?: return
        val session = nativeSession ?: return
        logNativeResult(
            "updatePlaybackState",
            library.kino_windows_media_session_update(session, playbackState.nativeValue()),
        )
    }

    private fun syncNativeState() {
        if (!focused && !lifetimeState.shouldKeepNativeSession()) return
        ensureNative()
        if (metadataDirty) {
            updateNativeMetadata()
            metadataDirty = false
        }
        updateNativeState()
    }

    fun updateMetadata(value: MediaSessionMetadata?) {
        synchronized(lock) {
            if (isClosed.get()) return
            metadata = value
            metadataDirty = true
            if (value != null) {
                lifetimeState = lifetimeState.mediaStarted()
            }
            syncNativeState()
        }
    }

    fun sourceChanged() {
        synchronized(lock) {
            if (isClosed.get()) return
            metadata = null
            metadataDirty = true
            playbackState = MediaSessionPlaybackState.Changing
            if (lifetimeState.shouldDisposeAfterSourceTeardown()) {
                disposeNative()
            } else {
                syncNativeState()
            }
        }
    }

    fun clearMetadata() {
        updateMetadata(null)
    }

    private fun updateNativeMetadata() {
        val library = nativeLibrary ?: return
        val session = nativeSession ?: return
        val value = metadata
        if (value == null) {
            logNativeResult("clearMetadata", library.kino_windows_media_session_clear_metadata(session))
        } else {
            logNativeResult(
                "updateMetadata",
                library.kino_windows_media_session_update_metadata(
                    session,
                    WString(value.title),
                    value.subtitle?.let(::WString),
                    value.artworkUrl?.let(::WString),
                ),
            )
        }
    }

    private fun disposeNative() {
        val session = nativeSession ?: return
        nativeLibrary?.kino_windows_media_session_dispose(session)?.let { result ->
            logNativeResult("dispose", result)
        }
        nativeSession = null
    }

    private fun logNativeResult(operation: String, result: Int) {
        if (result == 0) return
        windowsMediaSessionLog.e {
            "Native $operation failed hresult=0x${result.toUInt().toString(16).padStart(8, '0')}"
        }
    }

    private fun MediaSessionPlaybackState.nativeValue(): Int = when (this) {
        MediaSessionPlaybackState.Changing -> MEDIA_PLAYBACK_STATUS_CHANGING
        MediaSessionPlaybackState.Playing -> MEDIA_PLAYBACK_STATUS_PLAYING
        MediaSessionPlaybackState.Paused -> MEDIA_PLAYBACK_STATUS_PAUSED
        MediaSessionPlaybackState.Stopped -> MEDIA_PLAYBACK_STATUS_STOPPED
    }
}
