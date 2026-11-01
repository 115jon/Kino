package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerTimelineTest {
    @Test
    fun finishingWithoutAScrubDoesNotSeek() {
        assertNull(timelineScrubCommitTarget(scrubPositionMs = null, durationMs = 120_000L))
    }

    @Test
    fun finishingAScrubSeeksToTheScrubbedPosition() {
        assertEquals(42_000L, timelineScrubCommitTarget(scrubPositionMs = 42_000L, durationMs = 120_000L))
    }

    @Test
    fun scrubTargetsStayInsideTheMedia() {
        assertEquals(120_000L, timelineScrubCommitTarget(scrubPositionMs = 130_000L, durationMs = 120_000L))
        assertEquals(0L, timelineScrubCommitTarget(scrubPositionMs = -5L, durationMs = 120_000L))
        assertEquals(0L, timelineScrubCommitTarget(scrubPositionMs = 5_000L, durationMs = 0L))
    }
}
