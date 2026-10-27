package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_playback_hdr_passthrough
import nuvio.composeapp.generated.resources.settings_playback_hdr_passthrough_description
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun HdrPassthroughSettingRow(isTablet: Boolean) {
    if (!VideoOutputSettingsPlatform.hdrPassthroughSupported) return
    var enabled by remember { mutableStateOf(VideoOutputSettingsPlatform.loadHdrPassthroughEnabled()) }
    SettingsGroupDivider(isTablet = isTablet)
    SettingsSwitchRow(
        title = stringResource(Res.string.settings_playback_hdr_passthrough),
        description = stringResource(Res.string.settings_playback_hdr_passthrough_description),
        checked = enabled,
        isTablet = isTablet,
        onCheckedChange = { checked ->
            VideoOutputSettingsPlatform.saveHdrPassthroughEnabled(checked)
            enabled = checked
        },
    )
}
