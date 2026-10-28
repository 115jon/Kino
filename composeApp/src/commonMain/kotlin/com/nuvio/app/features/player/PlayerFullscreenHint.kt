package com.nuvio.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvioTypeScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_fullscreen_exit_hint
import nuvio.composeapp.generated.resources.player_key_esc
import org.jetbrains.compose.resources.stringResource

internal const val PlayerFullscreenHintDurationMs = 3_000L
private const val KeycapMarker = "\u0000"

@Composable
internal fun rememberPlayerFullscreen(controller: PlayerEngineController?): Boolean {
    val flow = remember(controller) { controller?.fullscreen ?: MutableStateFlow(false) }
    return flow.collectAsState().value
}

@Composable
internal fun PlayerFullscreenHint(
    isFullscreen: Boolean,
    modifier: Modifier = Modifier,
    suppressed: Boolean = false,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(isFullscreen) {
        visible = isFullscreen
        if (isFullscreen) {
            delay(PlayerFullscreenHintDurationMs)
            visible = false
        }
    }
    AnimatedVisibility(
        visible = visible && !suppressed,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(400)),
        modifier = modifier,
    ) {
        val parts = stringResource(Res.string.player_fullscreen_exit_hint, KeycapMarker).split(KeycapMarker, limit = 2)
        val textStyle = MaterialTheme.nuvioTypeScale.bodyMd.copy(fontWeight = FontWeight.Medium)
        Row(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(24.dp))
                .padding(horizontal = 16.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            parts.first().trim().takeIf { it.isNotEmpty() }?.let { Text(it, style = textStyle, color = Color.White) }
            PlayerKeycap(stringResource(Res.string.player_key_esc))
            parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { Text(it, style = textStyle, color = Color.White) }
        }
    }
}
