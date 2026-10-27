package com.nuvio.app.features.settings

import com.nuvio.app.desktop.DesktopPreferences

internal actual object VideoOutputSettingsPlatform {
    private const val preferencesName = "kino_video_output"
    private const val hdrPassthroughKey = "hdr_passthrough"

    actual val hdrPassthroughSupported: Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")

    actual fun loadHdrPassthroughEnabled(): Boolean =
        DesktopPreferences.getBoolean(preferencesName, hdrPassthroughKey) ?: false

    actual fun saveHdrPassthroughEnabled(enabled: Boolean) {
        DesktopPreferences.putBoolean(preferencesName, hdrPassthroughKey, enabled)
    }
}
