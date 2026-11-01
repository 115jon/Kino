package com.nuvio.app.core.diagnostics

import android.content.Context
import android.content.SharedPreferences

internal actual object DiagnosticsStorage {
    private const val preferencesName = "kino_diagnostics"
    private const val crashReportsKey = "crash_reports_enabled"
    private const val installIdKey = "install_id"
    private const val legacyPreferencesName = "nuvio_sentry_settings"
    private const val legacyCrashReportsKey = "enabled"

    private var preferences: SharedPreferences? = null
    private var legacyPreferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        legacyPreferences = context.getSharedPreferences(legacyPreferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadCrashReportsEnabled(): Boolean? =
        preferences?.booleanOrNull(crashReportsKey) ?: legacyPreferences?.booleanOrNull(legacyCrashReportsKey)

    actual fun saveCrashReportsEnabled(enabled: Boolean) {
        preferences?.edit()?.putBoolean(crashReportsKey, enabled)?.apply()
    }

    actual fun loadInstallId(): String? = preferences?.getString(installIdKey, null)

    actual fun saveInstallId(installId: String) {
        preferences?.edit()?.putString(installIdKey, installId)?.apply()
    }

    private fun SharedPreferences.booleanOrNull(key: String): Boolean? =
        if (contains(key)) getBoolean(key, true) else null
}
