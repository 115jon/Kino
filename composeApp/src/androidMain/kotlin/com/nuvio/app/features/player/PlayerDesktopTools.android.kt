package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun DesktopPlayerToolCluster(
    volumeLevel: PlayerAudioLevel,
    showVolumeControl: Boolean,
    onVolumeChanged: (Float) -> Unit,
    showFullscreenControl: Boolean,
    onFullscreenClick: () -> Unit,
    modifier: Modifier,
) {
    modifier
}
