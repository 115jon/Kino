package com.nuvio.app.features.settings

internal actual object VideoOutputSettingsPlatform {
    actual val hdrPassthroughSupported: Boolean = false

    actual fun loadHdrPassthroughEnabled(): Boolean = false

    actual fun saveHdrPassthroughEnabled(enabled: Boolean) = Unit
}
