package com.nuvio.app

internal enum class AppNavigationMode {
    Mobile,
    Tablet,
    Desktop,
}

internal fun appNavigationMode(widthDp: Float, desktopPlatform: Boolean = false): AppNavigationMode = when {
    (desktopPlatform && widthDp >= 768f) || widthDp >= 1100f -> AppNavigationMode.Desktop
    widthDp >= 768f -> AppNavigationMode.Tablet
    else -> AppNavigationMode.Mobile
}
