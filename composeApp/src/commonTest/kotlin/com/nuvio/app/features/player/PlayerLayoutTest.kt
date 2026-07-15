package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import androidx.compose.ui.Modifier

class PlayerLayoutTest {
    @Test
    fun `desktop mode starts at the approved 1024 dp breakpoint`() {
        assertEquals(PlayerWindowMode.Mobile, playerWindowMode(1023f))
        assertEquals(PlayerWindowMode.Desktop, playerWindowMode(1024f))
        assertEquals(PlayerWindowMode.Desktop, playerWindowMode(1920f))
    }

    @Test
    fun `desktop platform keeps desktop controls available at usable scaled widths`() {
        assertEquals(PlayerWindowMode.Mobile, playerWindowMode(767f, desktopPlatform = false))
        assertEquals(PlayerWindowMode.Desktop, playerWindowMode(768f, desktopPlatform = true))
    }

    @Test
    fun `desktop platform remains desktop below the content breakpoint`() {
        assertEquals(PlayerWindowMode.Desktop, playerWindowMode(640f, desktopPlatform = true))
    }

    @Test
    fun `desktop volume label contains only the current level`() {
        assertEquals("100%", desktopVolumeLabel(PlayerAudioLevel(1f, false)))
        assertEquals("38%", desktopVolumeLabel(PlayerAudioLevel(0.375f, false)))
        assertEquals("0%", desktopVolumeLabel(PlayerAudioLevel(0.8f, true)))
    }

    @Test
    fun `desktop surface exit hides unlocked controls and clears paused overlay`() {
        val runtime = PlayerScreenRuntime(
            PlayerScreenArgs(
                profileId = 1,
                title = "Test",
                sourceUrl = "https://example.com/video",
                sourceAudioUrl = null,
                sourceHeaders = emptyMap(),
                sourceResponseHeaders = emptyMap(),
                streamType = null,
                providerName = "Test",
                streamTitle = "Test",
                streamSubtitle = null,
                initialBingeGroup = null,
                pauseDescription = null,
                onBack = {},
                onOpenInExternalPlayer = null,
                onOpenExternalUrl = null,
                modifier = Modifier,
                logo = null,
                poster = null,
                background = null,
                seasonNumber = null,
                episodeNumber = null,
                episodeTitle = null,
                episodeThumbnail = null,
                contentType = null,
                videoId = null,
                parentMetaId = "test",
                parentMetaType = "movie",
                providerAddonId = null,
                torrentInfoHash = null,
                torrentFileIdx = null,
                torrentFilename = null,
                torrentTrackers = emptyList(),
                initialPositionMs = 0L,
                initialProgressFraction = null,
            ),
        )
        runtime.pausedOverlayVisible = true

        runtime.onPlayerSurfaceExit()

        assertEquals(false, runtime.controlsVisible)
        assertEquals(false, runtime.pausedOverlayVisible)
    }
}
