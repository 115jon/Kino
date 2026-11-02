package com.nuvio.app.core.diagnostics

import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.settings.ThemeModeRepository
import com.nuvio.app.features.settings.ThemeSettingsRepository

internal object ReportSettingsSnapshot {
    fun collect(redactor: ReportRedactor): Map<String, String> {
        val values = linkedMapOf<String, String>()
        runCatching {
            values["appearance.theme"] = ThemeSettingsRepository.selectedTheme.value.name
            values["appearance.themeMode"] = ThemeModeRepository.mode.value.name
            values["appearance.language"] = ThemeSettingsRepository.selectedAppLanguage.value.name
            values["appearance.amoled"] = ThemeSettingsRepository.amoledEnabled.value.toString()
        }
        runCatching {
            values["diagnostics.crashReports"] = CrashReportSettings.enabled.value.toString()
        }
        runCatching {
            val profiles = ProfileRepository.state.value
            values["profiles.count"] = profiles.profiles.size.toString()
            values["profiles.activeIndex"] = ProfileRepository.activeProfileId.toString()
        }
        runCatching {
            val addons = AddonRepository.uiState.value.addons
            values["addons.count"] = addons.size.toString()
            addons.forEachIndexed { index, addon ->
                val name = addon.manifest?.name ?: "unknown"
                val state = if (addon.enabled) "on" else "off"
                values["addons.$index"] = "$name | $state | ${addon.manifestUrl}"
            }
        }
        runCatching {
            PlayerSettingsRepository.ensureLoaded()
            values += flattenDataClassText("player", PlayerSettingsRepository.uiState.value.toString())
        }
        return sanitizeSettings(values, redactor)
    }
}

internal fun sanitizeSettings(values: Map<String, String>, redactor: ReportRedactor): Map<String, String> =
    values
        .filterKeys { key -> !ReportRedactor.isSecretName(key.substringAfterLast('.')) }
        .mapValues { (_, value) -> redactor.redact(value) }

internal fun flattenDataClassText(prefix: String, text: String): Map<String, String> {
    val body = dataClassBody(text) ?: return mapOf(prefix to text)
    val result = linkedMapOf<String, String>()
    splitTopLevel(body).forEach { field ->
        val name = field.substringBefore('=').trim()
        val value = field.substringAfter('=', "").trim()
        if (name.isEmpty()) return@forEach
        val key = "$prefix.$name"
        if (dataClassBody(value) != null) result += flattenDataClassText(key, value) else result[key] = value
    }
    return result
}

private val dataClassTextPattern = Regex("""^[A-Za-z_][A-Za-z0-9_]*\((.*)\)$""", RegexOption.DOT_MATCHES_ALL)

private fun dataClassBody(text: String): String? = dataClassTextPattern.matchEntire(text.trim())?.groupValues?.get(1)

private fun splitTopLevel(body: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var depth = 0
    body.forEach { char ->
        when (char) {
            '(', '[', '{' -> depth++
            ')', ']', '}' -> depth--
        }
        if (char == ',' && depth == 0) {
            parts += current.toString()
            current.clear()
        } else {
            current.append(char)
        }
    }
    if (current.isNotBlank()) parts += current.toString()
    return parts
}
