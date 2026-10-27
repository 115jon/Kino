package com.nuvio.app.features.mdblist

import com.nuvio.app.desktop.DesktopPreferences

internal actual object PlatformMdbListAuthPersistence : MdbListAuthPersistence {
    private const val preferencesName = "nuvio_mdblist_auth"

    actual override fun read(profileId: Int): String? =
        DesktopPreferences.getString(preferencesName, "profile.$profileId")

    actual override fun write(profileId: Int, value: String?) {
        DesktopPreferences.putNullableString(preferencesName, "profile.$profileId", value)
    }

    actual override fun clear() {
        DesktopPreferences.clearNode(preferencesName)
    }
}
