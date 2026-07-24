package com.nuvio.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import com.nuvio.app.features.p2p.P2pStreamingState
import com.nuvio.app.features.p2p.formatP2pMegabytes
import com.nuvio.app.features.p2p.formatP2pSpeed
import com.nuvio.app.isIos
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*

@Composable
internal fun PlayerScreenRuntime.RenderPlayerRuntimeUi() {
    val runtime = this
    val isEpisode = activeSeasonNumber != null && activeEpisodeNumber != null
    val resolvedMeta = metaUiState.meta?.takeIf { it.id == parentMetaId }
    val displayLogo = logo ?: resolvedMeta?.logo
    val displayPoster = poster ?: resolvedMeta?.poster
    val displayBackground = background ?: resolvedMeta?.background
    val displayArtwork = displayBackground ?: displayPoster
    val currentGestureFeedback = liveGestureFeedback ?: gestureFeedback
    val isP2pPlaybackActive = activeTorrentInfoHash != null
    val p2pStats = p2pStreamingState as? P2pStreamingState.Streaming
    val p2pPeerInfo = p2pStats?.let { stats ->
        org.jetbrains.compose.resources.stringResource(
            nuvio.composeapp.generated.resources.Res.string.player_torrent_peer_info,
            stats.seeds,
            stats.peers,
        )
    }
    val p2pDownloadSpeed = p2pStats?.let { formatP2pSpeed(it.downloadSpeed) }
    val p2pInitialLoadingMessage = when {
        !isP2pPlaybackActive || initialLoadCompleted -> null
        p2pStreamingState is P2pStreamingState.Connecting -> {
            org.jetbrains.compose.resources.stringResource(
                nuvio.composeapp.generated.resources.Res.string.player_torrent_connecting_peers,
            )
        }
        p2pStats != null -> {
            if (p2pSettingsUiState.hideTorrentStats) {
                null
            } else {
                org.jetbrains.compose.resources.stringResource(
                    nuvio.composeapp.generated.resources.Res.string.player_torrent_buffered_status,
                    formatP2pMegabytes(p2pStats.preloadedBytes),
                    p2pPeerInfo.orEmpty(),
                    p2pDownloadSpeed.orEmpty(),
                )
            }
        }
        else -> org.jetbrains.compose.resources.stringResource(
            nuvio.composeapp.generated.resources.Res.string.player_torrent_starting_engine,
        )
    }
    val p2pInitialLoadingProgress = when {
        !isP2pPlaybackActive || initialLoadCompleted || p2pStats == null -> null
        else -> (p2pStats.preloadedBytes.toFloat() / P2pInitialPreloadTargetBytes.toFloat()).coerceIn(0f, 1f)
    }
    val showP2pRebufferStats = isP2pPlaybackActive &&
        initialLoadCompleted &&
        playbackSnapshot.isLoading &&
        p2pStats != null &&
        !p2pSettingsUiState.hideTorrentStats
    val p2pRebufferMessage = when {
        !showP2pRebufferStats -> null
        else -> {
             val bufferedSeconds = ((latestPlaybackSnapshot.bufferedPositionMs - latestPlaybackSnapshot.positionMs) / 1000L)
                .coerceAtLeast(0L)
            "${bufferedSeconds}s buffered · ${p2pPeerInfo.orEmpty()} · ${p2pDownloadSpeed.orEmpty()}"
        }
    }
    val p2pRebufferProgress = when {
        !showP2pRebufferStats -> null
        else -> {
             val bufferedSeconds = ((latestPlaybackSnapshot.bufferedPositionMs - latestPlaybackSnapshot.positionMs) / 1000f)
                .coerceAtLeast(0f)
            (bufferedSeconds / 10f).coerceIn(0f, 1f)
        }
    }
    val gestureCallbacks = rememberSurfaceGestureCallbacks()
    val overlayContent: @Composable () -> Unit = {
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { layoutSize = it }
                    .playerSurfaceTapGestures(
                        layoutSize = layoutSize,
                        playerControlsLockedState = gestureCallbacks.playerControlsLocked,
                        onSurfaceTap = gestureCallbacks.onSurfaceTap,
                        onSurfaceDoubleTap = gestureCallbacks.onSurfaceDoubleTap,
                        activateHoldToSpeedState = gestureCallbacks.activateHoldToSpeed,
                        deactivateHoldToSpeedState = gestureCallbacks.deactivateHoldToSpeed,
                        revealLockedOverlayState = gestureCallbacks.revealLockedOverlay,
                    )
                    .playerSurfaceDragGestures(
                        gestureController = gestureController,
                        layoutSize = layoutSize,
                        sideGestureSystemEdgeExclusionPx = sideGestureSystemEdgeExclusionPx,
                        playerControlsLockedState = gestureCallbacks.playerControlsLocked,
                        touchGesturesEnabledState = gestureCallbacks.touchGesturesEnabled,
                        isHoldToSpeedGestureActiveState = gestureCallbacks.isHoldToSpeedGestureActive,
                        currentPositionMsState = gestureCallbacks.currentPositionMs,
                        currentDurationMsState = gestureCallbacks.currentDurationMs,
                        deactivateHoldToSpeedState = gestureCallbacks.deactivateHoldToSpeed,
                        showHorizontalSeekPreviewState = gestureCallbacks.showHorizontalSeekPreview,
                        showBrightnessFeedbackState = gestureCallbacks.showBrightnessFeedback,
                        showVolumeFeedbackState = gestureCallbacks.showVolumeFeedback,
                        clearLiveGestureFeedbackState = gestureCallbacks.clearLiveGestureFeedback,
                        revealLockedOverlayState = gestureCallbacks.revealLockedOverlay,
                        commitHorizontalSeekState = gestureCallbacks.commitHorizontalSeek,
                    ),
            )
            AnimatedVisibility(
                visible = pausedOverlayVisible && !controlsVisible && !playerControlsLocked,
                enter = fadeIn(animationSpec = tween(durationMillis = 220)),
                exit = fadeOut(animationSpec = tween(durationMillis = 180)),
            ) {
                PauseMetadataOverlay(
                    title = title,
                    logo = displayLogo,
                    isEpisode = isEpisode,
                    seasonNumber = activeSeasonNumber,
                    episodeNumber = activeEpisodeNumber,
                    episodeTitle = activeEpisodeTitle,
                    pauseDescription = pauseDescription ?: activeStreamSubtitle,
                    providerName = activeProviderName,
                    metrics = metrics,
                    horizontalSafePadding = horizontalSafePadding,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            RenderPlayerControls(isEpisode = isEpisode)
            RenderPlaybackOverlays(
                runtime = runtime,
                currentGestureFeedback = currentGestureFeedback,
                p2pInitialLoadingMessage = p2pInitialLoadingMessage,
                p2pInitialLoadingProgress = p2pInitialLoadingProgress,
                showP2pRebufferStats = showP2pRebufferStats,
                p2pRebufferMessage = p2pRebufferMessage,
                p2pRebufferProgress = p2pRebufferProgress,
                displayArtwork = displayArtwork,
                displayLogo = displayLogo,
            )
            RenderPlayerModals(displayedPositionMs = latestPlaybackSnapshot.positionMs)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { layoutSize = it }
    ) {
        val playerSurfaceSourceUrl = if (isP2pPlaybackActive) p2pResolvedSourceUrl else activeSourceUrl
        val playerSurfaceSourceIdentityKey = activeSourceIdentityKey
        val playerSurfaceAttemptToken = activeSourceAttemptToken
        if (playerSurfaceSourceUrl != null) {
            key(playerSurfaceAttemptToken) {
                PlatformPlayerSurface(
                sourceUrl = playerSurfaceSourceUrl,
                sourceAudioUrl = activeSourceAudioUrl,
                sourceHeaders = activeSourceHeaders,
                sourceResponseHeaders = activeSourceResponseHeaders,
                externalSubtitles = externalSubtitles,
                streamType = activeStreamType,
                modifier = Modifier.fillMaxSize(),
                 playWhenReady = shouldPlay,
                 resizeMode = resizeMode,
                 overlayContent = overlayContent,
                    onControllerReady = { controller ->
                        val currentSurfaceSourceUrl = if (activeTorrentInfoHash != null) {
                            p2pResolvedSourceUrl
                        } else {
                            activeSourceUrl
                        }
                        if (!isCurrentPlayerSurfaceAttempt(
                                currentSurfaceSourceUrl = currentSurfaceSourceUrl,
                                surfaceSourceUrl = playerSurfaceSourceUrl,
                                currentSourceIdentityKey = activeSourceIdentityKey,
                                surfaceSourceIdentityKey = playerSurfaceSourceIdentityKey,
                                currentAttemptToken = activeSourceAttemptToken,
                                surfaceAttemptToken = playerSurfaceAttemptToken,
                            )) return@PlatformPlayerSurface
                        playerController = controller
                        playerControllerSourceUrl = playerSurfaceSourceUrl
                        playerControllerSourceIdentityKey = playerSurfaceSourceIdentityKey
                        playerControllerSourceAttemptToken = playerSurfaceAttemptToken
                     controller.currentVolumeLevel()?.let { level ->
                         volumeLevel = level
                         if (!level.isMuted && level.fraction > 0f) {
                             lastUnmutedVolumeFraction = level.fraction
                         }
                     }
                  },
                    onSnapshot = { snapshot ->
                        val currentSurfaceSourceUrl = if (activeTorrentInfoHash != null) {
                            p2pResolvedSourceUrl
                        } else {
                            activeSourceUrl
                        }
                        if (
                            !isCurrentPlayerSurfaceAttempt(
                                currentSurfaceSourceUrl = currentSurfaceSourceUrl,
                                surfaceSourceUrl = playerSurfaceSourceUrl,
                                currentSourceIdentityKey = activeSourceIdentityKey,
                                surfaceSourceIdentityKey = playerSurfaceSourceIdentityKey,
                                currentAttemptToken = activeSourceAttemptToken,
                                surfaceAttemptToken = playerSurfaceAttemptToken,
                            ) ||
                            playerControllerSourceUrl != playerSurfaceSourceUrl ||
                            playerControllerSourceIdentityKey != playerSurfaceSourceIdentityKey ||
                            playerControllerSourceAttemptToken != playerSurfaceAttemptToken
                        ) return@PlatformPlayerSurface
                        publishPlaybackSnapshot(snapshot)
                        if (snapshot.hasLoadedMedia()) {
                            initialLoadCompleted = true
                            markStartupFallbackMediaLoaded()
                            errorMessage = null
                        }
                        if (snapshot.isStartupStalled) {
                            if (handleStartupFallbackFailure()) return@PlatformPlayerSurface
                            controlsVisible = !playerControlsLocked
                            pausedOverlayVisible = false
                       }
                      if (snapshot.isEnded && snapshot.hasLoadedMedia()) {
                         shouldPlay = false
                         controlsVisible = !playerControlsLocked
                      }
                 },
                  onSurfaceInteraction = { isTap ->
                     if (isTap) {
                         if (playerControlsLocked) {
                             revealLockedOverlay()
                         } else {
                             controlsVisible = !controlsVisible
                         }
                     } else {
                         controlsVisible = true
                     }
                      pausedOverlayVisible = false
                   },
                  onSurfaceExit = { onPlayerSurfaceExit() },
                   onError = { message ->
                       val currentSurfaceSourceUrl = if (activeTorrentInfoHash != null) {
                           p2pResolvedSourceUrl
                       } else {
                           activeSourceUrl
                       }
                       if (
                           !isCurrentPlayerSurfaceAttempt(
                               currentSurfaceSourceUrl = currentSurfaceSourceUrl,
                               surfaceSourceUrl = playerSurfaceSourceUrl,
                               currentSourceIdentityKey = activeSourceIdentityKey,
                               surfaceSourceIdentityKey = playerSurfaceSourceIdentityKey,
                               currentAttemptToken = activeSourceAttemptToken,
                               surfaceAttemptToken = playerSurfaceAttemptToken,
                           ) ||
                           playerControllerSourceUrl != playerSurfaceSourceUrl ||
                           playerControllerSourceIdentityKey != playerSurfaceSourceIdentityKey ||
                           playerControllerSourceAttemptToken != playerSurfaceAttemptToken
                       ) return@PlatformPlayerSurface
                     if (message != null && tryRefreshCredentialedSourceAfterError(message)) {
                         return@PlatformPlayerSurface
                     }
                     if (message != null) {
                         removeFailedStreamFromCache()
                     }
                     if (message != null && handleStartupFallbackFailure()) {
                         return@PlatformPlayerSurface
                     }
                     errorMessage = message
                     if (message != null) {
                         controlsVisible = !playerControlsLocked
                     }
                },
                )
            }
        }

        if (playerSurfaceSourceUrl == null || !platformPlayerSurfaceOwnsOverlay()) {
            overlayContent()
        }
    }
}

internal fun PlayerScreenRuntime.onPlayerSurfaceExit() {
    if (!playerControlsLocked) {
        controlsVisible = false
    }
    pausedOverlayVisible = false
}

internal fun PlayerScreenRuntime.cancelStartupFallback() {
    startupFallbackState = startupFallbackState.reduce(StartupFallbackEvent.Cancelled).state
}

@Composable
private fun PlayerScreenRuntime.RenderPlayerControls(isEpisode: Boolean) {
    val isInPip = rememberIsInPictureInPicture()
    AnimatedVisibility(
        visible = (controlsVisible || showParentalGuide) && !playerControlsLocked && !isInPip,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        PlayerControlsShell(
            title = title,
            streamTitle = activeStreamTitle,
            timelineIdentity = activeSourceUrl,
            providerName = activeProviderName,
            seasonNumber = activeSeasonNumber,
            episodeNumber = activeEpisodeNumber,
            episodeTitle = activeEpisodeTitle,
            playbackSnapshot = playbackSnapshot,
            isScrubbingTimeline = isScrubbingTimeline,
            playbackTimeline = playbackTimeline,
            metrics = metrics,
            resizeMode = resizeMode,
            isLocked = playerControlsLocked,
             showPlaybackControls = shouldRenderPlayerPlaybackControls(
                 controlsVisible = controlsVisible,
                 showParentalGuide = showParentalGuide,
             ),
            onLockToggle = {
                if (playerControlsLocked) unlockPlayerControls() else lockPlayerControls()
            },
            onBack = {
                flushWatchProgress()
                args.onBack()
            },
            onTogglePlayback = { togglePlayback() },
            volumeLevel = volumeLevel,
            showVolumeControl = playerController?.supportsVolumeControl() == true,
            onVolumeChanged = { level -> setVolumeLevel(level) },
            showFullscreenControl = playerController?.supportsFullscreenToggle() == true,
            onFullscreenClick = { playerController?.toggleFullscreen() },
            desktopLayout = isDesktopLayout,
            onSeekBack = { seekBy(-10_000L) },
            onSeekForward = { seekBy(10_000L) },
            onResizeModeClick = { cycleResizeMode() },
            onSpeedClick = { cyclePlaybackSpeed() },
            onSubtitleClick = {
                refreshTracks()
                showSubtitleModal = true
            },
            onAudioClick = {
                refreshTracks()
                showAudioModal = true
            },
            onVideoSettingsClick = if (isIos) {
                {
                    showVideoSettingsModal = true
                    controlsVisible = true
                }
            } else {
                null
            },
            onSourcesClick = if (activeVideoId != null) { { openSourcesPanel() } } else null,
            onEpisodesClick = if (isSeries) { { openEpisodesPanel() } } else null,
            onOpenInExternalPlayer = args.onOpenInExternalPlayer?.let { openExternal ->
                {
                    val loadedSubtitles = addonSubtitles
                        .takeIf { it.isNotEmpty() }
                        ?.map { sub ->
                            SubtitleInput(
                                url = sub.url,
                                name = buildString {
                                    if (!sub.addonName.isNullOrBlank()) append("[${sub.addonName}] ")
                                    append(sub.display)
                                },
                                lang = sub.language,
                            )
                        }
                    openExternal(
                        ExternalPlayerPlaybackRequest(
                            sourceUrl = activeSourceUrl,
                            title = title,
                            streamTitle = activeStreamTitle,
                            sourceHeaders = activeSourceHeaders,
                            resumePositionMs = latestPlaybackSnapshot.positionMs,
                            subtitles = loadedSubtitles,
                            season = activeSeasonNumber,
                            episode = activeEpisodeNumber,
                            episodeTitle = activeEpisodeTitle,
                        ),
                    )
                }
            },
            onSubmitIntroClick = if (
                isSeries &&
                playerSettingsUiState.introSubmitEnabled &&
                playerSettingsUiState.introDbApiKey.isNotBlank()
            ) {
                { showSubmitIntroModal = true }
            } else {
                null
            },
            parentalWarnings = parentalWarnings,
            showParentalGuide = showParentalGuide,
            onParentalGuideAnimationComplete = { showParentalGuide = false },
            onScrubChange = {
                isScrubbingTimeline = true
            },
             onScrubFinished = { positionMs ->
                 isScrubbingTimeline = false
                 playerController?.seekTo(positionMs)
                 scheduleProgressSyncAfterSeek()
             },
            horizontalSafePadding = horizontalSafePadding,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun BoxScope.RenderPlaybackOverlays(
    runtime: PlayerScreenRuntime,
    currentGestureFeedback: GestureFeedbackState?,
    p2pInitialLoadingMessage: String?,
    p2pInitialLoadingProgress: Float?,
    showP2pRebufferStats: Boolean,
    p2pRebufferMessage: String?,
    p2pRebufferProgress: Float?,
    displayArtwork: String?,
    displayLogo: String?,
) {
    runtime.run {
         PlayerPlaybackOverlays(
             playerControlsLocked = playerControlsLocked,
             lockedOverlayVisible = lockedOverlayVisible,
             playbackSnapshot = playbackSnapshot,
         playbackTimeline = playbackTimeline,
        metrics = metrics,
        horizontalSafePadding = horizontalSafePadding,
        onUnlock = { unlockPlayerControls() },
         showOpeningOverlay = playerSettingsUiState.showLoadingOverlay &&
             !initialLoadCompleted &&
             !playbackSnapshot.isStartupStalled &&
             errorMessage == null,
         backdropArtwork = displayArtwork,
         logo = displayLogo,
        title = title,
        onBackWithProgress = {
            flushWatchProgress()
            args.onBack()
        },
        p2pInitialLoadingMessage = p2pInitialLoadingMessage,
        p2pInitialLoadingProgress = p2pInitialLoadingProgress,
        showP2pRebufferStats = showP2pRebufferStats,
        p2pRebufferMessage = p2pRebufferMessage,
        p2pRebufferProgress = p2pRebufferProgress,
        currentGestureFeedback = currentGestureFeedback,
        renderedGestureFeedback = renderedGestureFeedback,
        initialLoadCompleted = initialLoadCompleted,
        pausedOverlayVisible = pausedOverlayVisible,
        activeSkipInterval = activeSkipInterval,
        skipIntervalDismissed = skipIntervalDismissed,
        controlsVisible = controlsVisible,
        onSkipInterval = { interval ->
            playerController?.seekTo((interval.endTime * 1000).toLong())
            scheduleProgressSyncAfterSeek()
            skipIntervalDismissed = true
        },
        onDismissSkipInterval = { skipIntervalDismissed = true },
        sliderEdgePadding = sliderEdgePadding,
        overlayBottomPadding = overlayBottomPadding,
        isSeries = isSeries,
        nextEpisodeInfo = nextEpisodeInfo,
        showNextEpisodeCard = showNextEpisodeCard,
        nextEpisodeAutoPlaySearching = nextEpisodeAutoPlaySearching,
        nextEpisodeAutoPlaySourceName = nextEpisodeAutoPlaySourceName,
        nextEpisodeAutoPlayCountdown = nextEpisodeAutoPlayCountdown,
        onPlayNextEpisode = {
            nextEpisodeAutoPlayJob?.cancel()
            playNextEpisode()
        },
         onDismissNextEpisode = {
             nextEpisodeAutoPlayJob?.cancel()
             showNextEpisodeCard = false
             nextEpisodeAutoPlaySearching = false
             nextEpisodeAutoPlaySourceName = null
              nextEpisodeAutoPlayCountdown = null
          },
         startupFallbackCandidate = pendingStartupFallbackCandidate,
         onStartupFallbackApproved = { approveStartupFallback() },
         onStartupFallbackDenied = { denyStartupFallback() },
          onRetryStartup = {
             errorMessage = null
             playerController?.retry()
         },
         errorMessage = errorMessage,
            onDismissError = {
                flushWatchProgress()
                args.onBack()
            },
        )
    }
}

@Composable
private fun PlayerScreenRuntime.RenderPlayerModals(displayedPositionMs: Long) {
    PlayerScreenModalHosts(
        pendingP2pSwitch = pendingP2pSwitch,
        onPendingP2pSwitchChanged = { pendingP2pSwitch = it },
        onP2pEpisodeStreamSelected = { stream, episode, isAutoPlay ->
            switchToP2pEpisodeStream(stream, episode, isAutoPlay)
        },
        onP2pSourceStreamSelected = { stream -> switchToP2pSourceStream(stream) },
        onNextEpisodeAutoPlaySearchingChanged = { nextEpisodeAutoPlaySearching = it },
        onNextEpisodeAutoPlayCountdownChanged = { nextEpisodeAutoPlayCountdown = it },
        onNextEpisodeAutoPlaySourceNameChanged = { nextEpisodeAutoPlaySourceName = it },
        showAudioModal = showAudioModal,
        audioTracks = audioTracks,
        selectedAudioIndex = selectedAudioIndex,
        onAudioTrackSelected = { index ->
            selectedAudioIndex = index
            persistAudioPreference(audioTracks.firstOrNull { it.index == index })
            playerController?.selectAudioTrack(index)
            scope.launch {
                kotlinx.coroutines.delay(200)
                showAudioModal = false
            }
        },
        onAudioModalDismissed = { showAudioModal = false },
        showSubtitleModal = showSubtitleModal,
        activeSubtitleTab = activeSubtitleTab,
        subtitleTracks = subtitleTracks,
        selectedSubtitleIndex = selectedSubtitleIndex,
        addonSubtitles = visibleAddonSubtitles,
        selectedAddonSubtitleId = selectedAddonSubtitleId,
        isLoadingAddonSubtitles = isLoadingAddonSubtitles,
        subtitleStyle = subtitleStyle,
        subtitleDelayMs = subtitleDelayMs,
        selectedAddonSubtitle = selectedAddonSubtitle,
        subtitleAutoSyncState = subtitleAutoSyncState,
        onSubtitleTabSelected = { activeSubtitleTab = it },
        onBuiltInSubtitleTrackSelected = { index ->
            val wasCustom = useCustomSubtitles
            selectedSubtitleIndex = index
            selectedAddonSubtitleId = null
            useCustomSubtitles = false
            persistInternalSubtitlePreference(subtitleTracks.firstOrNull { it.index == index })
            if (wasCustom) {
                playerController?.clearExternalSubtitleAndSelect(index)
            } else {
                playerController?.selectSubtitleTrack(index)
            }
        },
        onAddonSubtitleSelected = { addon ->
            selectedAddonSubtitleId = addon.id
            selectedSubtitleIndex = -1
            useCustomSubtitles = true
            persistAddonSubtitlePreference(addon)
            playerController?.setSubtitleUri(addon.url)
        },
        onFetchAddonSubtitles = { fetchAddonSubtitlesForActiveItem() },
        onSubtitleStyleChanged = PlayerSettingsRepository::updateSubtitleStyle,
        onSubtitleDelayChanged = { delayMs -> setSubtitleDelay(delayMs) },
        onSubtitleDelayReset = { setSubtitleDelay(0) },
        onAutoSyncCapture = { captureSubtitleAutoSyncTime() },
        onAutoSyncCueSelected = { cue -> applySubtitleAutoSyncCue(cue) },
        onAutoSyncReload = { loadSubtitleAutoSyncCues(force = true) },
        onSubtitleModalDismissed = { showSubtitleModal = false },
        showVideoSettingsModal = showVideoSettingsModal,
        playerSettings = playerSettingsUiState,
        onVideoSettingsChanged = {
            playerController?.configureIosVideoOutput(PlayerSettingsRepository.uiState.value)
        },
        onVideoSettingsModalDismissed = { showVideoSettingsModal = false },
        showSourcesPanel = showSourcesPanel,
        sourceStreamsState = sourceStreamsState,
        activeSourceUrl = activeSourceUrl,
        activeStreamTitle = activeStreamTitle,
        onSourceFilterSelected = PlayerStreamsRepository::selectSourceFilter,
        onSourceStreamSelected = { stream -> switchToSource(stream) },
        onReloadSources = {
            val vid = activeVideoId
            if (vid != null) {
                PlayerStreamsRepository.loadSources(
                    type = contentType ?: parentMetaType,
                    videoId = vid,
                    season = activeSeasonNumber,
                    episode = activeEpisodeNumber,
                    forceRefresh = true,
                )
            }
        },
        onSourcesPanelDismissed = {
            showSourcesPanel = false
            controlsVisible = true
        },
        desktopLayout = isDesktopLayout,
        isSeries = isSeries,
        showEpisodesPanel = showEpisodesPanel,
        allEpisodes = playerMetaVideos,
        parentMetaType = parentMetaType,
        parentMetaId = parentMetaId,
        activeSeasonNumber = activeSeasonNumber,
        activeEpisodeNumber = activeEpisodeNumber,
        watchProgressByVideoId = watchProgressUiState.byVideoIdForContent(parentMetaId),
        watchedKeys = watchedUiState.watchedKeys,
        blurUnwatchedEpisodes = metaScreenSettingsUiState.blurUnwatchedEpisodes,
        episodeStreamsPanelState = episodeStreamsPanelState,
        episodeStreamsRepoState = episodeStreamsRepoState,
        onEpisodeSelectedForDownload = { episode ->
            selectDownloadedEpisodeForPlayback(
                parentMetaId = parentMetaId,
                episode = episode,
                onDownloadedEpisodeSelected = { item, video -> switchToDownloadedEpisode(item, video) },
            )
        },
        onEpisodeStreamsRequested = { episode ->
            PlayerStreamsRepository.loadEpisodeStreams(
                type = contentType ?: parentMetaType,
                videoId = episode.id,
                season = episode.season,
                episode = episode.episode,
            )
            episodeStreamsPanelState = EpisodeStreamsPanelState(showStreams = true, selectedEpisode = episode)
        },
        onEpisodeStreamFilterSelected = PlayerStreamsRepository::selectEpisodeStreamsFilter,
        onEpisodeStreamSelected = { stream, episode -> switchToEpisodeStream(stream, episode) },
        onBackToEpisodes = {
            episodeStreamsPanelState = EpisodeStreamsPanelState()
            PlayerStreamsRepository.clearEpisodeStreams()
        },
        onReloadEpisodeStreams = {
            val episode = episodeStreamsPanelState.selectedEpisode
            if (episode != null) {
                PlayerStreamsRepository.loadEpisodeStreams(
                    type = contentType ?: parentMetaType,
                    videoId = episode.id,
                    season = episode.season,
                    episode = episode.episode,
                    forceRefresh = true,
                )
            }
        },
        onEpisodesPanelDismissed = {
            showEpisodesPanel = false
            episodeStreamsPanelState = EpisodeStreamsPanelState()
            PlayerStreamsRepository.clearEpisodeStreams()
            controlsVisible = true
        },
        showSubmitIntroModal = showSubmitIntroModal,
        activeVideoId = activeVideoId,
        metaUiState = metaUiState,
        displayedPositionMs = displayedPositionMs,
        submitIntroSegmentType = submitIntroSegmentType,
        onSubmitIntroSegmentTypeChanged = { submitIntroSegmentType = it },
        submitIntroStartTimeStr = submitIntroStartTimeStr,
        onSubmitIntroStartTimeChanged = { submitIntroStartTimeStr = it },
        submitIntroEndTimeStr = submitIntroEndTimeStr,
        onSubmitIntroEndTimeChanged = { submitIntroEndTimeStr = it },
        onSubmitIntroDismissed = { showSubmitIntroModal = false },
        onSubmitIntroSuccess = {
            submitIntroStartTimeStr = "00:00"
            submitIntroEndTimeStr = "00:00"
            submitIntroSegmentType = "intro"
            showSubmitIntroModal = false
        },
    )
}
