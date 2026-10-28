package com.nuvio.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvioTypeScale
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private class PlayerShortcutRow(val keys: List<String>, val label: StringResource)

@Composable
private fun playerShortcutRows(): List<PlayerShortcutRow> {
    val space = stringResource(Res.string.player_key_space)
    val shift = stringResource(Res.string.player_key_shift)
    val esc = stringResource(Res.string.player_key_esc)
    return listOf(
        PlayerShortcutRow(listOf(space, "K"), Res.string.player_shortcut_play_pause),
        PlayerShortcutRow(listOf("←", "→", "J", "L"), Res.string.player_shortcut_seek),
        PlayerShortcutRow(listOf(shift, "←", "→"), Res.string.player_shortcut_seek_long),
        PlayerShortcutRow(listOf("↑", "↓"), Res.string.player_shortcut_volume),
        PlayerShortcutRow(listOf("M"), Res.string.player_shortcut_mute),
        PlayerShortcutRow(listOf("F", "F11"), Res.string.player_shortcut_fullscreen),
        PlayerShortcutRow(listOf(esc), Res.string.player_shortcut_back),
        PlayerShortcutRow(listOf("N"), Res.string.player_shortcut_next_episode),
        PlayerShortcutRow(listOf("S"), Res.string.player_shortcut_subtitle_menu),
        PlayerShortcutRow(listOf("A"), Res.string.player_shortcut_audio_menu),
        PlayerShortcutRow(listOf("C"), Res.string.player_shortcut_toggle_subtitles),
        PlayerShortcutRow(listOf("[", "]"), Res.string.player_shortcut_speed),
        PlayerShortcutRow(listOf("0–9"), Res.string.player_shortcut_jump),
        PlayerShortcutRow(listOf("?"), Res.string.player_shortcut_help),
    )
}

@Composable
internal fun PlayerShortcutHelp(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(140)),
        modifier = modifier,
    ) {
        val backgroundInteraction = remember { MutableInteractionSource() }
        val cardInteraction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(interactionSource = backgroundInteraction, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 380.dp)
                    .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(16.dp))
                    .clickable(interactionSource = cardInteraction, indication = null, onClick = {})
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(Res.string.player_shortcuts_title),
                    style = MaterialTheme.nuvioTypeScale.titleSm.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                playerShortcutRows().forEach { row ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            modifier = Modifier.width(132.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            row.keys.forEach { PlayerKeycap(it) }
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(row.label),
                            style = MaterialTheme.nuvioTypeScale.bodySm,
                            color = Color.White.copy(alpha = 0.86f),
                        )
                    }
                }
            }
        }
    }
}
