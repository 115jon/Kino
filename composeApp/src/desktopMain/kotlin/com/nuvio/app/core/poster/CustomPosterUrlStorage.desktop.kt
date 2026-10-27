package com.nuvio.app.core.poster

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.desktop.DesktopPreferences

internal actual object CustomPosterUrlStorage {
    private const val preferencesName = "nuvio_custom_poster_url"
    private const val patternKey = "custom_poster_url_pattern"
    private const val enabledScreensKey = "custom_poster_enabled_screens"

    actual fun loadPattern(): String? =
        DesktopPreferences.getString(preferencesName, ProfileScopedKey.of(patternKey))

    actual fun savePattern(pattern: String?) {
        DesktopPreferences.putNullableString(
            preferencesName,
            ProfileScopedKey.of(patternKey),
            pattern?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    actual fun loadEnabledScreens(): Set<String>? =
        DesktopPreferences.getStringSet(preferencesName, ProfileScopedKey.of(enabledScreensKey))

    actual fun saveEnabledScreens(keys: Set<String>?) {
        if (keys == null) {
            DesktopPreferences.remove(preferencesName, ProfileScopedKey.of(enabledScreensKey))
        } else {
            DesktopPreferences.putStringSet(preferencesName, ProfileScopedKey.of(enabledScreensKey), keys)
        }
    }
}
