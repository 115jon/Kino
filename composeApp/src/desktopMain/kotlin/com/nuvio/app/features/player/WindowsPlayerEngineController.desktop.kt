package com.nuvio.app.features.player

import javax.swing.SwingUtilities

internal class WindowsPlayerCallbacks {
    @Volatile var onClose: (() -> Unit)? = null
}

internal class WindowsPlayerEngineController(
    private val windowState: WindowsPlayerWindowState,
    private val callbacks: WindowsPlayerCallbacks,
) : PlayerEngineController {
    private val engine: MpvEngine get() = windowState.engine

    override fun play() {
        engine.setPaused(false)
    }

    override fun pause() {
        engine.setPaused(true)
    }

    override fun togglePlayPause() {
        engine.togglePause()
    }

    override fun seekTo(positionMs: Long) {
        engine.seekTo(positionMs)
    }

    override fun seekToKeyframe(positionMs: Long) {
        engine.seekToKeyframe(positionMs)
    }

    override fun seekBy(offsetMs: Long) {
        engine.seekBy(offsetMs)
    }

    override fun supportsVolumeControl(): Boolean = true

    override fun currentVolumeLevel(): PlayerAudioLevel = windowState.playback.volumeLevel

    override fun setVolumeLevel(level: Float): PlayerAudioLevel? = engine.setVolumeLevel(level)

    override fun supportsFullscreenToggle(): Boolean = true

    override fun toggleFullscreen() {
        SwingUtilities.invokeLater {
            windowState.panelRef?.toggleFullScreen()
        }
    }

    override fun requestInteractionFocus() {
        SwingUtilities.invokeLater {
            windowState.panelRef?.requestInteractionFocus()
        }
    }

    override fun setStreamProfileInfo(
        profileSummary: String?,
        isHdrLike: Boolean,
        hasDolbyVision: Boolean,
        hasHdrFallback: Boolean,
    ) {
        engine.setStreamProfile(profileSummary, isHdrLike, hasDolbyVision, hasHdrFallback)
    }

    override fun retry() {
        SwingUtilities.invokeLater {
            windowState.panelRef?.retryPlayback()
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        engine.setSpeed(speed)
    }

    override fun getAudioTracks(): List<AudioTrack> = windowState.playback.audioTracks

    override fun getSubtitleTracks(): List<SubtitleTrack> = windowState.playback.subtitleTracks

    override fun applyAudioLanguagePreferences(languages: List<String>) {
        engine.applyAudioLanguagePreferences(languages)
    }

    override fun selectAudioTrack(index: Int) {
        engine.selectAudioTrack(index)
    }

    override fun selectSubtitleTrack(index: Int) {
        engine.selectSubtitleTrack(index)
    }

    override fun setSubtitleUri(url: String) {
        engine.addSubtitle(url, reapplyStyleLater = false)
    }

    override fun clearExternalSubtitle() {
        windowState.panelRef?.clearExternalSubtitles()
    }

    override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
        windowState.panelRef?.clearExternalSubtitlesAndSelect(trackIndex)
    }

    override fun applySubtitleStyle(style: SubtitleStyleState) {
        engine.applySubtitleStyle(style)
    }

    override fun setOnCloseCallback(callback: () -> Unit) {
        callbacks.onClose = callback
    }

    override fun switchSource(url: String, audioUrl: String?, headersJson: String?) {
        val sanitizedHeaders = sanitizePlaybackHeaders(parseWindowsPlaybackHeaders(headersJson))
        windowState.playback.currentSourceUrl = url
        windowState.playback.currentSourceAudioUrl = audioUrl
        windowState.playback.currentHeaders = sanitizedHeaders
        windowState.panelRef?.loadFile(url, audioUrl, sanitizedHeaders)
    }
}
