package com.nuvio.app.features.settings

internal expect object VideoOutputSettingsPlatform {
    val hdrPassthroughSupported: Boolean

    fun loadHdrPassthroughEnabled(): Boolean

    fun saveHdrPassthroughEnabled(enabled: Boolean)
}
