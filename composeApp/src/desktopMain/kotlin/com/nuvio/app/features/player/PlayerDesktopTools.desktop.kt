package com.nuvio.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.accentBrush
import com.nuvio.app.core.ui.nuvioTypeScale
import com.nuvio.app.core.ui.themePalette
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_fullscreen
import nuvio.composeapp.generated.resources.compose_player_volume
import org.jetbrains.compose.resources.stringResource

private val DesktopVolumeSliderWidth = 96.dp
private val DesktopVolumeLabelWidth = 40.dp

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
    val volumeInteraction = remember { MutableInteractionSource() }
    val isVolumeDragged by volumeInteraction.collectIsDraggedAsState()
    val isVolumePressed by volumeInteraction.collectIsPressedAsState()
    val isVolumeFocused by volumeInteraction.collectIsFocusedAsState()
    val volumeExpanded = showVolumeControl && (isHovered || isVolumeDragged || isVolumePressed || isVolumeFocused)
    val isMuted = volumeLevel.isMuted || volumeLevel.fraction <= 0f
    val volumeIcon = if (isMuted) {
        Icons.AutoMirrored.Rounded.VolumeOff
    } else {
        Icons.AutoMirrored.Rounded.VolumeUp
    }

    Row(
        modifier = modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .onPointerEvent(PointerEventType.Enter, PointerEventPass.Initial) { isHovered = true }
            .onPointerEvent(PointerEventType.Exit, PointerEventPass.Initial) { isHovered = false },
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showVolumeControl) {
            AnimatedVisibility(
                visible = volumeExpanded,
                enter = expandHorizontally(tween(180), expandFrom = Alignment.End) + fadeIn(tween(180)),
                exit = shrinkHorizontally(tween(160), shrinkTowards = Alignment.End) + fadeOut(tween(120)),
            ) {
                DesktopVolumeSlider(
                    volumeLevel = volumeLevel,
                    onVolumeChanged = onVolumeChanged,
                    interactionSource = volumeInteraction,
                )
            }
            PlayerAction(
                description = desktopVolumeLabel(volumeLevel),
                onClick = { isHovered = true },
                icon = volumeIcon,
            )
        }
        if (showFullscreenControl) {
            PlayerAction(
                description = stringResource(Res.string.compose_player_fullscreen),
                onClick = onFullscreenClick,
                icon = Icons.Rounded.Fullscreen,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DesktopVolumeSlider(
    volumeLevel: PlayerAudioLevel,
    onVolumeChanged: (Float) -> Unit,
    interactionSource: MutableInteractionSource,
) {
    val accentBrush = MaterialTheme.themePalette.accentBrush()
    val description = stringResource(Res.string.compose_player_volume)
    val isDragged by interactionSource.collectIsDraggedAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val trackThickness by animateDpAsState(
        targetValue = if (isDragged || isPressed) 6.dp else 4.dp,
        animationSpec = tween(durationMillis = 140),
        label = "desktopVolumeTrackThickness",
    )
    val level = if (volumeLevel.isMuted) 0f else volumeLevel.fraction.coerceIn(0f, 1f)
    val sliderState = remember { SliderState() }
    sliderState.value = level

    Row(
        modifier = Modifier.padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = desktopVolumeLabel(volumeLevel),
            color = Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.nuvioTypeScale.bodySm.copy(fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(DesktopVolumeLabelWidth),
        )
        Slider(
            state = sliderState,
            onValueChange = onVolumeChanged,
            interactionSource = interactionSource,
            thumb = {},
            track = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .drawBehind {
                            val height = trackThickness.toPx()
                            val origin = Offset(0f, (size.height - height) / 2f)
                            val radius = CornerRadius(height / 2f)
                            drawRoundRect(
                                color = Color.White.copy(alpha = 0.3f),
                                topLeft = origin,
                                size = Size(size.width, height),
                                cornerRadius = radius,
                            )
                            drawRoundRect(
                                brush = accentBrush,
                                topLeft = origin,
                                size = Size(size.width * sliderState.value, height),
                                cornerRadius = radius,
                            )
                        },
                )
            },
            modifier = Modifier
                .padding(start = 10.dp, end = 4.dp)
                .width(DesktopVolumeSliderWidth)
                .height(48.dp)
                .semantics { contentDescription = description },
        )
    }
}
