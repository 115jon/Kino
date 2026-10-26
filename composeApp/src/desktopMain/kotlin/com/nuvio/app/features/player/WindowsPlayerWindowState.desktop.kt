package com.nuvio.app.features.player

internal class WindowsPlayerWindowState(
    val engine: MpvEngine,
) {
    val playback: MpvPlaybackState get() = engine.playback

    var isClosed: Boolean
        get() = engine.isClosed
        set(value) {
            engine.isClosed = value
        }

    @Volatile
    var panelRef: WindowsPlaybackPanel? = null
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
    fun requestInteractionFocus() {}
    fun retryPlayback()
    fun clearExternalSubtitles()
    fun clearExternalSubtitlesAndSelect(trackIndex: Int)
    fun dispose()
}
