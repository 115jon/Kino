package com.nuvio.app.features.player

internal val PlayerScreenRuntime.hasOpenPlayerOverlay: Boolean
    get() = showSourcesPanel ||
        showEpisodesPanel ||
        showSubmitIntroModal ||
        showSwitchEngineConfirm ||
        showAudioModal ||
        showSubtitleModal ||
        showVideoSettingsModal ||
        showStreamInfo ||
        showPartyPanel ||
        showPartyChat

internal val PlayerScreenRuntime.shouldHideDesktopPointer: Boolean
    get() = isDesktopLayout &&
        !controlsVisible &&
        playbackSnapshot.isPlaying &&
        !hasOpenPlayerOverlay
