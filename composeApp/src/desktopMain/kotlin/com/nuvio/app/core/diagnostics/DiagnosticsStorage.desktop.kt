package com.nuvio.app.core.diagnostics

import com.nuvio.app.desktop.DesktopPreferences

internal actual object DiagnosticsStorage {
    private const val namespace = "kino_diagnostics"
    private const val crashReportsKey = "crash_reports_enabled"
    private const val installIdKey = "install_id"
    private const val legacyNamespace = "nuvio_sentry_settings"
    private const val legacyCrashReportsKey = "sentry_enabled"

    actual fun loadCrashReportsEnabled(): Boolean? =
        DesktopPreferences.getBoolean(namespace, crashReportsKey)
            ?: DesktopPreferences.getBoolean(legacyNamespace, legacyCrashReportsKey)

    actual fun saveCrashReportsEnabled(enabled: Boolean) {
        DesktopPreferences.putBoolean(namespace, crashReportsKey, enabled)
    }

    actual fun loadInstallId(): String? = DesktopPreferences.getString(namespace, installIdKey)

    actual fun saveInstallId(installId: String) {
        DesktopPreferences.putString(namespace, installIdKey, installId)
    }
}
