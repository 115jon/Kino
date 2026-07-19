package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun windowsPlaybackStartupStallRequiresAnIdleLoadedPath() {
        assertEquals(
            true,
            isWindowsPlaybackStartupStallCandidate(
                path = "episode.mkv",
                durationMs = 0L,
                idle = true,
                paused = false,
            ),
        )
        assertEquals(
            false,
            isWindowsPlaybackStartupStallCandidate(
                path = null,
                durationMs = 0L,
                idle = true,
                paused = false,
            ),
        )
        assertEquals(
            false,
            isWindowsPlaybackStartupStallCandidate(
                path = "episode.mkv",
                durationMs = 120_000L,
                idle = true,
                paused = false,
            ),
        )
    }

    @Test
    fun windowsPlaybackStartupStallDoesNotBecomeTerminalError() {
        assertEquals(
            null,
            selectWindowsPlaybackError(
                mpvErrorMessage = null,
                hasLoadedMedia = false,
                startupStalled = true,
            ),
        )
        assertEquals(
            "Failed to open stream",
            selectWindowsPlaybackError(
                mpvErrorMessage = "Failed to open stream",
                hasLoadedMedia = false,
                startupStalled = true,
            ),
        )
        assertEquals(
            null,
            selectWindowsPlaybackError(
                mpvErrorMessage = "stale mpv error",
                hasLoadedMedia = true,
                startupStalled = false,
            ),
        )
    }

    @Test
    fun startupStallRemainsLatchedUntilMediaLoads() {
        val initial = WindowsPlaybackStartupState()
        val waiting = reduceWindowsPlaybackStartupState(
            state = initial,
            path = "episode.mkv",
            durationMs = 0L,
            idle = true,
            paused = false,
            nowMs = 1_000L,
            timeoutMs = 30_000L,
            hasLoadedMedia = false,
        )
        val stalled = reduceWindowsPlaybackStartupState(
            state = waiting,
            path = "episode.mkv",
            durationMs = 0L,
            idle = true,
            paused = false,
            nowMs = 31_000L,
            timeoutMs = 30_000L,
            hasLoadedMedia = false,
        )
        val latched = reduceWindowsPlaybackStartupState(
            state = stalled,
            path = null,
            durationMs = 0L,
            idle = false,
            paused = false,
            nowMs = 32_000L,
            timeoutMs = 30_000L,
            hasLoadedMedia = false,
        )
        val loaded = reduceWindowsPlaybackStartupState(
            state = latched,
            path = "episode.mkv",
            durationMs = 120_000L,
            idle = false,
            paused = false,
            nowMs = 33_000L,
            timeoutMs = 30_000L,
            hasLoadedMedia = true,
        )

        assertFalse(waiting.isStalled)
        assertTrue(stalled.isStalled)
        assertTrue(latched.isStalled)
        assertFalse(loaded.isStalled)
    }

    @Test
    fun pendingPathlessStartupCanStall() {
        val waiting = reduceWindowsPlaybackStartupState(
            state = WindowsPlaybackStartupState(),
            path = null,
            durationMs = 0L,
            idle = true,
            paused = false,
            nowMs = 1_000L,
            timeoutMs = 30_000L,
            hasLoadedMedia = false,
            loadPending = true,
        )
        val stalled = reduceWindowsPlaybackStartupState(
            state = waiting,
            path = null,
            durationMs = 0L,
            idle = true,
            paused = false,
            nowMs = 31_000L,
            timeoutMs = 30_000L,
            hasLoadedMedia = false,
            loadPending = true,
        )

        assertTrue(stalled.isStalled)
    }

    @Test
    fun defaultPlaybackSnapshotIsNotStartupStalled() {
        assertFalse(PlayerPlaybackSnapshot().isStartupStalled)
    }

    @Test
    fun staleStartFileEventsDoNotCrossLoadRequests() {
        assertFalse(
            isWindowsPlaybackStartFileCurrent(
                playlistEntryId = 41L,
                expectedPlaylistEntryId = 42L,
                previousPlaylistEntryId = 40L,
            ),
        )
        assertTrue(
            isWindowsPlaybackStartFileCurrent(
                playlistEntryId = 42L,
                expectedPlaylistEntryId = 42L,
                previousPlaylistEntryId = 40L,
            ),
        )
    }

    @Test
    fun initialBufferingWithDurationIsNotLoaded() {
        assertFalse(
            PlayerPlaybackSnapshot(
                isLoading = true,
                durationMs = 120_000L,
                positionMs = 0L,
            ).hasLoadedMedia(),
        )
    }

    @Test
    fun loadedMediaRemainsLoadedWhileSeeking() {
        assertTrue(
            PlayerPlaybackSnapshot(
                isLoading = true,
                mediaLoaded = true,
                durationMs = 120_000L,
                positionMs = 0L,
            ).hasLoadedMedia(),
        )
    }

    @Test
    fun activeEndFileErrorsBecomeTerminal() {
        assertEquals(
            "network failed",
            selectWindowsPlaybackEndFileError(
                event = WindowsPlaybackEndFile(
                    reason = WindowsMpvEndFileReasonError,
                    errorMessage = "network failed",
                    playlistEntryId = 42L,
                ),
                activePlaylistEntryId = 42L,
                activePlaylistEntryGeneration = 7L,
                currentSourceGeneration = 7L,
                hasLoadedMedia = false,
            ),
        )
    }

    @Test
    fun eofBeforeMediaLoadsIsTerminalButNormalCompletionIsNot() {
        assertEquals(
            "Failed to open stream",
            selectWindowsPlaybackEndFileError(
                event = WindowsPlaybackEndFile(
                    reason = WindowsMpvEndFileReasonEof,
                    errorMessage = null,
                    playlistEntryId = 42L,
                ),
                activePlaylistEntryId = 42L,
                activePlaylistEntryGeneration = 7L,
                currentSourceGeneration = 7L,
                hasLoadedMedia = false,
            ),
        )
        assertNull(
            selectWindowsPlaybackEndFileError(
                event = WindowsPlaybackEndFile(
                    reason = WindowsMpvEndFileReasonEof,
                    errorMessage = null,
                    playlistEntryId = 42L,
                ),
                activePlaylistEntryId = 42L,
                activePlaylistEntryGeneration = 7L,
                currentSourceGeneration = 7L,
                hasLoadedMedia = true,
            ),
        )
        assertNull(
            selectWindowsPlaybackEndFileError(
                event = WindowsPlaybackEndFile(
                    reason = WindowsMpvEndFileReasonStop,
                    errorMessage = "ignored",
                    playlistEntryId = 42L,
                ),
                activePlaylistEntryId = 42L,
                activePlaylistEntryGeneration = 7L,
                currentSourceGeneration = 7L,
                hasLoadedMedia = false,
            ),
        )
        assertNull(
            selectWindowsPlaybackEndFileError(
                event = WindowsPlaybackEndFile(
                    reason = WindowsMpvEndFileReasonRedirect,
                    errorMessage = "ignored",
                    playlistEntryId = 42L,
                ),
                activePlaylistEntryId = 42L,
                activePlaylistEntryGeneration = 7L,
                currentSourceGeneration = 7L,
                hasLoadedMedia = false,
            ),
        )
    }

    @Test
    fun staleEndFileEventsAreIgnored() {
        val event = WindowsPlaybackEndFile(
            reason = WindowsMpvEndFileReasonError,
            errorMessage = "stale",
            playlistEntryId = 42L,
        )

        assertNull(
            selectWindowsPlaybackEndFileError(
                event = event,
                activePlaylistEntryId = 99L,
                activePlaylistEntryGeneration = 7L,
                currentSourceGeneration = 7L,
                hasLoadedMedia = false,
            ),
        )
        assertNull(
            selectWindowsPlaybackEndFileError(
                event = event,
                activePlaylistEntryId = 42L,
                activePlaylistEntryGeneration = 6L,
                currentSourceGeneration = 7L,
                hasLoadedMedia = false,
            ),
        )
    }

    @Test
    fun negativeLoadCommandResultBecomesTerminal() {
        assertEquals("Failed to open stream", selectWindowsPlaybackCommandError(-1))
        assertNull(selectWindowsPlaybackCommandError(0))
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
