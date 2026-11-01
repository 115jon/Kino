package com.nuvio.app.features.player

import java.awt.Window
import kotlinx.coroutines.flow.MutableStateFlow

internal class WindowsPlayerWindowState(
    val engine: MpvEngine,
) {
    val playback: MpvPlaybackState get() = engine.playback

    var isClosed: Boolean
        get() = engine.isClosed
        set(value) {
            engine.isClosed = value
        }

    val fullscreen = MutableStateFlow(false)

    @Volatile
    var panelRef: WindowsPlaybackPanel? = null
}

internal fun traceWindowsPlayerFocus(surface: String, focused: Boolean) {
    desktopPlayerTrace("window focus surface=$surface focused=$focused")
}

internal fun traceWindowsPlayerWindowState(surface: String, oldState: Int, newState: Int) {
    desktopPlayerTrace("window state surface=$surface old=$oldState new=$newState")
}

internal interface WindowsPlaybackPanel {
    fun loadFile(
        url: String,
        audioUrl: String?,
        headers: Map<String, String>,
        onCommitted: ((Long) -> Unit)? = null,
        forceResolve: Boolean = false,
        startPositionMs: Long? = null,
    )
    fun loadSubtitleUrl(url: String)
    fun applyResizeMode(resizeMode: PlayerResizeMode)
    fun toggleFullScreen() {}
    fun exitFullScreen() {}
    fun acceptsShortcutsFrom(window: Window?): Boolean = false
    fun requestInteractionFocus() {}
    fun retryPlayback()
    fun clearExternalSubtitles()
    fun clearExternalSubtitlesAndSelect(trackIndex: Int)
    fun dispose()
}
