package com.nuvio.app

internal enum class AppNavigationMode {
    Mobile,
    Tablet,
    Desktop,
}

internal fun appNavigationMode(widthDp: Float): AppNavigationMode = when {
    widthDp >= 1100f -> AppNavigationMode.Desktop
    widthDp >= 768f -> AppNavigationMode.Tablet
    else -> AppNavigationMode.Mobile
}
