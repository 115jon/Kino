package com.nuvio.app.features.player

internal enum class MediaKeyCommand {
    PlayPause,
    Stop,
    Previous,
    Next,
}

internal enum class MediaSessionPlaybackState {
    Changing,
    Playing,
    Paused,
    Stopped,
}

internal data class MediaSessionLifetimeState(
    val hasActiveMedia: Boolean = false,
    val isFocused: Boolean = false,
) {
    fun mediaStarted(): MediaSessionLifetimeState = copy(hasActiveMedia = true)

    fun focusChanged(value: Boolean): MediaSessionLifetimeState = copy(isFocused = value)

    fun surfaceTornDown(): MediaSessionLifetimeState = copy(hasActiveMedia = false)

    fun shouldKeepNativeSession(): Boolean = hasActiveMedia

    fun shouldDisposeAfterSourceTeardown(): Boolean = !hasActiveMedia
}

internal data class MediaSessionMetadata(
    val title: String,
    val subtitle: String?,
    val artworkUrl: String?,
)

internal fun PlayerNowPlayingInfo.toMediaSessionMetadata(): MediaSessionMetadata? {
    val normalizedTitle = title.trim().takeIf { it.isNotEmpty() } ?: return null
    return MediaSessionMetadata(
        title = normalizedTitle,
        subtitle = subtitle?.trim()?.takeIf { it.isNotEmpty() },
        artworkUrl = artworkUrl?.trim()?.takeIf { it.isNotEmpty() },
    )
}

internal fun PlayerPlaybackSnapshot.toMediaSessionPlaybackState(): MediaSessionPlaybackState = when {
    isEnded -> MediaSessionPlaybackState.Stopped
    isLoading -> MediaSessionPlaybackState.Changing
    isPlaying -> MediaSessionPlaybackState.Playing
    else -> MediaSessionPlaybackState.Paused
}

internal fun dispatchMediaKeyCommand(
    command: MediaKeyCommand,
    controller: PlayerEngineController,
) {
    when (command) {
        MediaKeyCommand.PlayPause -> controller.togglePlayPause()
        MediaKeyCommand.Stop -> controller.pause()
        MediaKeyCommand.Previous -> controller.seekBy(-60_000L)
        MediaKeyCommand.Next -> controller.seekBy(60_000L)
    }
}
