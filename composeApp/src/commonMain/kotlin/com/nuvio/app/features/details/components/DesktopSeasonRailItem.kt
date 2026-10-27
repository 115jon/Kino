package com.nuvio.app.features.details.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.nuvio.app.core.ui.NuvioRailItem
import com.nuvio.app.core.ui.NuvioRailItemDefaults
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio

@Composable
internal fun DesktopSeasonRailItem(
    label: String,
    episodeCount: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onSecondaryClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    NuvioRailItem(
        selected = selected,
        onClick = onClick,
        onSecondaryClick = onSecondaryClick,
        colors = NuvioRailItemDefaults.highlightColors(),
        contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s18, vertical = NuvioTokens.Space.s12),
        modifier = modifier,
    ) { state ->
        val primaryColor = when {
            state.selected -> Color.Black
            state.hovered -> tokens.colors.textPrimary
            else -> tokens.colors.textSecondary
        }
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (state.selected) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = primaryColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = episodeCount.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = if (state.selected) Color.Black.copy(alpha = tokens.opacity.secondary) else tokens.colors.textMuted,
        )
    }
}
