package com.nuvio.app.features.shuffle

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.desktop.DesktopPreferences

internal actual object EpisodeShuffleStorage {
    private const val preferencesName = "nuvio_episode_shuffle"

    actual fun load(profileId: Int): String? =
        DesktopPreferences.getString(preferencesName, ProfileScopedKey.of("episode_shuffle", profileId))

    actual fun save(profileId: Int, payload: String) {
        DesktopPreferences.putString(preferencesName, ProfileScopedKey.of("episode_shuffle", profileId), payload)
    }
}
