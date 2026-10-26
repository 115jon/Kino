package com.nuvio.app.features.player.compose

import com.nuvio.app.features.player.WindowsPlayerWindowState
import kotlin.test.Test
import kotlin.test.assertEquals

class ComposeVideoSurfacePolicyTest {
    @Test
    fun `compose surface is selected only by its names`() {
        assertEquals(true, isComposeWindowsVideoSurface("compose"))
        assertEquals(true, isComposeWindowsVideoSurface(" Skia "))
        assertEquals(false, isComposeWindowsVideoSurface(null))
        assertEquals(false, isComposeWindowsVideoSurface("native"))
        assertEquals(false, isComposeWindowsVideoSurface("embedded"))
    }

    @Test
    fun `video stays hidden while a new load is pending`() {
        assertEquals(
            false,
            shouldRevealComposeVideo(
                armedRestartGeneration = 3L,
                loadPending = true,
                activePlaylistEntryGeneration = 3L,
                sourceGeneration = 3L,
            ),
        )
    }

    @Test
    fun `video stays hidden after a playback restart from the previous source`() {
        assertEquals(
            false,
            shouldRevealComposeVideo(
                armedRestartGeneration = 2L,
                loadPending = false,
                activePlaylistEntryGeneration = 3L,
                sourceGeneration = 3L,
            ),
        )
    }

    @Test
    fun `video stays hidden until the current source has started`() {
        assertEquals(
            false,
            shouldRevealComposeVideo(
                armedRestartGeneration = 3L,
                loadPending = false,
                activePlaylistEntryGeneration = 2L,
                sourceGeneration = 3L,
            ),
        )
    }

    @Test
    fun `video is revealed after the current source restarts playback`() {
        assertEquals(
            true,
            shouldRevealComposeVideo(
                armedRestartGeneration = 3L,
                loadPending = false,
                activePlaylistEntryGeneration = 3L,
                sourceGeneration = 3L,
            ),
        )
    }

    @Test
    fun `reveal tracker requires a render that started after the restart`() {
        val state = WindowsPlayerWindowState().apply {
            sourceGeneration = 4L
            activePlaylistEntryGeneration = 4L
        }
        val tracker = ComposeVideoRevealTracker()
        tracker.armForRender()
        tracker.onPlaybackRestart(4L)
        assertEquals(false, tracker.shouldReveal(state))
        tracker.armForRender()
        assertEquals(true, tracker.shouldReveal(state))
        tracker.reset()
        assertEquals(false, tracker.shouldReveal(state))
    }
}
