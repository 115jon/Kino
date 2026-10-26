package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsVideoSurfacePolicyTest {
    @Test
    fun `video surface stays hidden while a new source is resolving`() {
        assertEquals(
            false,
            shouldRevealWindowsVideoSurface(
                loadPending = true,
                activePlaylistEntryGeneration = 1L,
                sourceGeneration = 1L,
                videoOutputConfigured = "yes",
                videoWidth = 1920,
                videoHeight = 1080,
            ),
        )
    }

    @Test
    fun `video surface stays hidden until the current source has started`() {
        assertEquals(
            false,
            shouldRevealWindowsVideoSurface(
                loadPending = false,
                activePlaylistEntryGeneration = null,
                sourceGeneration = 2L,
                videoOutputConfigured = "yes",
                videoWidth = 1920,
                videoHeight = 1080,
            ),
        )
        assertEquals(
            false,
            shouldRevealWindowsVideoSurface(
                loadPending = false,
                activePlaylistEntryGeneration = 1L,
                sourceGeneration = 2L,
                videoOutputConfigured = "yes",
                videoWidth = 1920,
                videoHeight = 1080,
            ),
        )
    }

    @Test
    fun `video surface is revealed once the current source has video output`() {
        assertEquals(
            true,
            shouldRevealWindowsVideoSurface(
                loadPending = false,
                activePlaylistEntryGeneration = 2L,
                sourceGeneration = 2L,
                videoOutputConfigured = "yes",
                videoWidth = 1920,
                videoHeight = 1080,
            ),
        )
        assertEquals(
            false,
            shouldRevealWindowsVideoSurface(
                loadPending = false,
                activePlaylistEntryGeneration = 2L,
                sourceGeneration = 2L,
                videoOutputConfigured = "yes",
                videoWidth = 0,
                videoHeight = 0,
            ),
        )
    }
}
