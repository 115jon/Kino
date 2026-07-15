package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopVideoCompatibilityTest {
    @Test
    fun windowsPlaybackDoesNotEndWithoutLoadedMedia() {
        assertEquals(
            false,
            isWindowsPlaybackEnded(path = null, durationMs = 0L, eofReached = true),
        )
        assertEquals(
            false,
            isWindowsPlaybackEnded(path = "episode.mkv", durationMs = 0L, eofReached = true),
        )
        assertEquals(
            true,
            isWindowsPlaybackEnded(path = "episode.mkv", durationMs = 120_000L, eofReached = true),
        )
    }

    @Test
    fun windowsPlaybackWithoutPathIsLoadingAndNotPlaying() {
        assertEquals(
            true,
            isWindowsPlaybackLoading(
                path = null,
                idle = false,
                paused = false,
                eofReached = false,
                seeking = false,
                bufferingCache = false,
            ),
        )
        assertEquals(
            false,
            isWindowsPlaybackPlaying(
                path = null,
                paused = false,
                idle = false,
                eofReached = false,
                durationMs = 0L,
            ),
        )
    }

    @Test
    fun windowsPlaybackStartupStallsOnlyAfterTimeout() {
        assertEquals(
            false,
            isWindowsPlaybackStartupStalled(
                path = "episode.mkv",
                durationMs = 0L,
                idle = true,
                paused = false,
                startedAtMs = 1_000L,
                nowMs = 9_999L,
                timeoutMs = 10_000L,
            ),
        )
        assertEquals(
            true,
            isWindowsPlaybackStartupStalled(
                path = "episode.mkv",
                durationMs = 0L,
                idle = true,
                paused = false,
                startedAtMs = 1_000L,
                nowMs = 11_000L,
                timeoutMs = 10_000L,
            ),
        )
        assertEquals(
            false,
            isWindowsPlaybackStartupStalled(
                path = "episode.mkv",
                durationMs = 1_000L,
                idle = true,
                paused = false,
                startedAtMs = 1_000L,
                nowMs = 20_000L,
                timeoutMs = 10_000L,
            ),
        )
        assertEquals(
            false,
            isWindowsPlaybackStartupStalled(
                path = "episode.mkv",
                durationMs = 0L,
                idle = true,
                paused = true,
                startedAtMs = 1_000L,
                nowMs = 20_000L,
                timeoutMs = 10_000L,
            ),
        )
    }

    @Test
    fun dolbyVisionMetadataSelectsDolbyVisionCompatibility() {
        val decision = selectDesktopVideoPipeline(
            DesktopVideoMetadata(
                codec = "dvhe",
                pixelFormat = "yuv420p10le",
            )
        )

        assertEquals(DesktopVideoPipelineMode.DolbyVisionCompatibility, decision.mode)
    }

    @Test
    fun dolbyVisionMatrixSelectsDolbyVisionCompatibility() {
        val decision = selectDesktopVideoPipeline(
            DesktopVideoMetadata(
                codec = "H.265 / HEVC",
                pixelFormat = "p010",
                primaries = "bt.2020",
                transfer = "pq",
                matrix = "dolbyvision",
            ),
        )

        assertEquals(DesktopVideoPipelineMode.DolbyVisionCompatibility, decision.mode)
    }

    @Test
    fun hdrTransferSelectsHdrCompatibility() {
        val decision = selectDesktopVideoPipeline(
            DesktopVideoMetadata(
                codec = "hevc",
                pixelFormat = "p010le",
                primaries = "bt.2020",
                transfer = "pq",
            )
        )

        assertEquals(DesktopVideoPipelineMode.HdrCompatibility, decision.mode)
    }

    @Test
    fun ordinarySdrVideoKeepsStandardPipeline() {
        val decision = selectDesktopVideoPipeline(
            DesktopVideoMetadata(
                codec = "hevc",
                pixelFormat = "yuv420p",
                primaries = "bt.709",
                transfer = "bt.709",
            )
        )

        assertEquals(DesktopVideoPipelineMode.Standard, decision.mode)
    }
}
