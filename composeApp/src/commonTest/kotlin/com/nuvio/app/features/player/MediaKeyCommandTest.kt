package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaKeyCommandTest {
    @Test
    fun mediaSessionMetadataUsesOnlyNonBlankPlayerInfo() {
        assertEquals(
            MediaSessionMetadata(
                title = "Episode 1",
                subtitle = "S1E1 - Pilot",
                artworkUrl = "https://example.test/poster.jpg",
            ),
            PlayerNowPlayingInfo(
                title = " Episode 1 ",
                subtitle = " S1E1 - Pilot ",
                artworkUrl = " https://example.test/poster.jpg ",
            ).toMediaSessionMetadata(),
        )
        assertNull(
            PlayerNowPlayingInfo(
                title = "Episode 1",
                artworkUrl = " ",
            ).toMediaSessionMetadata()?.artworkUrl,
        )
        assertNull(PlayerNowPlayingInfo(title = " ").toMediaSessionMetadata())
    }

    @Test
    fun mediaSessionStateFollowsPlaybackSnapshot() {
        assertEquals(
            MediaSessionPlaybackState.Changing,
            PlayerPlaybackSnapshot(isLoading = true).toMediaSessionPlaybackState(),
        )
        assertEquals(
            MediaSessionPlaybackState.Playing,
            PlayerPlaybackSnapshot(isLoading = false, isPlaying = true).toMediaSessionPlaybackState(),
        )
        assertEquals(
            MediaSessionPlaybackState.Paused,
            PlayerPlaybackSnapshot(isLoading = false).toMediaSessionPlaybackState(),
        )
        assertEquals(
            MediaSessionPlaybackState.Stopped,
            PlayerPlaybackSnapshot(isLoading = false, isEnded = true).toMediaSessionPlaybackState(),
        )
    }

    @Test
    fun playPauseUsesTheAtomicPlayerToggle() {
        val controller = RecordingPlayerController()

        dispatchMediaKeyCommand(MediaKeyCommand.PlayPause, controller)
        dispatchMediaKeyCommand(MediaKeyCommand.PlayPause, controller)

        assertEquals(listOf("toggle", "toggle"), controller.actions)
    }

    @Test
    fun transportCommandsUseThePlayerController() {
        val controller = RecordingPlayerController()

        dispatchMediaKeyCommand(MediaKeyCommand.Stop, controller)
        dispatchMediaKeyCommand(MediaKeyCommand.Previous, controller)
        dispatchMediaKeyCommand(MediaKeyCommand.Next, controller)

        assertEquals(
            listOf("pause", "seek:-60000", "seek:60000"),
            controller.actions,
        )
    }

    @Test
    fun focusLossKeepsActiveMediaSessionAlive() {
        val state = MediaSessionLifetimeState().mediaStarted().focusChanged(true)

        val unfocusedState = state.focusChanged(false)

        assertEquals(true, unfocusedState.shouldKeepNativeSession())
    }

    @Test
    fun surfaceTeardownAllowsMediaSessionDisposal() {
        val state = MediaSessionLifetimeState().mediaStarted().surfaceTornDown()

        assertEquals(false, state.shouldKeepNativeSession())
    }

    @Test
    fun sourceTeardownDisposesOnlyWhenMediaIsInactive() {
        assertEquals(true, MediaSessionLifetimeState().shouldDisposeAfterSourceTeardown())
        assertEquals(
            false,
            MediaSessionLifetimeState().mediaStarted().shouldDisposeAfterSourceTeardown(),
        )
    }
}

private class RecordingPlayerController : PlayerEngineController {
    val actions = mutableListOf<String>()

    override fun play() = Unit

    override fun pause() {
        actions += "pause"
    }

    override fun togglePlayPause() {
        actions += "toggle"
    }

    override fun seekTo(positionMs: Long) = Unit

    override fun seekBy(offsetMs: Long) {
        actions += "seek:$offsetMs"
    }

    override fun retry() = Unit

    override fun setPlaybackSpeed(speed: Float) = Unit

    override fun getAudioTracks(): List<AudioTrack> = emptyList()

    override fun getSubtitleTracks(): List<SubtitleTrack> = emptyList()

    override fun selectAudioTrack(index: Int) = Unit

    override fun selectSubtitleTrack(index: Int) = Unit

    override fun setSubtitleUri(url: String) = Unit

    override fun clearExternalSubtitle() = Unit

    override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit
}
