package com.nuvio.app.features.mdblist

import com.nuvio.app.desktop.DesktopPreferences

internal actual object PlatformMdbListSyncStorage : MdbListSyncStorage {
    private const val preferencesName = "nuvio_mdblist_sync"

    actual override suspend fun load(profileId: Int): String? =
        DesktopPreferences.getString(preferencesName, "profile.$profileId")

    actual override suspend fun save(profileId: Int, payload: String, checkScope: () -> Unit) {
        checkScope()
        DesktopPreferences.putString(preferencesName, "profile.$profileId", payload)
    }

    actual override suspend fun remove(profileId: Int, checkScope: () -> Unit) {
        checkScope()
        DesktopPreferences.remove(preferencesName, "profile.$profileId")
    }

    actual fun clearAll() {
        DesktopPreferences.clearNode(preferencesName)
    }
}
