package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlin.math.roundToInt

internal fun desktopVolumeLabel(volumeLevel: PlayerAudioLevel): String {
    val fraction = if (volumeLevel.isMuted) 0f else volumeLevel.fraction
    return "${(fraction.coerceIn(0f, 1f) * 100f).roundToInt()}%"
}

@Composable
internal expect fun DesktopPlayerToolCluster(
    volumeLevel: PlayerAudioLevel,
    showVolumeControl: Boolean,
    onVolumeChanged: (Float) -> Unit,
    showFullscreenControl: Boolean,
    onFullscreenClick: () -> Unit,
    modifier: Modifier = Modifier,
)
