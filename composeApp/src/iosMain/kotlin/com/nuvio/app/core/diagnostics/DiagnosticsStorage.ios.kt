package com.nuvio.app.core.diagnostics

import platform.Foundation.NSUserDefaults

internal actual object DiagnosticsStorage {
    private const val crashReportsKey = "kino_crash_reports_enabled"
    private const val installIdKey = "kino_install_id"
    private const val legacyCrashReportsKey = "sentry_enabled"

    actual fun loadCrashReportsEnabled(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        return when {
            defaults.objectForKey(crashReportsKey) != null -> defaults.boolForKey(crashReportsKey)
            defaults.objectForKey(legacyCrashReportsKey) != null -> defaults.boolForKey(legacyCrashReportsKey)
            else -> null
        }
    }

    actual fun saveCrashReportsEnabled(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = crashReportsKey)
    }

    actual fun loadInstallId(): String? = NSUserDefaults.standardUserDefaults.stringForKey(installIdKey)

    actual fun saveInstallId(installId: String) {
        NSUserDefaults.standardUserDefaults.setObject(installId, forKey = installIdKey)
    }
}
