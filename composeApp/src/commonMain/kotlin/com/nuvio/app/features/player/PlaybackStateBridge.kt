package com.nuvio.app.features.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class PlayerPlaybackTimeline(
    val positionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1f,
    val isLoading: Boolean = true,
    val isPlaying: Boolean = false,
    val isEnded: Boolean = false,
)

internal class PlaybackStateBridge(
    initialSnapshot: PlayerPlaybackSnapshot = PlayerPlaybackSnapshot(),
) {
    private val _timeline = MutableStateFlow(initialSnapshot.toTimeline())

    var latestSnapshot: PlayerPlaybackSnapshot = initialSnapshot
        private set

    var semanticSnapshot: PlayerPlaybackSnapshot = initialSnapshot.toSemanticSnapshot()
        private set

    val timeline: StateFlow<PlayerPlaybackTimeline> = _timeline.asStateFlow()

    fun publish(snapshot: PlayerPlaybackSnapshot): Boolean {
        val semanticChanged = semanticSnapshot != snapshot.toSemanticSnapshot()
        latestSnapshot = snapshot
        _timeline.value = snapshot.toTimeline()
        if (semanticChanged) {
            semanticSnapshot = snapshot.toSemanticSnapshot()
        }
        return semanticChanged
    }

    fun reset() {
        latestSnapshot = PlayerPlaybackSnapshot()
        semanticSnapshot = PlayerPlaybackSnapshot()
        _timeline.value = PlayerPlaybackTimeline()
    }
}

internal val PlayerScreenRuntime.playbackTimeline: StateFlow<PlayerPlaybackTimeline>
    get() = playbackStateBridge.timeline

internal val PlayerScreenRuntime.latestPlaybackSnapshot: PlayerPlaybackSnapshot
    get() = playbackStateBridge.latestSnapshot

internal fun PlayerScreenRuntime.publishPlaybackSnapshot(snapshot: PlayerPlaybackSnapshot) {
    if (playbackStateBridge.publish(snapshot)) {
        playbackSnapshot = playbackStateBridge.semanticSnapshot
    }
}

internal fun PlayerScreenRuntime.resetPlaybackSnapshotState() {
    playbackStateBridge.reset()
    playbackSnapshot = playbackStateBridge.semanticSnapshot
}

private fun PlayerPlaybackSnapshot.toSemanticSnapshot(): PlayerPlaybackSnapshot =
    copy(positionMs = 0L, bufferedPositionMs = 0L)

private fun PlayerPlaybackSnapshot.toTimeline(): PlayerPlaybackTimeline =
    PlayerPlaybackTimeline(
        positionMs = positionMs,
        bufferedPositionMs = bufferedPositionMs,
        durationMs = durationMs,
        playbackSpeed = playbackSpeed,
        isLoading = isLoading,
        isPlaying = isPlaying,
        isEnded = isEnded,
    )
