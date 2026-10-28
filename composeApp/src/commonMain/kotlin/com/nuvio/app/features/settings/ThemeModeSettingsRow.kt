package com.nuvio.app.features.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.ThemeMode
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_appearance_theme_mode
import nuvio.composeapp.generated.resources.settings_appearance_theme_mode_dark
import nuvio.composeapp.generated.resources.settings_appearance_theme_mode_light
import nuvio.composeapp.generated.resources.settings_appearance_theme_mode_system
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val ThemeMode.labelRes: StringResource
    get() = when (this) {
        ThemeMode.SYSTEM -> Res.string.settings_appearance_theme_mode_system
        ThemeMode.LIGHT -> Res.string.settings_appearance_theme_mode_light
        ThemeMode.DARK -> Res.string.settings_appearance_theme_mode_dark
    }

@Composable
internal fun ThemeModeSettingsRow(isTablet: Boolean) {
    val selected by remember {
        ThemeModeRepository.ensureLoaded()
        ThemeModeRepository.mode
    }.collectAsStateWithLifecycle()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (isTablet) 20.dp else 16.dp,
                vertical = if (isTablet) 14.dp else 12.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.settings_appearance_theme_mode),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.nuvio.colors.textPrimary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        ThemeModeSegmentedControl(
            selected = selected,
            onSelected = ThemeModeRepository::setMode,
        )
    }
}

@Composable
private fun ThemeModeSegmentedControl(
    selected: ThemeMode,
    onSelected: (ThemeMode) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .clip(tokens.shapes.chip)
            .background(tokens.colors.surfaceCard)
            .padding(NuvioTokens.Space.s3)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2),
    ) {
        ThemeMode.entries.forEach { mode ->
            val isSelected = mode == selected
            val background by animateColorAsState(if (isSelected) tokens.colors.accent else Color.Transparent)
            val content by animateColorAsState(if (isSelected) tokens.colors.onAccent else tokens.colors.textSecondary)
            Box(
                modifier = Modifier
                    .clip(tokens.shapes.chip)
                    .background(background)
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onSelected(mode) },
                    )
                    .padding(horizontal = NuvioTokens.Space.s14, vertical = NuvioTokens.Space.s7),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(mode.labelRes),
                    style = MaterialTheme.typography.labelLarge,
                    color = content,
                )
            }
        }
    }
}
