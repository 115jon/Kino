package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.diagnostics.CrashReportSettings
import com.nuvio.app.core.diagnostics.ManualReports
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_advanced_crash_reports
import nuvio.composeapp.generated.resources.settings_advanced_crash_reports_subtitle
import nuvio.composeapp.generated.resources.settings_advanced_section_diagnostics
import nuvio.composeapp.generated.resources.settings_advanced_send_logs
import nuvio.composeapp.generated.resources.settings_advanced_send_logs_subtitle
import nuvio.composeapp.generated.resources.settings_advanced_send_logs_unavailable
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DiagnosticsSection(isTablet: Boolean) {
    val configured = ManualReports.isConfigured
    var showSendLogs by rememberSaveable { mutableStateOf(false) }
    SettingsSection(
        title = stringResource(Res.string.settings_advanced_section_diagnostics),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            if (CrashReportSettings.isSupported) {
                val enabled by remember {
                    CrashReportSettings.ensureLoaded()
                    CrashReportSettings.enabled
                }.collectAsStateWithLifecycle()
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_advanced_crash_reports),
                    description = stringResource(Res.string.settings_advanced_crash_reports_subtitle),
                    checked = enabled,
                    isTablet = isTablet,
                    onCheckedChange = CrashReportSettings::setEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
            }
            SettingsNavigationRow(
                title = stringResource(Res.string.settings_advanced_send_logs),
                description = stringResource(
                    if (configured) Res.string.settings_advanced_send_logs_subtitle else Res.string.settings_advanced_send_logs_unavailable,
                ),
                enabled = configured,
                isTablet = isTablet,
                onClick = { showSendLogs = true },
            )
        }
    }
    if (showSendLogs && configured) {
        SendLogsDialog(onDismiss = { showSendLogs = false })
    }
}
