package com.nuvio.app.features.player.compose

import com.nuvio.app.features.player.MpvOption
import com.nuvio.app.features.player.MpvPlaybackState
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
    fun `compose is the default surface unless hdr passthrough is on`() {
        assertEquals("compose", defaultWindowsVideoSurface(hdrPassthrough = false))
        assertEquals("native", defaultWindowsVideoSurface(hdrPassthrough = true))
    }

    @Test
    fun `compose video mpv options default to zero copy nvdec with copy fallbacks`() {
        assertEquals(ComposeVideoDefaultHwdec, composeVideoHwdec(null))
        assertEquals(ComposeVideoDefaultHwdec, composeVideoHwdec(" "))
        assertEquals("d3d11va-copy", composeVideoHwdec("d3d11va-copy"))
        assertEquals(
            listOf(MpvOption("video-sync", "display-resample"), MpvOption("hwdec", "no")),
            parseComposeVideoMpvOverrides("video-sync=display-resample, hwdec=no,broken"),
        )
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
        val state = MpvPlaybackState().apply {
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
