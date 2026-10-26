package com.nuvio.app.features.player

import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamItem
import javax.swing.SwingUtilities

internal class WindowsPlayerCallbacks {
    @Volatile var onClose: (() -> Unit)? = null
    @Volatile var onAddonSubtitlesFetch: (() -> Unit)? = null
    @Volatile var onSourcesRequested: (() -> Unit)? = null
    @Volatile var onSourceStreamSelected: ((String) -> Unit)? = null
    @Volatile var onSourceFilterChanged: ((String?) -> Unit)? = null
    @Volatile var onSourceReload: (() -> Unit)? = null
    @Volatile var onEpisodesRequested: (() -> Unit)? = null
    @Volatile var onEpisodeSelected: ((String) -> Unit)? = null
    @Volatile var onEpisodeStreamSelected: ((String) -> Unit)? = null
    @Volatile var onEpisodeFilterChanged: ((String?) -> Unit)? = null
    @Volatile var onEpisodeReload: (() -> Unit)? = null
    @Volatile var onEpisodeBack: (() -> Unit)? = null
    @Volatile var onNextEpisodeRequested: (() -> Unit)? = null
    @Volatile var onSubmitIntroSubmitted: ((String, Double, Double) -> Unit)? = null
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
        SwingUtilities.invokeLater {
            windowState.panelRef?.clearExternalSubtitles()
        }
    }

    override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
        SwingUtilities.invokeLater {
            windowState.panelRef?.clearExternalSubtitlesAndSelect(trackIndex)
        }
    }

    override fun applySubtitleStyle(style: SubtitleStyleState) {
        engine.applySubtitleStyle(style)
    }

    override fun setMetadata(
        title: String,
        streamTitle: String,
        providerName: String,
        seasonNumber: Int?,
        episodeNumber: Int?,
        episodeTitle: String?,
        artwork: String?,
        logo: String?,
    ) {
        windowState.metaTitle = title
        windowState.metaStreamTitle = streamTitle
        windowState.metaProviderName = providerName
        windowState.metaSeason = seasonNumber
        windowState.metaEpisode = episodeNumber
        windowState.metaEpisodeTitle = episodeTitle
    }

    override fun setPlayerFlags(hasVideoId: Boolean, isSeries: Boolean) {
        windowState.hasVideoId = hasVideoId
        windowState.isSeries = isSeries
    }

    override fun setSubmitIntroEnabled(enabled: Boolean) {
        windowState.submitIntroEnabled = enabled
    }

    override fun showSkipButton(type: String, endTimeMs: Long) {
        windowState.skipIntroType = type
        windowState.skipIntroEndTimeMs = endTimeMs
        SwingUtilities.invokeLater {
            (windowState.panelRef as? WindowsPlayerPanel)?.updateSkipIntroButtonVisibility(true)
        }
    }

    override fun hideSkipButton() {
        windowState.skipIntroEndTimeMs = null
        SwingUtilities.invokeLater {
            (windowState.panelRef as? WindowsPlayerPanel)?.updateSkipIntroButtonVisibility(false)
        }
    }

    override fun showNextEpisode(
        season: Int,
        episode: Int,
        title: String,
        thumbnail: String?,
        hasAired: Boolean,
    ) {
        windowState.nextEpisodeSeason = season
        windowState.nextEpisodeEpisode = episode
        windowState.nextEpisodeTitle = title
        SwingUtilities.invokeLater {
            (windowState.panelRef as? WindowsPlayerPanel)?.updateNextEpisodeButtonVisibility(true)
        }
    }

    override fun hideNextEpisode() {
        windowState.nextEpisodeSeason = null
        windowState.nextEpisodeEpisode = null
        windowState.nextEpisodeTitle = null
        SwingUtilities.invokeLater {
            (windowState.panelRef as? WindowsPlayerPanel)?.updateNextEpisodeButtonVisibility(false)
        }
    }

    override fun setOnNextEpisodeRequestedCallback(callback: () -> Unit) {
        callbacks.onNextEpisodeRequested = callback
    }

    override fun setOnSubmitIntroSubmittedCallback(callback: (String, Double, Double) -> Unit) {
        callbacks.onSubmitIntroSubmitted = callback
    }

    override fun setOnCloseCallback(callback: () -> Unit) {
        callbacks.onClose = callback
    }

    override fun setOnAddonSubtitlesFetchCallback(callback: () -> Unit) {
        callbacks.onAddonSubtitlesFetch = callback
    }

    override fun pushAddonSubtitles(subtitles: List<AddonSubtitle>, isLoading: Boolean) {
        windowState.addonSubtitles = subtitles
        windowState.addonSubtitlesLoading = isLoading
    }

    override fun setOnSourcesRequestedCallback(callback: () -> Unit) {
        callbacks.onSourcesRequested = callback
    }

    override fun setOnSourceStreamSelectedCallback(callback: (String) -> Unit) {
        callbacks.onSourceStreamSelected = callback
    }

    override fun setOnSourceFilterChangedCallback(callback: (String?) -> Unit) {
        callbacks.onSourceFilterChanged = callback
    }

    override fun setOnSourceReloadCallback(callback: () -> Unit) {
        callbacks.onSourceReload = callback
    }

    override fun setOnEpisodesRequestedCallback(callback: () -> Unit) {
        callbacks.onEpisodesRequested = callback
    }

    override fun setOnEpisodeSelectedCallback(callback: (String) -> Unit) {
        callbacks.onEpisodeSelected = callback
    }

    override fun setOnEpisodeStreamSelectedCallback(callback: (String) -> Unit) {
        callbacks.onEpisodeStreamSelected = callback
    }

    override fun setOnEpisodeFilterChangedCallback(callback: (String?) -> Unit) {
        callbacks.onEpisodeFilterChanged = callback
    }

    override fun setOnEpisodeReloadCallback(callback: () -> Unit) {
        callbacks.onEpisodeReload = callback
    }

    override fun setOnEpisodeBackCallback(callback: () -> Unit) {
        callbacks.onEpisodeBack = callback
    }

    override fun pushSourceData(
        streams: List<StreamItem>,
        groups: List<AddonStreamGroup>,
        loading: Boolean,
        selectedFilter: String?,
        currentStreamUrl: String?,
    ) {
        windowState.sourceStreams = streams
        windowState.sourceGroups = groups
        windowState.sourcesLoading = loading
        windowState.sourceFilter = selectedFilter
        windowState.currentSourceStreamUrl = currentStreamUrl
    }

    override fun pushEpisodes(episodes: List<MetaVideo>) {
        windowState.episodes = episodes
    }

    override fun pushEpisodeStreamsData(
        streams: List<StreamItem>,
        groups: List<AddonStreamGroup>,
        loading: Boolean,
        selectedFilter: String?,
        currentStreamUrl: String?,
    ) {
        windowState.episodeStreams = streams
        windowState.episodeGroups = groups
        windowState.episodeLoading = loading
        windowState.episodeFilter = selectedFilter
        windowState.currentEpisodeStreamUrl = currentStreamUrl
    }

    override fun switchSource(url: String, audioUrl: String?, headersJson: String?) {
        val sanitizedHeaders = sanitizePlaybackHeaders(parseWindowsPlaybackHeaders(headersJson))
        windowState.playback.currentSourceUrl = url
        windowState.playback.currentSourceAudioUrl = audioUrl
        windowState.playback.currentHeaders = sanitizedHeaders
        windowState.panelRef?.loadFile(url, audioUrl, sanitizedHeaders)
    }
}
