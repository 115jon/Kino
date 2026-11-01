package com.nuvio.app.core.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object CrashReportSettings {
    val isSupported: Boolean
        get() = DiagnosticsPlatform.crashReportsSupported

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
        _enabled.value = DiagnosticsStorage.loadCrashReportsEnabled() ?: true
    }

    fun setEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_enabled.value == enabled) return
        _enabled.value = enabled
        DiagnosticsStorage.saveCrashReportsEnabled(enabled)
    }
}
