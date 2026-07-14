package com.nuvio.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_fullscreen
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun DesktopPlayerToolCluster(
    volumeLevel: PlayerAudioLevel,
    showVolumeControl: Boolean,
    onVolumeChanged: (Float) -> Unit,
    showFullscreenControl: Boolean,
    onFullscreenClick: () -> Unit,
    modifier: Modifier,
) {
    if (!showVolumeControl && !showFullscreenControl) return

    var isHovered by remember { mutableStateOf(false) }
    val isMuted = volumeLevel.isMuted || volumeLevel.fraction <= 0f
    val volumeIcon = if (isMuted) {
        Icons.AutoMirrored.Rounded.VolumeOff
    } else {
        Icons.AutoMirrored.Rounded.VolumeUp
    }
    Column(
        modifier = modifier
            .onPointerEvent(PointerEventType.Enter, PointerEventPass.Initial) { isHovered = true }
            .onPointerEvent(PointerEventType.Exit, PointerEventPass.Initial) { isHovered = false },
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.Bottom,
    ) {
        AnimatedVisibility(visible = showVolumeControl && isHovered) {
            Surface(
                color = ColorBlack.copy(alpha = 0.82f),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 4.dp,
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    androidx.compose.material3.Text(
                        text = desktopVolumeLabel(volumeLevel),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                    )
                    Box(
                        modifier = Modifier.size(width = 40.dp, height = 128.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Slider(
                            value = volumeLevel.fraction.coerceIn(0f, 1f),
                            onValueChange = onVolumeChanged,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.White.copy(alpha = 0.28f),
                            ),
                            modifier = Modifier
                                .requiredWidth(128.dp)
                                .requiredHeight(32.dp)
                                .rotate(270f),
                        )
                    }
                }
            }
        }

        Surface(
            color = ColorBlack.copy(alpha = 0.5f),
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.2f),
                shape = MaterialTheme.shapes.extraLarge,
            ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showVolumeControl) {
                    IconButton(
                        onClick = { isHovered = true },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            imageVector = volumeIcon,
                            contentDescription = desktopVolumeLabel(volumeLevel),
                            tint = Color.White,
                        )
                    }
                }
                if (showFullscreenControl) {
                    IconButton(
                        onClick = onFullscreenClick,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Fullscreen,
                            contentDescription = stringResource(Res.string.compose_player_fullscreen),
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}

private val ColorBlack = androidx.compose.ui.graphics.Color.Black
