package com.nuvio.app.features.settings

import com.nuvio.app.desktop.DesktopPreferences
import com.nuvio.app.isWindowsPlatform

internal actual object VideoOutputSettingsPlatform {
    private const val preferencesName = "kino_video_output"
    private const val hdrPassthroughKey = "hdr_passthrough"

    actual val hdrPassthroughSupported: Boolean = isWindowsPlatform()

    actual fun loadHdrPassthroughEnabled(): Boolean =
        DesktopPreferences.getBoolean(preferencesName, hdrPassthroughKey) ?: false

    actual fun saveHdrPassthroughEnabled(enabled: Boolean) {
        DesktopPreferences.putBoolean(preferencesName, hdrPassthroughKey, enabled)
    }
}
