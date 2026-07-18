package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackStateBridgeTest {
    @Test
    fun publishes_timeline_without_republishing_semantic_state() {
        val bridge = PlaybackStateBridge()
        val ready = PlayerPlaybackSnapshot(
            isLoading = false,
            isPlaying = true,
            durationMs = 120_000L,
            positionMs = 1_000L,
            bufferedPositionMs = 10_000L,
            videoWidth = 1920,
            videoHeight = 1080,
        )

        assertTrue(bridge.publish(ready))
        assertFalse(bridge.publish(ready.copy(positionMs = 1_250L, bufferedPositionMs = 10_250L)))
        assertEquals(1_250L, bridge.latestSnapshot.positionMs)
        assertEquals(10_250L, bridge.timeline.value.bufferedPositionMs)
        assertEquals(ready.copy(positionMs = 0L, bufferedPositionMs = 0L), bridge.semanticSnapshot)
    }

    @Test
    fun publishes_semantic_changes_immediately() {
        val bridge = PlaybackStateBridge()
        val ready = PlayerPlaybackSnapshot(
            isLoading = false,
            isPlaying = true,
            durationMs = 120_000L,
        )

        bridge.publish(ready)

        assertTrue(bridge.publish(ready.copy(isPlaying = false)))
        assertEquals(false, bridge.semanticSnapshot.isPlaying)
    }

    @Test
    fun timeline_reflects_semantic_transitions_at_the_same_position() {
        val bridge = PlaybackStateBridge()
        val playing = PlayerPlaybackSnapshot(
            isLoading = false,
            isPlaying = true,
            durationMs = 60_000L,
            positionMs = 10_000L,
        )

        bridge.publish(playing)
        bridge.publish(playing.copy(isPlaying = false))

        assertEquals(10_000L, bridge.timeline.value.positionMs)
        assertFalse(bridge.timeline.value.isPlaying)
    }

    @Test
    fun reset_clears_exact_and_semantic_state() {
        val bridge = PlaybackStateBridge()
        bridge.publish(
            PlayerPlaybackSnapshot(
                isLoading = false,
                isPlaying = true,
                durationMs = 120_000L,
                positionMs = 42_000L,
            ),
        )

        bridge.reset()

        assertEquals(PlayerPlaybackSnapshot(), bridge.latestSnapshot)
        assertEquals(PlayerPlaybackTimeline(), bridge.timeline.value)
        assertEquals(PlayerPlaybackSnapshot(), bridge.semanticSnapshot)
    }
}
