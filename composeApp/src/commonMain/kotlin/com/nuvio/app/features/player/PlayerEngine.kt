package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

interface PlayerEngineController {
    fun play()
    fun pause()
    fun togglePlayPause() {}
    fun seekTo(positionMs: Long)

    fun seekToKeyframe(positionMs: Long) {
        seekTo(positionMs)
    }
    fun seekBy(offsetMs: Long)
    fun supportsVolumeControl(): Boolean = false
    fun currentVolumeLevel(): PlayerAudioLevel? = null
    fun setVolumeLevel(level: Float): PlayerAudioLevel? = null
    fun supportsFullscreenToggle(): Boolean = false
    fun toggleFullscreen() {}
    fun requestInteractionFocus() {}
    fun setStreamProfileInfo(
        profileSummary: String?,
        isHdrLike: Boolean,
        hasDolbyVision: Boolean,
        hasHdrFallback: Boolean,
    ) {}
    fun retry()
    fun setPlaybackSpeed(speed: Float)
    fun setMuted(muted: Boolean) {}
    fun getAudioTracks(): List<AudioTrack>
    fun getSubtitleTracks(): List<SubtitleTrack>
    fun selectAudioTrack(index: Int)
    fun selectSubtitleTrack(index: Int)
    fun setSubtitleUri(url: String)
    fun clearExternalSubtitle()
    fun clearExternalSubtitleAndSelect(trackIndex: Int)
    fun applySubtitleStyle(style: SubtitleStyleState) {}
    fun setSubtitleDelayMs(delayMs: Int) {}
    fun configureIosVideoOutput(settings: PlayerSettingsUiState) {}
    fun updateNowPlayingMetadata(info: PlayerNowPlayingInfo) {}
    fun clearNowPlayingInfo() {}
    fun setMetadata(
        title: String,
        streamTitle: String,
        providerName: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        episodeTitle: String? = null,
        artwork: String? = null,
        logo: String? = null,
    ) {}
    fun setPlayerFlags(hasVideoId: Boolean, isSeries: Boolean) {}
    fun setSubmitIntroEnabled(enabled: Boolean) {}
    fun showSkipButton(type: String, endTimeMs: Long) {}
    fun hideSkipButton() {}
    fun showNextEpisode(
        season: Int,
        episode: Int,
        title: String,
        thumbnail: String? = null,
        hasAired: Boolean = true,
    ) {}
    fun hideNextEpisode() {}
    fun setOnNextEpisodeRequestedCallback(callback: () -> Unit) {}
    fun setOnSubmitIntroSubmittedCallback(callback: (segmentType: String, startSec: Double, endSec: Double) -> Unit) {}
    fun setOnCloseCallback(callback: () -> Unit) {}
    fun setOnAddonSubtitlesFetchCallback(callback: () -> Unit) {}
    fun pushAddonSubtitles(subtitles: List<AddonSubtitle>, isLoading: Boolean) {}
    fun setOnSourcesRequestedCallback(callback: () -> Unit) {}
    fun setOnSourceStreamSelectedCallback(callback: (String) -> Unit) {}
    fun setOnSourceFilterChangedCallback(callback: (String?) -> Unit) {}
    fun setOnSourceReloadCallback(callback: () -> Unit) {}
    fun setOnEpisodesRequestedCallback(callback: () -> Unit) {}
    fun setOnEpisodeSelectedCallback(callback: (String) -> Unit) {}
    fun setOnEpisodeStreamSelectedCallback(callback: (String) -> Unit) {}
    fun setOnEpisodeFilterChangedCallback(callback: (String?) -> Unit) {}
    fun setOnEpisodeReloadCallback(callback: () -> Unit) {}
    fun setOnEpisodeBackCallback(callback: () -> Unit) {}
    fun pushSourceData(
        streams: List<com.nuvio.app.features.streams.StreamItem>,
        groups: List<com.nuvio.app.features.streams.AddonStreamGroup>,
        loading: Boolean,
        selectedFilter: String?,
        currentStreamUrl: String?,
    ) {}
    fun pushEpisodes(episodes: List<com.nuvio.app.features.details.MetaVideo>) {}
    fun pushEpisodeStreamsData(
        streams: List<com.nuvio.app.features.streams.StreamItem>,
        groups: List<com.nuvio.app.features.streams.AddonStreamGroup>,
        loading: Boolean,
        selectedFilter: String?,
        currentStreamUrl: String?,
    ) {}
    fun showEpisodeStreamsView(season: Int?, episode: Int?, title: String?) {}
    fun dismissNativePanels() {}
    fun switchSource(url: String, audioUrl: String?, headersJson: String?) {}
}

internal const val MaxPlaybackHeaderNameLength = 256
internal const val MaxPlaybackHeaderValueLength = 8_192
internal const val MaxPlaybackHeaderCount = 32
internal const val MaxPlaybackHeaderAggregateLength = 16_384

private fun hasPlaybackHeaderControlCharacter(value: String): Boolean = value.any { character ->
    character.code < 0x20 || character.code in 0x7F..0x9F
}

private fun isPlaybackHeaderName(value: String): Boolean =
    value.length <= MaxPlaybackHeaderNameLength &&
        value.isNotEmpty() &&
        value.all { character ->
            character in 'a'..'z' ||
                character in 'A'..'Z' ||
                character in '0'..'9' ||
                character in "!#$%&'*+-.^_`|~"
        }

private fun sanitizePlaybackHeaderMap(
    headers: Map<String, String>?,
    removeRange: Boolean,
): Map<String, String> {
    val rawHeaders = headers ?: return emptyMap()
    if (rawHeaders.isEmpty()) return emptyMap()

    val sanitized = LinkedHashMap<String, String>(minOf(rawHeaders.size, MaxPlaybackHeaderCount))
    var aggregateLength = 0
    rawHeaders.forEach { (rawKey, rawValue) ->
        if (hasPlaybackHeaderControlCharacter(rawKey) || hasPlaybackHeaderControlCharacter(rawValue)) {
            return@forEach
        }
        val key = rawKey.trim()
        val value = rawValue.trim()
        if (key.isEmpty() || value.isEmpty()) return@forEach
        if (!isPlaybackHeaderName(key) || value.length > MaxPlaybackHeaderValueLength) return@forEach
        if (removeRange && key.equals("Range", ignoreCase = true)) return@forEach
        if (sanitized.size >= MaxPlaybackHeaderCount) return@forEach
        val entryLength = key.length + value.length + 2
        if (aggregateLength + entryLength > MaxPlaybackHeaderAggregateLength) return@forEach
        sanitized[key] = value
        aggregateLength += entryLength
    }
    return sanitized
}

internal fun sanitizePlaybackHeaders(headers: Map<String, String>?): Map<String, String> =
    sanitizePlaybackHeaderMap(headers, removeRange = true)

internal fun sanitizePlaybackResponseHeaders(headers: Map<String, String>?): Map<String, String> {
    return sanitizePlaybackHeaderMap(headers, removeRange = false)
}

@Composable
expect fun PlatformPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String? = null,
    sourceHeaders: Map<String, String> = emptyMap(),
    sourceResponseHeaders: Map<String, String> = emptyMap(),
    externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle> = emptyList(),
    streamType: String? = null,
    useYoutubeChunkedPlayback: Boolean = false,
    modifier: Modifier = Modifier,
    playWhenReady: Boolean = true,
    resizeMode: PlayerResizeMode = PlayerResizeMode.Fit,
    useNativeController: Boolean = false,
    overlayContent: @Composable () -> Unit = {},
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
    onSurfaceInteraction: (Boolean) -> Unit = {},
    onSurfaceExit: () -> Unit = {},
)

internal expect fun platformPlayerSurfaceOwnsOverlay(): Boolean
