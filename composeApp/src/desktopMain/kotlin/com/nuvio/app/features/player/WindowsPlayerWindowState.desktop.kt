package com.nuvio.app.features.player

import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamItem

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

    var metaTitle = ""
    var metaStreamTitle = ""
    var metaProviderName = ""
    var metaSeason: Int? = null
    var metaEpisode: Int? = null
    var metaEpisodeTitle: String? = null
    var hasVideoId = false
    var isSeries = false
    var submitIntroEnabled = false

    var addonSubtitles = emptyList<AddonSubtitle>()
    var addonSubtitlesLoading = false

    var skipIntroType: String? = null
    var skipIntroEndTimeMs: Long? = null
    var nextEpisodeSeason: Int? = null
    var nextEpisodeEpisode: Int? = null
    var nextEpisodeTitle: String? = null

    var sourceStreams = emptyList<StreamItem>()
    var sourceGroups = emptyList<AddonStreamGroup>()
    var sourcesLoading = false
    var sourceFilter: String? = null
    var currentSourceStreamUrl: String? = null

    var episodes = emptyList<MetaVideo>()
    var episodeStreams = emptyList<StreamItem>()
    var episodeGroups = emptyList<AddonStreamGroup>()
    var episodeLoading = false
    var episodeFilter: String? = null
    var currentEpisodeStreamUrl: String? = null
}

internal interface WindowsPlaybackPanel {
    fun loadFile(
        url: String,
        audioUrl: String?,
        headers: Map<String, String>,
        onCommitted: ((Long) -> Unit)? = null,
        forceResolve: Boolean = false,
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
