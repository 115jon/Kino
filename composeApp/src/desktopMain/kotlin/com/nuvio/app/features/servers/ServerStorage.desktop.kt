package com.nuvio.app.features.servers

import com.nuvio.app.desktop.DesktopPreferences

internal actual object ServerStorage {
    private const val preferencesName = "nuvio_servers"

    actual fun read(key: String): String? = DesktopPreferences.getString(preferencesName, key)

    actual fun write(key: String, value: String?) {
        DesktopPreferences.putNullableString(preferencesName, key, value)
    }

    actual fun clear() {
        DesktopPreferences.clearNode(preferencesName)
    }
}
