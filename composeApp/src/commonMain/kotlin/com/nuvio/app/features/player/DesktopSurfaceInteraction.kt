package com.nuvio.app.features.player

internal fun shouldNotifyPlayerSurfaceExit(wasInside: Boolean, isInside: Boolean): Boolean =
    wasInside && !isInside

internal enum class WindowsPlayerPointerTarget {
    Video,
    Overlay,
}

internal fun shouldForwardWindowsPlayerClick(target: WindowsPlayerPointerTarget): Boolean =
    target == WindowsPlayerPointerTarget.Video

internal fun shouldRestoreWindowsFullscreen(isFullscreen: Boolean, isIconified: Boolean): Boolean =
    isFullscreen && isIconified

internal fun shouldRenderPlayerPlaybackControls(
    controlsVisible: Boolean,
    showParentalGuide: Boolean,
): Boolean = controlsVisible || !showParentalGuide
