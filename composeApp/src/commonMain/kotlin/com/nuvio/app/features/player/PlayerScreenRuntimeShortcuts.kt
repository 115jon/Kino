package com.nuvio.app.features.player

import nuvio.composeapp.generated.resources.*
import kotlin.time.TimeSource

internal val PlayerScreenRuntime.canPlayNextEpisodeNow: Boolean
    get() = nextEpisodeInfo?.hasAired == true && !nextEpisodeAutoPlaySearching && nextEpisodeAutoPlayCountdown == null

internal fun PlayerScreenRuntime.handlePlayerShortcut(shortcut: PlayerShortcut): Boolean {
    if (shortcut == PlayerShortcut.Back) {
        handleBackShortcut()
        return true
    }
    if (playerControlsLocked) {
        revealLockedOverlay()
        return true
    }
    when (shortcut) {
        PlayerShortcut.TogglePlayback -> {
            val wasVisible = controlsVisible
            togglePlayback()
            controlsVisible = wasVisible
        }
        is PlayerShortcut.Seek -> seekBy(shortcut.offsetMs)
        is PlayerShortcut.SeekToTenth -> seekToTenth(shortcut.tenth)
        is PlayerShortcut.Volume -> changeVolumeBy(shortcut.delta)
        PlayerShortcut.ToggleMute -> toggleMute()
        PlayerShortcut.ToggleFullscreen -> playerController?.toggleFullscreen()
        PlayerShortcut.NextEpisode -> if (canPlayNextEpisodeNow && !partyBlocksControl()) playNextEpisode()
        PlayerShortcut.SubtitleMenu -> {
            if (!showSubtitleModal) refreshTracks()
            showAudioModal = false
            showSubtitleModal = !showSubtitleModal
        }
        PlayerShortcut.AudioMenu -> {
            if (!showAudioModal) refreshTracks()
            showSubtitleModal = false
            showAudioModal = !showAudioModal
        }
        PlayerShortcut.ToggleSubtitles -> toggleSubtitleVisibility()
        is PlayerShortcut.Speed -> applyPlaybackSpeed(steppedPlaybackSpeed(playbackSnapshot.playbackSpeed, shortcut.step))
        PlayerShortcut.ShortcutHelp -> showShortcutHelp = !showShortcutHelp
        PlayerShortcut.Back -> Unit
    }
    if (shortcut.revealsControls) {
        controlsVisible = true
        controlsActivityTick += 1
        pausedOverlayVisible = false
    }
    return true
}

private fun PlayerScreenRuntime.handleBackShortcut() {
    when (playerBackAction(playerController?.fullscreen?.value == true, hasOpenPlayerOverlay)) {
        PlayerBackAction.ExitFullscreen -> playerController?.exitFullscreen()
        PlayerBackAction.CloseOverlay -> dismissTopPlayerOverlay()
        PlayerBackAction.LeavePlayer -> {
            flushWatchProgress()
            args.onBack()
        }
    }
}

private fun PlayerScreenRuntime.dismissTopPlayerOverlay() {
    when {
        showShortcutHelp -> showShortcutHelp = false
        showSwitchEngineConfirm -> showSwitchEngineConfirm = false
        showSubmitIntroModal -> showSubmitIntroModal = false
        showStreamInfo -> showStreamInfo = false
        showVideoSettingsModal -> showVideoSettingsModal = false
        showSubtitleModal -> showSubtitleModal = false
        showAudioModal -> showAudioModal = false
        showSourcesPanel -> closeSourcesPanel()
        showEpisodesPanel && episodeStreamsPanelState.showStreams -> showEpisodeList()
        showEpisodesPanel -> closeEpisodesPanel()
        showPartyChat -> showPartyChat = false
        showPartyPanel -> showPartyPanel = false
    }
}

private fun PlayerScreenRuntime.seekToTenth(tenth: Int) {
    val requestedMs = shortcutSeekTarget(tenth, playbackSnapshot.durationMs) ?: return
    val fromMs = currentPlaybackPositionMs()
    val targetMs = if (isPartyControlLocked) ownSeekTarget(requestedMs) ?: return else requestedMs
    lastManualSkipSeekPositions = fromMs to targetMs
    pendingSeekTarget = targetMs to TimeSource.Monotonic.markNow()
    playerController?.seekTo(targetMs)
    scheduleProgressSyncAfterSeek()
    publishPartyPlayback(positionMs = targetMs)
    showGestureFeedback(
        GestureFeedbackState(
            message = formatPlaybackTime(targetMs),
            icon = if (targetMs >= fromMs) GestureFeedbackIcon.SeekForward else GestureFeedbackIcon.SeekBackward,
        ),
    )
}

private fun PlayerScreenRuntime.currentVolumeFraction(): Float {
    val level = playerController?.currentVolumeLevel() ?: volumeLevel
    return if (level.isMuted) 0f else level.fraction
}

private fun PlayerScreenRuntime.changeVolumeBy(delta: Float) {
    setVolumeLevel(currentVolumeFraction() + delta)
    showVolumeFeedback(volumeLevel)
}

private fun PlayerScreenRuntime.toggleMute() {
    val muted = currentVolumeFraction() <= 0f
    setVolumeLevel(if (muted) lastUnmutedVolumeFraction.takeIf { it > 0f } ?: 1f else 0f)
    showVolumeFeedback(volumeLevel)
}

private fun PlayerScreenRuntime.toggleSubtitleVisibility() {
    val visible = subtitlesHidden
    if (playerController?.setSubtitlesVisible(visible) != true) return
    subtitlesHidden = !visible
    showGestureFeedback(
        GestureFeedbackState(
            messageRes = if (visible) Res.string.compose_player_subtitles_shown else Res.string.compose_player_subtitles_hidden,
            icon = if (visible) GestureFeedbackIcon.Subtitles else GestureFeedbackIcon.SubtitlesOff,
        ),
    )
}
