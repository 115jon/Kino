package com.nuvio.app.features.details

internal fun detailUsesDesktopSeasonRail(
    widthDp: Float,
    desktopPlatform: Boolean = false,
): Boolean = (desktopPlatform && widthDp >= 768f) || widthDp >= 1100f
